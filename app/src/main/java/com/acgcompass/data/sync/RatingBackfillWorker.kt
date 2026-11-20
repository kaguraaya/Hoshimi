package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.NetworkSource
import com.acgcompass.core.network.SourceQuota
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingBackfillDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.repository.WorkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 后台补充其它平台评分：挑只有一个平台有评分（或还没有任何评分记录）的动画，逐部做和打开详情页时一样的评分刷新
 * （含按标题去 AniList、Tenrai 等平台匹配）。收藏和待补池里的作品优先，其余按评分人数。
 *
 * 一次最多 [BATCH] 部、一部一部地做；请求全部走后台优先级，前台页面（例如正在打开的详情页）的请求先走。
 * 每部作品的结果记在 `rating_backfill_state` 表里：查过了就一周后再看，因网络 / 限流没查成的几小时后就再试
 * （见 [RatingBackfillPolicy]）。候选在数据库里筛选，不整表读。执行细节（占位、单部限时、整批限时）见
 * [RatingBackfillRunner]。定时任务和「立即补一批」是两个任务名，同一时间只让一个在跑。
 */
@HiltWorker
@Suppress("LongParameterList") // Hilt 注入
class RatingBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val backfillDao: RatingBackfillDao,
    private val workDao: WorkDao,
    private val sourceQuota: SourceQuota,
    private val recorder: TaskRunRecorder,
) : CoroutineWorker(appContext, params) {
    // 任何异常都只记为这次失败，下个周期再跑；开关关闭、另一个任务在跑、平台都在冷却时提前结束。
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun doWork(): Result {
        if (!settings.scoreDiffAutoBackfill.first()) return Result.success()
        // 另一个补评分任务正在跑（定时任务和立即补一批同时被排上）：这一次直接让给它。
        if (!RUN_GATE.tryLock()) return Result.success()
        try {
            recorder.started(BackgroundTaskKind.RATING_BACKFILL)
            cooldownReason()?.let { reason ->
                recorder.failed(BackgroundTaskKind.RATING_BACKFILL, reason)
                return Result.success()
            }
            val progress = RatingBackfillRunner.Progress()
            return try {
                migrateLegacyAttempts()
                val batch = runBatch(progress)
                if (batch.failed == 0) {
                    recorder.succeeded(BackgroundTaskKind.RATING_BACKFILL, batch.summary())
                } else {
                    // 有作品没查成就记为失败：全部没查成、部分没查成都要在「后台任务」页看得出来。
                    recorder.failed(BackgroundTaskKind.RATING_BACKFILL, batch.failureReason(), batch.summary())
                }
                Result.success()
            } catch (error: CancellationException) {
                // 被系统停掉：已处理的作品结果都已写进表里；把停止原因和停在哪一部也记下来，
                // 不然页面上一直停在上一次的结果，也看不出为什么停。
                withContext(NonCancellable) {
                    recorder.failed(BackgroundTaskKind.RATING_BACKFILL, stoppedReason(progress))
                }
                throw error
            } catch (error: Exception) {
                recorder.failed(BackgroundTaskKind.RATING_BACKFILL, TaskRunRecorder.describe(error))
                Result.success()
            }
        } finally {
            RUN_GATE.unlock()
        }
    }

    private suspend fun runBatch(progress: RatingBackfillRunner.Progress): RatingBackfillBatch {
        val now = System.currentTimeMillis()
        val targets = backfillDao.selectTargets(now, now - RatingBackfillPolicy.CHECKED_RETRY_MS, BATCH)
        return RatingBackfillRunner(
            dao = backfillDao,
            backfill = { id -> workRepository.backfillRatingsReport(id) },
            itemTimeoutMs = ITEM_TIMEOUT_MS,
            budgetMs = TIME_BUDGET_MS,
        ).run(targets, progress)
    }

    /**
     * 主要依赖的平台正在限流冷却、而且冷却时间比单部作品的等待上限还长时，这次先不查（查也只会一部部超时），
     * 返回给用户看的原因；否则返回 null。
     */
    private fun cooldownReason(): String? {
        val bangumi = sourceQuota.backgroundCooldownMillis(NetworkSource.BANGUMI)
        val others = listOf(NetworkSource.ANILIST, NetworkSource.TENRAI)
            .associateWith { sourceQuota.backgroundCooldownMillis(it) }
        val blocking = when {
            bangumi > ITEM_TIMEOUT_MS -> mapOf(NetworkSource.BANGUMI to bangumi)
            others.values.all { it > ITEM_TIMEOUT_MS } -> others
            else -> return null
        }
        val minutes = (blocking.values.max() + MINUTE_MS - 1) / MINUTE_MS
        val names = blocking.keys.joinToString("、") { sourceDisplayName(it.name) }
        return "$names 正在限流冷却（约 $minutes 分钟），这次先不查，冷却后再试"
    }

    private suspend fun stoppedReason(progress: RatingBackfillRunner.Progress): String {
        val reason = describeStopReason(stopReason)
        val current = progress.currentId?.let { id ->
            val title = runCatchingCancellable { workDao.getById(id)?.canonicalTitle }.getOrNull() ?: id
            val seconds = (System.nanoTime() / NANOS_PER_MILLI - progress.currentStartedAtMs) / SECOND_MS
            "；停下时正在查《$title》，已等 $seconds 秒"
        }.orEmpty()
        return "被系统中止（$reason），已处理 ${progress.done} 部$current，剩下的下次继续"
    }

    /** 旧版本把「最近查过」存在设置里：第一次运行时搬进数据库表，然后清掉。 */
    private suspend fun migrateLegacyAttempts() {
        val legacy = settings.ratingBackfillAttempts.first()
        if (legacy.isEmpty()) return
        if (backfillDao.count() == 0) backfillDao.upsertAll(RatingBackfillPolicy.fromLegacy(legacy))
        settings.clearRatingBackfillAttempts()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "rating_backfill"
        const val UNIQUE_NOW_NAME = "rating_backfill_now"
        private const val BATCH = 20

        /** 一批最多跑 8 分钟，留出余量在系统 10 分钟的时限前正常收尾。 */
        private val TIME_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        /** 单部作品最多等 90 秒：某个平台限流冷却时，请求会在队列里一直等额度。 */
        private val ITEM_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(90)
        private const val MINUTE_MS = 60_000L
        private const val SECOND_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** 进程内同一时间只跑一个补评分任务（定时任务和立即补一批共用）。 */
        private val RUN_GATE = Mutex()
    }
}

/**
 * 一批补评分的结果：挑了多少部、查完多少部、多少部因网络 / 限流 / 其它错误没查成；
 * [timedOut] 是其中等过了单部上限的部数，[failedSources] 是各平台没拿到结果的部数（平台名 → 部数）。
 */
internal data class RatingBackfillBatch(
    val total: Int,
    val checked: Int,
    val failed: Int,
    val deferred: Int = 0,
    val timedOut: Int = 0,
    val failedSources: Map<String, Int> = emptyMap(),
) {
    fun summary(): String {
        val base = when {
            total == 0 -> "没有需要补的作品"
            failed == 0 && deferred == 0 -> "查了 $total 部"
            failed == 0 -> "查完 $checked 部"
            else -> "挑了 $total 部，查完 $checked 部，$failed 部没查成"
        }
        val details = buildList {
            if (failedSources.isNotEmpty()) {
                add(
                    "没拿到结果的平台：" + failedSources.entries.sortedByDescending { it.value }
                        .joinToString("、") { (source, n) -> "${sourceDisplayName(source)} $n 部" },
                )
            }
            if (timedOut > 0) add("$timedOut 部等额度超时")
            if (deferred > 0) add("到了单次运行时限，剩下 $deferred 部下次继续")
        }
        return (listOf(base) + details).joinToString("；")
    }

    fun failureReason(): String = if (checked == 0) {
        "这批 $total 部都没查成，多半是网络不通或被限速，会在几小时后自动再试"
    } else {
        "$failed 部没查成（网络或限速），会在几小时后自动再试"
    }
}

/** 按「自动补充其它平台评分」开关调度后台补评分：打开时马上补一批，之后联网且电量不低时约每 6 小时一次。 */
@Singleton
class RatingBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(RatingBackfillWorker.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(RatingBackfillWorker.UNIQUE_NOW_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<RatingBackfillWorker>(REPEAT_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            RatingBackfillWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        // 打开开关后马上补一批，不必等周期任务。
        workManager.enqueueUniqueWork(
            RatingBackfillWorker.UNIQUE_NOW_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RatingBackfillWorker>().setConstraints(constraints).build(),
        )
    }

    private companion object {
        const val REPEAT_HOURS = 6L
        const val BACKOFF_MINUTES = 15L
    }
}

/** 平台内部名 → 界面上的名字。 */
internal fun sourceDisplayName(name: String): String = when (name) {
    "BANGUMI" -> "Bangumi"
    "ANILIST" -> "AniList"
    "TENRAI" -> "Tenrai"
    "MAL" -> "MAL"
    "VNDB" -> "VNDB"
    "JIKAN" -> "Jikan"
    else -> name
}

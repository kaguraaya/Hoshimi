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
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingBackfillDao
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.repository.WorkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 后台补充其它平台评分：挑只有一个平台有评分（或还没有任何评分记录）的动画，逐部做和打开详情页时一样的评分刷新
 * （含按标题去 AniList、Tenrai 等平台匹配）。收藏和待补池里的作品优先，其余按评分人数。
 *
 * 一次最多 [BATCH] 部、一部一部地做；请求全部走后台优先级，前台页面（例如正在打开的详情页）的请求先走。
 * 每部作品的结果记在 `rating_backfill_state` 表里：查过了就一周后再看，因网络 / 限流没查成的几小时后就再试
 * （见 [RatingBackfillPolicy]）。候选在数据库里筛选，不整表读。
 */
@HiltWorker
@Suppress("LongParameterList") // Hilt 注入
class RatingBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val backfillDao: RatingBackfillDao,
    private val recorder: TaskRunRecorder,
) : CoroutineWorker(appContext, params) {
    @Suppress("TooGenericExceptionCaught") // 任何异常都只记为这次失败，下个周期再跑
    override suspend fun doWork(): Result {
        if (!settings.scoreDiffAutoBackfill.first()) return Result.success()
        recorder.started(BackgroundTaskKind.RATING_BACKFILL)
        return try {
            migrateLegacyAttempts()
            val batch = runBatch()
            if (batch.failed == 0) {
                recorder.succeeded(BackgroundTaskKind.RATING_BACKFILL, batch.summary())
            } else {
                // 有作品没查成就记为失败：全部没查成、部分没查成都要在「后台任务」页看得出来。
                recorder.failed(BackgroundTaskKind.RATING_BACKFILL, batch.failureReason(), batch.summary())
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recorder.failed(BackgroundTaskKind.RATING_BACKFILL, TaskRunRecorder.describe(error))
            Result.success()
        }
    }

    private suspend fun runBatch(): RatingBackfillBatch {
        val now = System.currentTimeMillis()
        val targets = backfillDao.selectTargets(now, now - RatingBackfillPolicy.CHECKED_RETRY_MS, BATCH)
        var checked = 0
        var transient = 0
        for (id in targets) {
            val outcome = try {
                workRepository.backfillRatingsOutcome(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RatingBackfillOutcome.FAILED
            }
            if (outcome == RatingBackfillOutcome.CHECKED) checked++ else transient++
            val at = System.currentTimeMillis()
            backfillDao.upsert(RatingBackfillPolicy.next(id, backfillDao.get(id), outcome, at))
        }
        return RatingBackfillBatch(total = targets.size, checked = checked, failed = transient)
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
    }
}

/** 一批补评分的结果：挑了多少部、查完多少部、多少部因网络 / 限流 / 其它错误没查成。 */
internal data class RatingBackfillBatch(val total: Int, val checked: Int, val failed: Int) {
    fun summary(): String = when {
        total == 0 -> "没有需要补的作品"
        failed == 0 -> "查了 $total 部"
        else -> "挑了 $total 部，查完 $checked 部，$failed 部没查成"
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

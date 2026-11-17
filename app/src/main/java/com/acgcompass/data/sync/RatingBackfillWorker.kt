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
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
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
 * 查过的作品 [RETRY_AFTER_MS] 内不重复查。
 */
@HiltWorker
@Suppress("LongParameterList") // Hilt 注入
class RatingBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val workDao: WorkDao,
    private val ratingDao: RatingDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!settings.scoreDiffAutoBackfill.first()) return Result.success()
        val now = System.currentTimeMillis()
        val attempts = settings.ratingBackfillAttempts.first().filterValues { now - it < RETRY_AFTER_MS }
        val tried = LinkedHashMap(attempts)
        for (id in targets(now, attempts.keys)) {
            try {
                workRepository.backfillRatings(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 单部失败不影响其它作品，下次到期再试。
            }
            tried[id] = System.currentTimeMillis()
        }
        settings.setRatingBackfillAttempts(
            tried.entries.sortedByDescending {
                it.value
            }.take(MAX_ATTEMPTS).associate { it.toPair() },
        )
        return Result.success()
    }

    private suspend fun targets(now: Long, recentlyTried: Set<String>): List<String> {
        val personal = buildSet {
            userCollectionDao.getAll().forEach { add(it.localWorkId) }
            backlogDao.getAll().forEach { add(it.workId) }
        }
        return selectBackfillTargets(workDao.getAll(), ratingDao.getAll(), personal, recentlyTried, now, BATCH)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "rating_backfill"
        const val UNIQUE_NOW_NAME = "rating_backfill_now"
        private const val BATCH = 20
        private const val MAX_ATTEMPTS = 3_000
        internal const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 挑出需要补评分的动画：有效评分（未缺失、分数大于 0）不超过一个平台，最近没查过，其它平台最近也没查过。
 * 收藏 / 待补池里的作品（[personal]）优先，其余按评分人数从多到少，最多 [limit] 部。
 */
@Suppress("LongParameterList") // 纯函数，输入逐个列出便于测试
internal fun selectBackfillTargets(
    works: List<WorkEntity>,
    ratings: List<RatingEntity>,
    personal: Set<String>,
    recentlyTried: Set<String>,
    now: Long,
    limit: Int,
): List<String> {
    val byWork = ratings.groupBy { it.workId }
    return works.asSequence()
        .filter { MediaType.fromStorage(it.mediaType) == MediaType.ANIME && it.id !in recentlyTried }
        .filter { work ->
            val rows = byWork[work.id].orEmpty()
            val valid = rows.count { !it.missing && it.score > 0f }
            val othersChecked = rows.any {
                it.sourceId != SourceId.BANGUMI.name && now - it.fetchedAt < RatingBackfillWorker.RETRY_AFTER_MS
            }
            valid <= 1 && !othersChecked
        }
        .sortedWith(
            compareByDescending<WorkEntity> { it.id in personal }
                .thenByDescending { work -> byWork[work.id].orEmpty().maxOfOrNull { it.voteCount } ?: 0 },
        )
        .take(limit)
        .map { it.id }
        .toList()
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

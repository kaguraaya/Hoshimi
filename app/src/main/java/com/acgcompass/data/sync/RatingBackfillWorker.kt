package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.model.MediaType
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
 * 后台补充其它平台评分：挑只有一个平台有评分、评分人数最多的动画，逐部去其它平台查评分。
 * 每次最多 [BATCH] 部、按顺序进行；各数据源自己的限流照常生效。查过的作品在评分缓存期内不会再查。
 */
@HiltWorker
class RatingBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val workDao: WorkDao,
    private val ratingDao: RatingDao,
) : CoroutineWorker(appContext, params) {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        if (!settings.scoreDiffAutoBackfill.first()) return Result.success()
        return try {
            targets().forEach { id -> runCatching { workRepository.aggregateRatings(id, forceRefresh = false) } }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun targets(): List<String> {
        val now = System.currentTimeMillis()
        val animeIds = workDao.getAll()
            .filter { MediaType.fromStorage(it.mediaType) == MediaType.ANIME }
            .map { it.id }
            .toSet()
        return ratingDao.getAll()
            .filter { it.workId in animeIds }
            .groupBy { it.workId }
            .filterValues { rows ->
                val valid = rows.filter { !it.missing && it.score > 0f }
                // 只有一个平台有分，且最近一次查询已过缓存期（否则 aggregateRatings 也不会真去查）。
                valid.size == 1 && rows.all { now - it.fetchedAt >= DetailCacheTtl.COMMENTS_MILLIS }
            }
            .entries
            .sortedByDescending { (_, rows) -> rows.maxOf { it.voteCount } }
            .take(BATCH)
            .map { it.key }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "rating_backfill"
        private const val BATCH = 20
    }
}

/** 按「自动补充其它平台评分」开关调度后台补评分：联网且电量不低时，约每 6 小时一次。 */
@Singleton
class RatingBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(RatingBackfillWorker.UNIQUE_WORK_NAME)
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
    }

    private companion object {
        const val REPEAT_HOURS = 6L
        const val BACKOFF_MINUTES = 15L
    }
}

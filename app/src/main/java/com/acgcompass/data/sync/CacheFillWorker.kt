package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.domain.repository.CacheRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/** Replenishes the public metadata cache when the user explicitly enables background filling. */
@HiltWorker
class CacheFillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val cacheRepository: CacheRepository,
    private val fillStateDao: VndbRankingCacheDao,
) : CoroutineWorker(appContext, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        if (!settings.cacheBackgroundFillEnabled.first()) return Result.success()
        updateStatus("RUNNING")
        return try {
            cacheRepository.trimToPolicy()
            when (val fill = workRepository.loadPublicDiscovery(background = true)) {
                is AppResult.Success -> {
                    cacheRepository.trimToPolicy()
                    updateStatus("SUCCESS")
                    Result.success()
                }
                is AppResult.Failure -> {
                    updateStatus(
                        result = "FAILED",
                        failure = listOf(fill.error.cause, fill.error.nextStep)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                    )
                    Result.retry()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateStatus("FAILED", error.message ?: error.javaClass.simpleName)
            Result.retry()
        }
    }

    private suspend fun updateStatus(result: String, failure: String? = null) {
        val now = System.currentTimeMillis()
        fillStateDao.upsertFillState(
            CacheFillStateEntity(
                source = WORKER_SOURCE,
                nextPage = runAttemptCount,
                exhausted = false,
                lastRunAt = now,
                lastResult = result,
                failureReason = failure,
                updatedAt = now,
            ),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "public_cache_background_fill"
        const val WORKER_SOURCE = "WORKER"
    }
}

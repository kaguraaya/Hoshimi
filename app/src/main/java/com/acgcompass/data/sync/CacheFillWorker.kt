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
@Suppress("LongParameterList") // Hilt 注入
class CacheFillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val workRepository: WorkRepository,
    private val cacheRepository: CacheRepository,
    private val fillStateDao: VndbRankingCacheDao,
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
) : CoroutineWorker(appContext, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        if (!settings.cacheBackgroundFillEnabled.first()) return Result.success()
        updateStatus("RUNNING")
        taskRecorder.started(com.acgcompass.domain.model.BackgroundTaskKind.CACHE_FILL)
        return try {
            cacheRepository.trimToPolicy()
            when (val fill = workRepository.loadPublicDiscovery(background = true)) {
                is AppResult.Success -> {
                    cacheRepository.trimToPolicy()
                    updateStatus("SUCCESS")
                    taskRecorder.succeeded(com.acgcompass.domain.model.BackgroundTaskKind.CACHE_FILL)
                    Result.success()
                }
                is AppResult.Failure -> {
                    updateStatus(
                        result = "FAILED",
                        failure = listOf(fill.error.cause, fill.error.nextStep)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                    )
                    taskRecorder.failed(
                        com.acgcompass.domain.model.BackgroundTaskKind.CACHE_FILL,
                        com.acgcompass.data.tasks.TaskRunRecorder.describe(fill.error),
                    )
                    Result.retry()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateStatus("FAILED", error.message ?: error.javaClass.simpleName)
            taskRecorder.failed(
                com.acgcompass.domain.model.BackgroundTaskKind.CACHE_FILL,
                com.acgcompass.data.tasks.TaskRunRecorder.describe(error),
            )
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

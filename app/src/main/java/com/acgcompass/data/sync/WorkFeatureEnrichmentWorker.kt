package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.domain.taste.WorkFeatureRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class WorkFeatureEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val workFeatureDao: WorkFeatureDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val scheduler: WorkFeatureEnrichmentScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val priorityIds = buildSet {
            userCollectionDao.getAll().forEach { add(it.sourceItemId.ifBlank { it.localWorkId }) }
            backlogDao.getAll().forEach { add(it.workId) }
        }.filter { it.toIntOrNull() != null }
        val priorityBatch = if (priorityIds.isEmpty()) {
            emptyList()
        } else {
            workFeatureDao.getIncompleteIds(priorityIds, BATCH_SIZE)
        }
        val ids = priorityBatch.ifEmpty { workFeatureDao.getIncompleteIds(BATCH_SIZE) }
        if (ids.isEmpty()) return Result.success()
        workFeatureRepository.getFeatures(ids, networkBudget = ids.size)
        if (workFeatureDao.getIncompleteIds(1).isNotEmpty()) scheduler.enqueue()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_WORK_NAME = "work_feature_enrichment"
        private const val BATCH_SIZE = 20
    }
}

package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.domain.model.MediaType
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
    /**
     * 先把个人收藏 / 待补池里资料不全的作品补成完整资料（含 Staff、角色），再给本地作品池里还没有资料的作品
     * 补基础资料（标签、评分、封面）。每次一小批；这一批有进展且还有剩余时再排下一批。
     */
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
        val incomplete = priorityBatch.ifEmpty { workFeatureDao.getIncompleteIds(BATCH_SIZE) }
        val progressed = if (incomplete.isNotEmpty()) {
            workFeatureRepository.getFeatures(incomplete, networkBudget = incomplete.size)
            workFeatureDao.getIncompleteIds(incomplete, incomplete.size).size < incomplete.size
        } else {
            val missing = workFeatureDao.getPoolIdsWithoutFeature(MediaType.ANIME.name, POOL_BATCH_SIZE)
            if (missing.isEmpty()) return@runCatching Result.success()
            workFeatureRepository.ensureBasic(missing, networkBudget = missing.size, background = true).isNotEmpty()
        }
        val remaining = workFeatureDao.getIncompleteIds(1).isNotEmpty() ||
            workFeatureDao.getPoolIdsWithoutFeature(MediaType.ANIME.name, 1).isNotEmpty()
        if (progressed && remaining) scheduler.enqueue()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_WORK_NAME = "work_feature_enrichment"
        private const val BATCH_SIZE = 20
        private const val POOL_BATCH_SIZE = 40
    }
}

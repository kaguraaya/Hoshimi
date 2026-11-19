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
     * 每次一小批，顺序是：
     * 1. 个人收藏 / 待补池里资料不全的作品补成完整资料（含 Staff、角色）；
     * 2. 本地作品池里还没有资料的作品补基础资料（标签和标注人数、评分、封面，一次请求一部）——这些作品补完才能参与口味排行；
     * 3. 其余只有基础资料的作品再补成完整资料。
     * 第 3 步的作品永远不少，所以必须放在第 2 步之后，否则没有资料的作品一直轮不到。
     * 这一批有进展且还有剩余时再排下一批。
     */
    override suspend fun doWork(): Result = runCatching {
        val priorityIds = buildSet {
            userCollectionDao.getAll().forEach { add(it.sourceItemId.ifBlank { it.localWorkId }) }
            backlogDao.getAll().forEach { add(it.workId) }
        }.filter { it.toIntOrNull() != null }
        val personal = if (priorityIds.isEmpty()) {
            emptyList()
        } else {
            workFeatureDao.getIncompleteIds(priorityIds, BATCH_SIZE)
        }
        val missing = if (personal.isEmpty()) {
            workFeatureDao.getPoolIdsWithoutFeature(MediaType.ANIME.name, POOL_BATCH_SIZE)
        } else {
            emptyList()
        }
        val progressed = when {
            personal.isNotEmpty() -> upgrade(personal)
            missing.isNotEmpty() ->
                workFeatureRepository.ensureBasic(missing, networkBudget = missing.size, background = true).isNotEmpty()
            else -> workFeatureDao.getIncompleteIds(BATCH_SIZE).takeIf { it.isNotEmpty() }?.let { upgrade(it) } ?: false
        }
        val remaining = workFeatureDao.getIncompleteIds(1).isNotEmpty() ||
            workFeatureDao.getPoolIdsWithoutFeature(MediaType.ANIME.name, 1).isNotEmpty()
        if (progressed && remaining) scheduler.enqueue()
        Result.success()
    }.getOrElse { Result.retry() }

    /** 把这批补成完整资料，返回是否至少补完一部。 */
    private suspend fun upgrade(ids: List<String>): Boolean {
        workFeatureRepository.getFeatures(ids, networkBudget = ids.size)
        return workFeatureDao.getIncompleteIds(ids, ids.size).size < ids.size
    }

    companion object {
        const val UNIQUE_WORK_NAME = "work_feature_enrichment"
        private const val BATCH_SIZE = 20
        private const val POOL_BATCH_SIZE = 40
    }
}

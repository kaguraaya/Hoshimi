package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.WorkFeatureRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
@Suppress("LongParameterList") // Hilt 注入
class WorkFeatureEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val workFeatureDao: WorkFeatureDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val scheduler: WorkFeatureEnrichmentScheduler,
    private val taskRecorder: TaskRunRecorder,
) : CoroutineWorker(appContext, params) {
    /**
     * 每次一小批，顺序是：
     * 1. 个人收藏 / 待补池里资料不全的作品补成完整资料（含 Staff、角色）；
     * 2. 本地作品池里还没有资料的作品补基础资料（标签和标注人数、评分、封面，一次请求一部）——这些作品补完才能参与口味排行；
     * 3. 其余只有基础资料的作品再补成完整资料。
     * 第 3 步的作品永远不少，所以必须放在第 2 步之后，否则没有资料的作品一直轮不到。
     * 这一批有进展且还有剩余时再排下一批。
     */
    override suspend fun doWork(): Result = runCatchingCancellable {
        taskRecorder.started(BackgroundTaskKind.FEATURE_ENRICHMENT)
        val batch = enrichBatch()
        if (batch.stalled) {
            // 有要补的作品却一部都没补上：多半是网络或限流，记为失败并交给 WorkManager 退避重试几次。
            taskRecorder.failed(
                BackgroundTaskKind.FEATURE_ENRICHMENT,
                "这批 ${batch.attempted} 部一部都没补上，多半是网络不通或被限速，稍后会自动再试",
            )
            if (runAttemptCount < MAX_STALLED_RETRIES) Result.retry() else Result.success()
        } else {
            taskRecorder.succeeded(BackgroundTaskKind.FEATURE_ENRICHMENT, batch.summary())
            Result.success()
        }
    }.getOrElse { error ->
        if (error is kotlin.coroutines.cancellation.CancellationException) throw error
        taskRecorder.failed(BackgroundTaskKind.FEATURE_ENRICHMENT, TaskRunRecorder.describe(error))
        Result.retry()
    }

    private suspend fun enrichBatch(): EnrichmentBatch {
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
        val rest = if (personal.isEmpty() && missing.isEmpty()) {
            workFeatureDao.getIncompleteIds(
                BATCH_SIZE,
            )
        } else {
            emptyList()
        }
        val progressed = when {
            personal.isNotEmpty() -> upgrade(personal)
            missing.isNotEmpty() ->
                workFeatureRepository.ensureBasic(missing, networkBudget = missing.size, background = true).isNotEmpty()
            rest.isNotEmpty() -> upgrade(rest)
            else -> false
        }
        val remaining = workFeatureDao.getIncompleteIds(1).isNotEmpty() ||
            workFeatureDao.getPoolIdsWithoutFeature(MediaType.ANIME.name, 1).isNotEmpty()
        if (progressed && remaining) scheduler.enqueue()
        return EnrichmentBatch(attempted = personal.size + missing.size + rest.size, progressed, remaining)
    }

    /** 把这批补成完整资料，返回是否至少补完一部。 */
    private suspend fun upgrade(ids: List<String>): Boolean {
        workFeatureRepository.getFeatures(ids, networkBudget = ids.size)
        return workFeatureDao.getIncompleteIds(ids, ids.size).size < ids.size
    }

    companion object {
        const val UNIQUE_WORK_NAME = "work_feature_enrichment"

        /** 一部都没补上时最多让 WorkManager 退避重试几次。 */
        private const val MAX_STALLED_RETRIES = 3
        private const val BATCH_SIZE = 20
        private const val POOL_BATCH_SIZE = 40
    }
}

/** 一批补资料的结果：尝试了多少部、有没有进展、还有没有剩余。 */
internal data class EnrichmentBatch(val attempted: Int, val progressed: Boolean, val remaining: Boolean) {
    /** 有要补的作品却一部都没补上。 */
    val stalled: Boolean get() = attempted > 0 && !progressed

    fun summary(): String = when {
        attempted == 0 -> "没有要补的作品"
        remaining -> "补了一批，还有作品没补完"
        else -> "都补完了"
    }
}

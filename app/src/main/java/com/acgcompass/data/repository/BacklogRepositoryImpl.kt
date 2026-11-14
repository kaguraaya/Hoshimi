package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AddResult
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DrawCriteria
import com.acgcompass.domain.repository.DrawResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [BacklogRepository] 实现。
 *
 * **单一可信源 = Room**：所有读取都从 [BacklogDao] / [WorkDao] 的
 * Flow 出发，离线可用、状态一致。
 *
 * 关键不变式：
 * - [addAll] 按 `workId` 去重并幂等：重复作品不新增条目；每次导入命中都使
 *   `recommendedCount` 自增。
 * - [observeBacklog] 在 [BacklogFilter] / [BacklogSort] 变化时重新发射；媒介类型过滤需联结
 *   [WorkDao]（待补条目本身不携带媒介类型）。
 * - 所有写操作（[bulk] / [setPriority] / [setNote]）包裹在 [AppResult] / runCatching 中，
 *   异常兜底为领域错误，绝不崩溃。
 */
@Singleton
class BacklogRepositoryImpl @Inject constructor(
    private val backlogDao: BacklogDao,
    private val workDao: WorkDao,
    private val dispatchers: DispatcherProvider,
    private val wishSync: com.acgcompass.data.sync.WishSyncQueue,
) : BacklogRepository {

    // --- observeBacklog ----------------------------------------------------

    override fun observeBacklog(filter: BacklogFilter, sort: BacklogSort): Flow<List<BacklogItem>> =
        // 联结 works：媒介类型过滤需要作品信息（待补条目本身不携带 mediaType）。
        combine(backlogDao.observeAll(), workDao.observeAll()) { backlog, works ->
            val mediaTypeById: Map<String, MediaType?> =
                works.associate { it.id to MediaType.fromStorage(it.mediaType) }
            backlog
                .map { it.toDomain() }
                .filter { item -> matchesFilter(item, filter, mediaTypeById[item.workId]) }
                .sortedWith(sortComparator(sort))
        }.flowOn(dispatchers.io)

    private fun matchesFilter(item: BacklogItem, filter: BacklogFilter, mediaType: MediaType?): Boolean {
        if (filter.priorities.isNotEmpty() && item.priority !in filter.priorities) return false
        if (filter.mediaTypes.isNotEmpty() && (mediaType == null || mediaType !in filter.mediaTypes)) {
            return false
        }
        // moodTags / riskTags：要求包含全部指定标签（AND 语义）。
        if (!item.moodTags.containsAll(filter.moodTags)) return false
        if (!item.riskTags.containsAll(filter.riskTags)) return false
        filter.inDustMuseum?.let { if (item.inDustMuseum != it) return false }
        return true
    }

    private fun sortComparator(sort: BacklogSort): Comparator<BacklogItem> = when (sort) {
        BacklogSort.ADDED_DESC -> compareByDescending { it.addedAt }
        BacklogSort.ADDED_ASC -> compareBy { it.addedAt }
        // 高 → 中 → 低；同级按加入时间降序保证稳定可读顺序。
        BacklogSort.PRIORITY_DESC ->
            compareBy<BacklogItem> { priorityRank(it.priority) }
                .thenByDescending { it.addedAt }
        BacklogSort.DUST_DAYS_DESC -> compareByDescending { it.dustDays }
    }

    /** HIGH=0 < MEDIUM=1 < LOW=2，配合升序比较实现「高→低」。 */
    private fun priorityRank(p: Priority): Int = when (p) {
        Priority.HIGH -> 0
        Priority.MEDIUM -> 1
        Priority.LOW -> 2
    }

    // --- addAll（去重 + 被安利计数） ---------------------------------------

    override suspend fun addAll(items: List<Work>): AddResult = withContext(dispatchers.io) {
        val added = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val processed = mutableSetOf<String>()
        val now = System.currentTimeMillis()

        for (work in items) {
            val workId = work.id
            if (workId in processed) {
                // 同一次调用内的重复命中：池规模不变，记为去重项并计入被安利次数。
                duplicates += workId
                incrementRecommendation(workId, now)
                continue
            }
            processed += workId

            val alreadyInPool = backlogDao.getByWork(workId) != null
            if (alreadyInPool) {
                duplicates += workId
            } else {
                // 写入作品（保留既有 createdAt），再新建待补条目。
                val createdAt = workDao.getById(workId)?.createdAt ?: now
                workDao.upsert(work.toEntity(createdAt = createdAt, updatedAt = now))
                backlogDao.upsert(newBacklogEntity(workId, now))
                added += workId
            }
            // 每次导入命中都自增被安利次数。
            incrementRecommendation(workId, now)
        }

        // 新加入的作品排队同步成 Bangumi「想看」（后台推送，失败会重试）。
        runCatching { wishSync.enqueue(added) }
        AddResult(addedWorkIds = added, duplicateWorkIds = duplicates)
    }

    private fun newBacklogEntity(workId: String, now: Long): BacklogItemEntity = BacklogItemEntity(
        workId = workId,
        priority = Priority.MEDIUM.name,
        moodTags = emptyList(),
        riskTags = emptyList(),
        note = null,
        addedAt = now,
        dustDays = 0,
        inDustMuseum = false,
    )

    private suspend fun incrementRecommendation(workId: String, now: Long) {
        val current = workDao.observeRecommendationCount(workId).first()
        workDao.upsertRecommendationCount(
            RecommendationCountEntity(
                workId = workId,
                recommendedCount = (current?.recommendedCount ?: 0) + 1,
                lastRecommendedAt = now,
            ),
        )
    }

    // --- setPriority / setNote ---------------------------------------------

    override suspend fun setPriority(id: String, p: Priority) {
        withContext(dispatchers.io) {
            val existing = backlogDao.getByWork(id) ?: return@withContext
            backlogDao.update(existing.copy(priority = p.name))
        }
    }

    override suspend fun setNote(id: String, note: String?) {
        withContext(dispatchers.io) {
            val existing = backlogDao.getByWork(id) ?: return@withContext
            backlogDao.update(existing.copy(note = note?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }

    // --- bulk --------------------------------------------------------------

    override suspend fun bulk(op: BulkOp, ids: List<String>): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            for (id in ids) {
                when (op) {
                    BulkOp.DELETE -> backlogDao.deleteByWork(id)
                    BulkOp.ARCHIVE_TO_DUST_MUSEUM -> updateEntity(id) {
                        it.copy(inDustMuseum = true)
                    }
                    BulkOp.RESTORE_FROM_DUST_MUSEUM -> updateEntity(id) {
                        it.copy(inDustMuseum = false)
                    }
                    BulkOp.SET_PRIORITY_HIGH -> updateEntity(id) {
                        it.copy(priority = Priority.HIGH.name)
                    }
                    BulkOp.SET_PRIORITY_MEDIUM -> updateEntity(id) {
                        it.copy(priority = Priority.MEDIUM.name)
                    }
                    BulkOp.SET_PRIORITY_LOW -> updateEntity(id) {
                        it.copy(priority = Priority.LOW.name)
                    }
                }
            }
        }
    }

    private suspend inline fun updateEntity(id: String, transform: (BacklogItemEntity) -> BacklogItemEntity) {
        val existing = backlogDao.getByWork(id) ?: return
        backlogDao.update(transform(existing))
    }

    // --- 吃灰馆归档与状态还原 --------------------------------------------

    override suspend fun archiveToDust(workId: String, prevStatus: String?) {
        withContext(dispatchers.io) {
            val existing = backlogDao.getByWork(workId) ?: return@withContext
            backlogDao.update(existing.copy(inDustMuseum = true, prevStatus = prevStatus))
        }
    }

    override suspend fun restoreFromDust(workId: String): String? = withContext(dispatchers.io) {
        val existing = backlogDao.getByWork(workId) ?: return@withContext null
        val prev = existing.prevStatus
        backlogDao.update(existing.copy(inDustMuseum = false, prevStatus = null))
        prev
    }

    // --- draw（一键抽番，带理由） ------------------------------------------

    override suspend fun draw(criteria: DrawCriteria): DrawResult = withContext(dispatchers.io) {
        val items = backlogDao.observeAll().first().map { it.toDomain() }
        val worksById = workDao.observeAll().first().associateBy { it.id }

        val candidates = items.filter { item ->
            satisfiesDraw(item, worksById[item.workId], criteria)
        }
        if (candidates.isEmpty()) {
            return@withContext DrawResult(
                pick = null,
                reason = "当前没有满足条件的待补作品，请调整条件或添加作品。",
            )
        }

        // 多维度加权抽取并加入随机扰动，避免结果长期固定。
        // 评分 = 优先级权重(高>中>低) + 吃灰天数归一 + 随机扰动；在候选中取加权最高者。
        val maxDust = candidates.maxOf { it.dustDays }.coerceAtLeast(1)
        val pick = candidates.maxByOrNull { item ->
            val priorityScore = when (item.priority) {
                Priority.HIGH -> 1.0
                Priority.MEDIUM -> 0.6
                Priority.LOW -> 0.3
            }
            val dustScore = item.dustDays.toDouble() / maxDust
            // 随机扰动占比足够大，使重复抽番结果有变化（多样性），但仍偏向高优先级/久吃灰。
            priorityScore * 1.2 + dustScore * 0.8 + kotlin.random.Random.nextDouble() * 1.0
        } ?: candidates.random()
        DrawResult(pick = pick, reason = buildDrawReason(pick, candidates.size))
    }

    private fun satisfiesDraw(item: BacklogItem, work: WorkEntity?, criteria: DrawCriteria): Boolean {
        if (item.workId in criteria.excludeWorkIds) return false
        // 吃灰博物馆条目不参与日常抽番。
        if (item.inDustMuseum) return false
        // 心情：命中其一即可（空集合不限）。
        if (criteria.moodTags.isNotEmpty() && item.moodTags.none { it in criteria.moodTags }) {
            return false
        }
        // 风险容忍：超出容忍集合的高风险作品被过滤。
        if (item.riskTags.any { it !in criteria.riskTolerance }) return false

        val completionCost = work?.completionCostBucket?.let { CompletionCost.fromStorage(it) }
        val status = ReleaseStatus.fromStorage(work?.status)

        // 期末保护：排除长期坑 / 未完结。
        if (criteria.finalsProtection) {
            if (completionCost == CompletionCost.LONG_HAUL) return false
            if (status != ReleaseStatus.FINISHED) return false
        }
        // 可用时间：粗粒度按补完成本分桶过滤。
        criteria.availableMinutes?.let { minutes ->
            if (completionCost != null && estimatedMinutes(completionCost) > minutes) return false
        }
        return true
    }

    /** 补完成本分桶到粗粒度时长估计（分钟），用于可用时间硬过滤。 */
    private fun estimatedMinutes(cost: CompletionCost): Int = when (cost) {
        CompletionCost.TONIGHT -> 120
        CompletionCost.WEEKEND -> 600
        CompletionCost.LONG_HAUL -> Int.MAX_VALUE
    }

    private fun buildDrawReason(pick: BacklogItem, candidateCount: Int): String {
        val priorityLabel = when (pick.priority) {
            Priority.HIGH -> "高优先级"
            Priority.MEDIUM -> "中优先级"
            Priority.LOW -> "低优先级"
        }
        val dust = if (pick.dustDays > 0) "，已等待 ${pick.dustDays} 天" else ""
        return "从 $candidateCount 个满足条件的待补作品中抽取（$priorityLabel$dust）。"
    }
}

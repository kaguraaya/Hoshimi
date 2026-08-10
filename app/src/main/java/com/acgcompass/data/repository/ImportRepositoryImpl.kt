package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.getOrNull
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.matching.MatchDecision
import com.acgcompass.domain.matching.decideMatch
import com.acgcompass.domain.model.ImportBatch
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportItemStatus
import com.acgcompass.domain.model.ImportSource
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AddResult
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.ImportRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.ParsedCandidate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [ImportRepository] 实现。
 *
 * **单一可信源 = Room**：匹配到的作品在批次生成 / 确认时写入 [WorkDao]，待补池加入复用
 * [BacklogRepository.addAll]（去重 + 被安利计数）。
 *
 * 关键不变式：
 * - [createBatch] 逐条匹配：高置信（[MatchDecision.AutoMerge]）→ 已匹配；低置信
 *   （[MatchDecision.NeedsConfirmation] 且有候选）→ 待确认；无候选 → 失败。
 * - 批次统计：识别数 = 候选条数；成功数 = 已匹配 / 已加入；失败数 = 未匹配。
 * - [addBatchToBacklog] 仅加入已匹配条目；加入后标记为已加入，重复调用幂等。
 * - 被安利次数由 [BacklogRepository.addAll] 在命中同一作品时自增。
 * - 所有写操作包裹在 [AppResult] / runCatching 中，异常兜底为领域错误，绝不崩溃。
 */
@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val importDao: ImportDao,
    private val workDao: WorkDao,
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    private val dispatchers: DispatcherProvider,
) : ImportRepository {

    override fun observeBatches(): Flow<List<ImportBatch>> = importDao.observeBatches()
        .map { batches -> batches.map { it.toDomain() } }
        .flowOn(dispatchers.io)

    override fun observeItems(batchId: String): Flow<List<ImportItem>> = importDao.observeItems(batchId)
        .map { items -> items.map { it.toDomain() } }
        .flowOn(dispatchers.io)

    // --- createBatch（批次生成 + 逐条匹配） --------------------------------

    override suspend fun createBatch(
        name: String,
        source: ImportSource,
        candidates: List<ParsedCandidate>,
    ): AppResult<ImportBatch> = withContext(dispatchers.io) {
        runCatchingApp {
            val now = System.currentTimeMillis()
            val batchId = newId()

            val items = candidates.map { candidate ->
                resolveCandidate(batchId, candidate, now)
            }
            importDao.upsertItems(items)

            val batch = ImportBatchEntity(
                id = batchId,
                name = name,
                createdAt = now,
                source = source.name,
                recognizedCount = candidates.size,
                successCount = items.count { it.status == ImportItemStatus.MATCHED.name },
                failureCount = items.count { it.status == ImportItemStatus.UNMATCHED.name },
            )
            importDao.upsertBatch(batch)
            batch.toDomain()
        }
    }

    /** 对单个候选做多源匹配并构造其导入明细；高置信作品同步写入本地单一可信源。 */
    private suspend fun resolveCandidate(batchId: String, candidate: ParsedCandidate, now: Long): ImportItemEntity {
        // 搜索失败（网络 / 源不可用）按「无候选」处理，绝不让整批崩溃。
        // 补番 App 默认优先动画——同名多类型时，在同置信度内按 动画 > 游戏/VN > 漫画/小说 排序，
        // 避免静默把番剧匹配成漫画版本。decideMatch 内部按置信度稳定排序，故此处的同分类型次序得以保留。
        val matches = workRepository.search(candidate.title).getOrNull().orEmpty()
            .let { com.acgcompass.domain.matching.sortMatchesByTypePriority(it) }
        return when (val decision = decideMatch(matches)) {
            is MatchDecision.AutoMerge -> {
                persistWork(decision.best.work, now)
                importItem(
                    batchId = batchId,
                    candidate = candidate,
                    workId = decision.best.work.id,
                    confidence = decision.best.matchConfidence,
                    status = ImportItemStatus.MATCHED,
                )
            }

            is MatchDecision.NeedsConfirmation -> {
                val best = decision.candidates.firstOrNull()
                if (best != null) {
                    // 低置信：保留候选置信度，等待用户确认后再加入。
                    importItem(
                        batchId = batchId,
                        candidate = candidate,
                        workId = null,
                        confidence = best.matchConfidence,
                        status = ImportItemStatus.NEEDS_CONFIRMATION,
                    )
                } else {
                    // 无任何候选：记为失败，不臆造匹配（不伪造）。
                    importItem(
                        batchId = batchId,
                        candidate = candidate,
                        workId = null,
                        confidence = 0f,
                        status = ImportItemStatus.UNMATCHED,
                    )
                }
            }
        }
    }

    private fun importItem(
        batchId: String,
        candidate: ParsedCandidate,
        workId: String?,
        confidence: Float,
        status: ImportItemStatus,
    ): ImportItemEntity = ImportItemEntity(
        id = newId(),
        batchId = batchId,
        rawText = candidate.rawSpan.raw,
        parsedTitle = candidate.title,
        workId = workId,
        matchConfidence = confidence,
        status = status.name,
    )

    // --- confirmItem（低置信确认） -----------------------------------------

    override suspend fun confirmItem(itemId: String, chosen: Work): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            val item = importDao.getItem(itemId)
            if (item != null) {
                val now = System.currentTimeMillis()
                persistWork(chosen, now)
                importDao.updateItem(
                    item.copy(
                        workId = chosen.id,
                        // 用户手动确认即视为完全匹配。
                        matchConfidence = 1f,
                        status = ImportItemStatus.MATCHED.name,
                    ),
                )
                recomputeBatchCounts(item.batchId)
            }
        }
    }

    // --- addBatchToBacklog（一键加入 + 去重） ------------------------------

    override suspend fun addBatchToBacklog(batchId: String): AppResult<AddResult> = withContext(dispatchers.io) {
        runCatchingApp {
            val items = importDao.getItems(batchId)
            // 仅加入已匹配（含已确认）且尚未加入的条目；按 workId 去重。
            val pending = items.filter {
                it.status == ImportItemStatus.MATCHED.name && it.workId != null
            }
            // 不去重：每条匹配命中都计入被安利次数；
            // 待补池去重由 [BacklogRepository.addAll] 按 workId 负责。
            val works: List<Work> = pending
                .mapNotNull { it.workId }
                .mapNotNull { workId -> workDao.getById(workId)?.toDomain() }

            // 复用待补池仓库：去重 + 被安利计数自增。
            val result = backlogRepository.addAll(works)

            // 标记为已加入，保证重复调用幂等（不重复计数 / 不重复加入）。
            pending.forEach { entity ->
                importDao.updateItem(entity.copy(status = ImportItemStatus.ADDED.name))
            }
            if (pending.isNotEmpty()) recomputeBatchCounts(batchId)
            result
        }
    }

    // --- helpers -----------------------------------------------------------

    /** 写入 / 更新本地作品（保留既有 createdAt），使其在确认 / 加入时可从单一可信源取回。 */
    private suspend fun persistWork(work: Work, now: Long) {
        val createdAt = workDao.getById(work.id)?.createdAt ?: now
        workDao.upsert(work.toEntity(createdAt = createdAt, updatedAt = now))
    }

    /** 依据当前明细状态刷新批次的成功 / 失败计数（确认 / 加入后保持统计一致）。 */
    private suspend fun recomputeBatchCounts(batchId: String) {
        val batch = importDao.getBatch(batchId) ?: return
        val items = importDao.getItems(batchId)
        val success = items.count {
            it.status == ImportItemStatus.MATCHED.name || it.status == ImportItemStatus.ADDED.name
        }
        val failure = items.count { it.status == ImportItemStatus.UNMATCHED.name }
        importDao.updateBatch(batch.copy(successCount = success, failureCount = failure))
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

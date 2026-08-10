package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity
import com.acgcompass.domain.model.ImportItemStatus
import com.acgcompass.domain.model.ImportSource
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.ParsedCandidate
import com.acgcompass.domain.usecase.TextSpan
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * 批量导入仓库单元测试。使用内存 Fake，无 Room / Android 依赖。
 *
 * 覆盖：批次生成与识别/成功/失败计数、低置信标记需确认、确认后回填、一键加入去重与被安利计数。
 */
class ImportRepositoryImplTest : StringSpec({

    fun work(id: String): Work = Work(
        id = id,
        titles = Titles(canonical = "作品 $id"),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
    )

    fun candidate(title: String): ParsedCandidate =
        ParsedCandidate(title = title, note = null, rawSpan = TextSpan(0, title.length, title))

    fun match(workId: String, confidence: Float): WorkMatch =
        WorkMatch(work = work(workId), matchConfidence = confidence, sourceTag = SourceId.BANGUMI)

    fun newRepo(matches: Map<String, List<WorkMatch>>): Triple<ImportRepositoryImpl, ImportDao, WorkDao> {
        val importDao = FakeImportDao()
        val workDao = FakeImportWorkDao()
        val backlogDao = FakeImportBacklogDao()
        val dispatchers = TestDispatchers()
        val backlogRepo = BacklogRepositoryImpl(backlogDao, workDao, dispatchers)
        val workRepo = FakeWorkRepository(matches)
        val repo = ImportRepositoryImpl(importDao, workDao, workRepo, backlogRepo, dispatchers)
        return Triple(repo, importDao, workDao)
    }

    "createBatch records recognized/success/failure counts and per-item status " {
        runTest {
            val (repo, importDao, _) = newRepo(
                matches = mapOf(
                    // 自动匹配
                    "高匹配" to listOf(match("a", 0.95f)),
                    // 需确认
                    "低匹配" to listOf(match("b", 0.50f)),
                    // 失败
                    "无匹配" to emptyList(),
                ),
            )

            val batch = (
                repo.createBatch(
                    name = "群友安利清单",
                    source = ImportSource.PASTE,
                    candidates = listOf(candidate("高匹配"), candidate("低匹配"), candidate("无匹配")),
                ) as AppResult.Success
                ).data

            batch.recognizedCount shouldBe 3
            batch.successCount shouldBe 1
            batch.failureCount shouldBe 1
            batch.source shouldBe ImportSource.PASTE

            val statuses = repo.observeItems(batch.id).first().associate { it.parsedTitle to it.status }
            statuses["高匹配"] shouldBe ImportItemStatus.MATCHED
            statuses["低匹配"] shouldBe ImportItemStatus.NEEDS_CONFIRMATION
            statuses["无匹配"] shouldBe ImportItemStatus.UNMATCHED
        }
    }

    "addBatchToBacklog adds matched works, dedupes, and is idempotent " {
        runTest {
            val (repo, _, workDao) = newRepo(
                matches = mapOf(
                    "甲" to listOf(match("a", 0.95f)),
                    // 同一作品命中两次
                    "甲重复" to listOf(match("a", 0.95f)),
                    "乙" to listOf(match("b", 0.95f)),
                ),
            )
            val batch = (
                repo.createBatch(
                    name = "批次",
                    source = ImportSource.CLIPBOARD,
                    candidates = listOf(candidate("甲"), candidate("甲重复"), candidate("乙")),
                ) as AppResult.Success
                ).data

            val first = (repo.addBatchToBacklog(batch.id) as AppResult.Success).data
            first.addedWorkIds.toSet() shouldBe setOf("a", "b")

            // 再次调用：已加入条目幂等，不再新增。
            val second = (repo.addBatchToBacklog(batch.id) as AppResult.Success).data
            second.addedWorkIds shouldBe emptyList()

            // 被安利次数：作品 a 在导入中命中两次，因此 recommendedCount == 2。
            (workDao as FakeImportWorkDao).recommendationCounts["a"]?.recommendedCount shouldBe 2
            workDao.recommendationCounts["b"]?.recommendedCount shouldBe 1
        }
    }

    "confirmItem promotes a low-confidence item and refreshes batch counts " {
        runTest {
            val (repo, _, _) = newRepo(
                matches = mapOf("低匹配" to listOf(match("c", 0.40f))),
            )
            val batch = (
                repo.createBatch(
                    name = "批次",
                    source = ImportSource.PASTE,
                    candidates = listOf(candidate("低匹配")),
                ) as AppResult.Success
                ).data
            batch.successCount shouldBe 0

            val item = repo.observeItems(batch.id).first().single()
            item.status shouldBe ImportItemStatus.NEEDS_CONFIRMATION

            repo.confirmItem(item.id, work("c"))

            val confirmed = repo.observeItems(batch.id).first().single()
            confirmed.status shouldBe ImportItemStatus.MATCHED
            confirmed.workId shouldBe "c"

            val refreshed = repo.observeBatches().first().single()
            refreshed.successCount shouldBe 1

            // 确认后可一键加入待补池。
            val added = (repo.addBatchToBacklog(batch.id) as AppResult.Success).data
            added.addedWorkIds shouldContainExactly listOf("c")
        }
    }

    "search failure yields an unmatched item without crashing " {
        runTest {
            val (repo, _, _) = newRepo(matches = emptyMap())
            val batch = (
                repo.createBatch(
                    name = "批次",
                    source = ImportSource.FILE_TXT,
                    candidates = listOf(candidate("任意标题")),
                ) as AppResult.Success
                ).data
            batch.failureCount shouldBe 1
            repo.observeItems(batch.id).first().single().status shouldBe ImportItemStatus.UNMATCHED
        }
    }
})

/** Fake [WorkRepository]：仅 [search] 按预置映射返回结果，其余方法不参与本测试。 */
private class FakeWorkRepository(
    private val matches: Map<String, List<WorkMatch>>,
) : WorkRepository {
    override fun observeWork(workId: String): Flow<UiState<Work>> = emptyFlow()
    override fun observeWorks(): Flow<List<Work>> = emptyFlow()

    override suspend fun search(query: String): AppResult<List<WorkMatch>> = AppResult.Success(matches[query].orEmpty())
    override suspend fun aggregateRatings(workId: String, forceRefresh: Boolean): AppResult<RatingAggregate> =
        throw UnsupportedOperationException("unused")
    override suspend fun aggregateRatingsCached(workId: String): RatingAggregate = RatingAggregate()
    override suspend fun overrideMatch(localId: String, chosen: SourceRef): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun loadPublicDiscovery(background: Boolean): AppResult<Int> = AppResult.Success(0)
    override suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
    ): AppResult<com.acgcompass.domain.repository.RankingPage> =
        AppResult.Success(com.acgcompass.domain.repository.RankingPage(emptyList(), 0))
    override suspend fun getCachedRanking(
        scopeKey: String,
    ): List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>> = emptyList()
    override suspend fun saveRankingCache(scopeKey: String, orderedWorkIds: List<String>) {}
}

/** 内存 Fake [ImportDao]，实现仓库使用到的方法。 */
private class FakeImportDao : ImportDao {
    private val batches = LinkedHashMap<String, ImportBatchEntity>()
    private val items = LinkedHashMap<String, ImportItemEntity>()
    private val batchesFlow = MutableStateFlow<List<ImportBatchEntity>>(emptyList())
    private val itemsFlow = MutableStateFlow<List<ImportItemEntity>>(emptyList())

    private fun emitBatches() {
        batchesFlow.value = batches.values.sortedByDescending { it.createdAt }
    }
    private fun emitItems() {
        itemsFlow.value = items.values.toList()
    }

    override fun observeBatches(): Flow<List<ImportBatchEntity>> = batchesFlow
    override suspend fun getAllBatches(): List<ImportBatchEntity> = batches.values.toList()
    override fun observeBatch(id: String): Flow<ImportBatchEntity?> = batchesFlow.map { batches[id] }
    override suspend fun getBatch(id: String): ImportBatchEntity? = batches[id]
    override suspend fun insertBatch(batch: ImportBatchEntity) = upsertBatch(batch)
    override suspend fun upsertBatch(batch: ImportBatchEntity) {
        batches[batch.id] = batch
        emitBatches()
    }
    override suspend fun updateBatch(batch: ImportBatchEntity) {
        batches[batch.id] = batch
        emitBatches()
    }
    override suspend fun deleteBatch(batch: ImportBatchEntity) {
        batches.remove(batch.id)
        emitBatches()
    }

    override fun observeItems(batchId: String): Flow<List<ImportItemEntity>> =
        itemsFlow.map { list -> list.filter { it.batchId == batchId } }
    override suspend fun getAllItems(): List<ImportItemEntity> = items.values.toList()
    override suspend fun getItems(batchId: String): List<ImportItemEntity> =
        items.values.filter { it.batchId == batchId }
    override suspend fun getItem(id: String): ImportItemEntity? = items[id]
    override fun observeItemsByStatus(status: String): Flow<List<ImportItemEntity>> =
        itemsFlow.map { list -> list.filter { it.status == status } }
    override suspend fun insertItem(item: ImportItemEntity) = upsertItem(item)
    override suspend fun upsertItem(item: ImportItemEntity) {
        items[item.id] = item
        emitItems()
    }
    override suspend fun upsertItems(list: List<ImportItemEntity>) {
        list.forEach { items[it.id] = it }
        emitItems()
    }
    override suspend fun updateItem(item: ImportItemEntity) {
        items[item.id] = item
        emitItems()
    }
    override suspend fun deleteItem(item: ImportItemEntity) {
        items.remove(item.id)
        emitItems()
    }
    override suspend fun deleteItemsForBatch(batchId: String) {
        items.values.filter { it.batchId == batchId }.forEach { items.remove(it.id) }
        emitItems()
    }
}

/** 内存 Fake [WorkDao]，供导入仓库与真实 [BacklogRepositoryImpl] 共享。 */
private class FakeImportWorkDao : WorkDao {
    private val works = LinkedHashMap<String, WorkEntity>()
    private val worksFlow = MutableStateFlow<List<WorkEntity>>(emptyList())
    val recommendationCounts = LinkedHashMap<String, RecommendationCountEntity>()
    private val recFlows = HashMap<String, MutableStateFlow<RecommendationCountEntity?>>()

    private fun emit() {
        worksFlow.value = works.values.toList()
    }
    private fun recFlow(id: String) = recFlows.getOrPut(id) { MutableStateFlow(recommendationCounts[id]) }

    override fun observeAll(): Flow<List<WorkEntity>> = worksFlow

    override fun observePublicCached(): Flow<List<WorkEntity>> = worksFlow
    override suspend fun getAll(): List<WorkEntity> = works.values.toList()
    override suspend fun count(): Int = works.size
    override suspend fun getById(id: String): WorkEntity? = works[id]
    override suspend fun upsert(work: WorkEntity) {
        works[work.id] = work
        emit()
    }
    override fun observeRecommendationCount(workId: String): Flow<RecommendationCountEntity?> = recFlow(workId)
    override suspend fun upsertRecommendationCount(count: RecommendationCountEntity) {
        recommendationCounts[count.workId] = count
        recFlow(count.workId).value = count
    }

    override fun observeById(id: String): Flow<WorkEntity?> = worksFlow.map { works[id] }
    override fun observeByMediaType(mediaType: String): Flow<List<WorkEntity>> =
        worksFlow.map { list -> list.filter { it.mediaType == mediaType } }
    override suspend fun getIdsByMediaType(mediaType: String, limit: Int): List<String> =
        works.values.filter { it.mediaType == mediaType }.take(limit).map { it.id }
    override fun search(query: String): Flow<List<WorkEntity>> = worksFlow
    override suspend fun insert(work: WorkEntity) = upsert(work)
    override suspend fun upsertAll(works: List<WorkEntity>) {
        works.forEach { this.works[it.id] = it }
        emit()
    }
    override suspend fun update(work: WorkEntity) = upsert(work)
    override suspend fun delete(work: WorkEntity) {
        works.remove(work.id)
        emit()
    }
    override suspend fun deleteById(id: String) {
        works.remove(id)
        emit()
    }
    override fun observeTagLinks(workId: String): Flow<List<WorkTagEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAllWorkTags(): List<WorkTagEntity> = emptyList()
    override suspend fun upsertWorkTags(links: List<WorkTagEntity>) = Unit
    override suspend fun deleteWorkTag(link: WorkTagEntity) = Unit
    override suspend fun deleteTagLinksForWork(workId: String) = Unit
    override fun observeRecommendationCounts(): Flow<List<RecommendationCountEntity>> =
        MutableStateFlow(recommendationCounts.values.toList())
    override suspend fun getAllRecommendationCounts(): List<RecommendationCountEntity> =
        recommendationCounts.values.toList()
}

/** 内存 Fake [BacklogDao]，供真实 [BacklogRepositoryImpl] 在导入测试中执行去重。 */
private class FakeImportBacklogDao : BacklogDao {
    private val store = LinkedHashMap<String, BacklogItemEntity>()
    private val flow = MutableStateFlow<List<BacklogItemEntity>>(emptyList())
    private fun emit() {
        flow.value = store.values.toList()
    }

    override fun observeAll(): Flow<List<BacklogItemEntity>> = flow
    override suspend fun getAll(): List<BacklogItemEntity> = store.values.toList()
    override suspend fun getByWork(workId: String): BacklogItemEntity? = store[workId]
    override suspend fun upsert(item: BacklogItemEntity) {
        store[item.workId] = item
        emit()
    }
    override suspend fun update(item: BacklogItemEntity) {
        store[item.workId] = item
        emit()
    }
    override suspend fun deleteByWork(workId: String) {
        store.remove(workId)
        emit()
    }
    override fun observeByWork(workId: String): Flow<BacklogItemEntity?> =
        flow.map { it.firstOrNull { e -> e.workId == workId } }
    override fun observeByPriority(priority: String): Flow<List<BacklogItemEntity>> =
        flow.map { list -> list.filter { it.priority == priority } }
    override fun observeDustMuseum(): Flow<List<BacklogItemEntity>> =
        flow.map { list -> list.filter { it.inDustMuseum } }
    override suspend fun insert(item: BacklogItemEntity) = upsert(item)
    override suspend fun upsertAll(items: List<BacklogItemEntity>) {
        items.forEach { store[it.workId] = it }
        emit()
    }
    override suspend fun delete(item: BacklogItemEntity) = deleteByWork(item.workId)
}

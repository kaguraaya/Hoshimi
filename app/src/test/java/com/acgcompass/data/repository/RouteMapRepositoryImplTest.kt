package com.acgcompass.data.repository

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.RouteNodeDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.RouteNodeEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity
import com.acgcompass.domain.ai.RouteRecommendation
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.RouteNode
import com.acgcompass.domain.model.RouteRelationType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AddResult
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DrawCriteria
import com.acgcompass.domain.repository.DrawResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * 补番路线图仓库单元测试。
 * 使用内存 Fake DAO + Fake [BacklogRepository]，无 Room / Android 依赖。
 *
 * 覆盖：
 * - [RouteMapRepositoryImpl.observeRoute] 映射并按 orderIndex 顺序观察节点。
 * - [RouteMapRepositoryImpl.upsertNodes] 落库。
 * - [RouteMapRepositoryImpl.addSeriesToBacklog] 整系列 / 仅必看，复用 addAll 去重，
 *   并安全跳过本地缺失作品。
 */
class RouteMapRepositoryImplTest : StringSpec({

    fun node(
        id: String,
        seriesId: String = "s1",
        workId: String = id,
        relation: RouteRelationType = RouteRelationType.SEQUEL,
        rec: RouteRecommendation = RouteRecommendation.OPTIONAL,
        order: Int = 0,
        confirmed: Boolean = true,
    ): RouteNode = RouteNode(
        id = id,
        seriesId = seriesId,
        workId = workId,
        relationType = relation,
        recommendation = rec,
        orderIndex = order,
        confirmed = confirmed,
    )

    fun newRepo(): Triple<RouteMapRepositoryImpl, FakeRouteNodeDao, Pair<FakeRouteWorkDao, FakeBacklogRepository>> {
        val routeDao = FakeRouteNodeDao()
        val workDao = FakeRouteWorkDao()
        val backlog = FakeBacklogRepository()
        val repo = RouteMapRepositoryImpl(routeDao, workDao, backlog, RouteMapTestDispatchers())
        return Triple(repo, routeDao, workDao to backlog)
    }

    "observeRoute maps entities to domain ordered by orderIndex " {
        runTest {
            val (repo, _, _) = newRepo()
            repo.upsertNodes(
                listOf(
                    node("b", workId = "wb", relation = RouteRelationType.MOVIE, order = 2),
                    node("a", workId = "wa", relation = RouteRelationType.SEQUEL, order = 1),
                ),
            )

            val observed = repo.observeRoute("s1").first()
            observed.map { it.id } shouldContainExactly listOf("a", "b")
            observed.map { it.relationType } shouldContainExactly
                listOf(RouteRelationType.SEQUEL, RouteRelationType.MOVIE)
        }
    }

    "observeRoute emits empty list for a series with no nodes" {
        runTest {
            val (repo, _, _) = newRepo()
            repo.observeRoute("missing").first().shouldBeEmpty()
        }
    }

    "addSeriesToBacklog adds the whole series, deduping workIds " {
        runTest {
            val (repo, _, env) = newRepo()
            val (workDao, backlog) = env
            workDao.put("wa")
            workDao.put("wb")
            repo.upsertNodes(
                listOf(
                    node("n1", workId = "wa", rec = RouteRecommendation.MUST, order = 1),
                    node("n2", workId = "wb", rec = RouteRecommendation.OPTIONAL, order = 2),
                    node("n3", workId = "wa", rec = RouteRecommendation.RECAP, order = 3),
                ),
            )

            val result = repo.addSeriesToBacklog("s1", mustOnly = false)

            // wa appears twice in nodes but is deduped before addAll.
            backlog.lastAdded.map { it.id } shouldContainExactly listOf("wa", "wb")
            result.addedWorkIds shouldContainExactly listOf("wa", "wb")
        }
    }

    "addSeriesToBacklog mustOnly=true adds only MUST nodes " {
        runTest {
            val (repo, _, env) = newRepo()
            val (workDao, backlog) = env
            workDao.put("wa")
            workDao.put("wb")
            repo.upsertNodes(
                listOf(
                    node("n1", workId = "wa", rec = RouteRecommendation.MUST, order = 1),
                    node("n2", workId = "wb", rec = RouteRecommendation.OPTIONAL, order = 2),
                ),
            )

            repo.addSeriesToBacklog("s1", mustOnly = true)

            backlog.lastAdded.map { it.id } shouldContainExactly listOf("wa")
        }
    }

    "addSeriesToBacklog skips nodes whose work is not cached locally, never fabricating ()" {
        runTest {
            val (repo, _, env) = newRepo()
            val (workDao, backlog) = env
            workDao.put("wa") // wb intentionally missing from local cache
            repo.upsertNodes(
                listOf(
                    node("n1", workId = "wa", order = 1),
                    node("n2", workId = "wb", order = 2),
                ),
            )

            repo.addSeriesToBacklog("s1", mustOnly = false)

            backlog.lastAdded.map { it.id } shouldContainExactly listOf("wa")
        }
    }

    "addSeriesToBacklog with no local works returns an empty AddResult and never calls addAll" {
        runTest {
            val (repo, _, env) = newRepo()
            val (_, backlog) = env
            repo.upsertNodes(listOf(node("n1", workId = "wa", order = 1)))

            val result = repo.addSeriesToBacklog("s1", mustOnly = false)

            result.addedWorkIds.shouldBeEmpty()
            result.duplicateWorkIds.shouldBeEmpty()
            backlog.addAllCallCount shouldBe 0
        }
    }
})

/** 测试用调度器：全部走 Unconfined，立即执行。 */
@OptIn(ExperimentalCoroutinesApi::class)
private class RouteMapTestDispatchers : DispatcherProvider {
    private val d: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val io: CoroutineDispatcher = d
    override val default: CoroutineDispatcher = d
    override val main: CoroutineDispatcher = d
}

/** 内存 Fake [RouteNodeDao]，仅实现仓库使用到的方法。 */
private class FakeRouteNodeDao : RouteNodeDao {
    private val store = LinkedHashMap<String, RouteNodeEntity>()
    private val flow = MutableStateFlow<List<RouteNodeEntity>>(emptyList())

    private fun emit() {
        flow.value = store.values.toList()
    }

    private fun bySeries(seriesId: String): List<RouteNodeEntity> =
        store.values.filter { it.seriesId == seriesId }.sortedBy { it.orderIndex }

    override fun observeBySeries(seriesId: String): Flow<List<RouteNodeEntity>> = flow.map { bySeries(seriesId) }

    override suspend fun getBySeries(seriesId: String): List<RouteNodeEntity> = bySeries(seriesId)

    override fun observeByWork(workId: String): Flow<List<RouteNodeEntity>> =
        flow.map { list -> list.filter { it.workId == workId } }

    override suspend fun insert(node: RouteNodeEntity) {
        store[node.id] = node
        emit()
    }

    override suspend fun upsert(node: RouteNodeEntity) {
        store[node.id] = node
        emit()
    }

    override suspend fun upsertAll(nodes: List<RouteNodeEntity>) {
        nodes.forEach { store[it.id] = it }
        emit()
    }

    override suspend fun update(node: RouteNodeEntity) {
        store[node.id] = node
        emit()
    }

    override suspend fun delete(node: RouteNodeEntity) {
        store.remove(node.id)
        emit()
    }

    override suspend fun deleteBySeries(seriesId: String) {
        store.values.removeAll { it.seriesId == seriesId }
        emit()
    }
}

/** 内存 Fake [WorkDao]，仅实现 [RouteMapRepositoryImpl] 用到的 getById。 */
private class FakeRouteWorkDao : WorkDao {
    private val works = LinkedHashMap<String, WorkEntity>()

    override suspend fun count(): Int = works.size

    fun put(id: String) {
        works[id] = WorkEntity(
            id = id,
            canonicalTitle = "作品 $id",
            titleJa = null,
            titleRomaji = null,
            titleEn = null,
            aliases = emptyList(),
            mediaType = "ANIME",
            year = null,
            status = "UNKNOWN",
            episodes = null,
            episodeMinutes = null,
            volumes = null,
            estPlayMinutes = null,
            coverUrl = null,
            primarySource = "BANGUMI",
            completionCostBucket = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    override suspend fun getById(id: String): WorkEntity? = works[id]

    // DAO methods not exercised by these repository tests.
    override suspend fun getAll(): List<WorkEntity> = works.values.toList()
    override suspend fun getAllWorkTags(): List<WorkTagEntity> = emptyList()
    override suspend fun getAllRecommendationCounts(): List<RecommendationCountEntity> = emptyList()
    override fun observeAll(): Flow<List<WorkEntity>> = MutableStateFlow(works.values.toList())
    override fun observePublicCached(): Flow<List<WorkEntity>> = MutableStateFlow(works.values.toList())
    override fun observeById(id: String): Flow<WorkEntity?> = MutableStateFlow(works[id])
    override fun observeByMediaType(mediaType: String): Flow<List<WorkEntity>> = MutableStateFlow(emptyList())
    override suspend fun getIdsByMediaType(mediaType: String, limit: Int): List<String> =
        works.values.filter { it.mediaType == mediaType }.take(limit).map { it.id }
    override fun search(query: String): Flow<List<WorkEntity>> = MutableStateFlow(emptyList())
    override suspend fun insert(work: WorkEntity) {
        works[work.id] = work
    }
    override suspend fun upsert(work: WorkEntity) {
        works[work.id] = work
    }
    override suspend fun upsertAll(works: List<WorkEntity>) {
        works.forEach { this.works[it.id] = it }
    }
    override suspend fun update(work: WorkEntity) {
        works[work.id] = work
    }
    override suspend fun delete(work: WorkEntity) {
        works.remove(work.id)
    }
    override suspend fun deleteById(id: String) {
        works.remove(id)
    }
    override fun observeTagLinks(workId: String): Flow<List<WorkTagEntity>> = MutableStateFlow(emptyList())
    override suspend fun upsertWorkTags(links: List<WorkTagEntity>) = Unit
    override suspend fun deleteWorkTag(link: WorkTagEntity) = Unit
    override suspend fun deleteTagLinksForWork(workId: String) = Unit
    override fun observeRecommendationCount(workId: String): Flow<RecommendationCountEntity?> = MutableStateFlow(null)
    override suspend fun upsertRecommendationCount(count: RecommendationCountEntity) = Unit
    override fun observeRecommendationCounts(): Flow<List<RecommendationCountEntity>> = MutableStateFlow(emptyList())
}

/** Fake [BacklogRepository]，记录最近一次 addAll 的入参并返回去重后的 [AddResult]。 */
private class FakeBacklogRepository : BacklogRepository {
    override suspend fun removeToTrash(ids: List<String>, batchId: String): Int = 0

    var lastAdded: List<Work> = emptyList()
        private set
    var addAllCallCount: Int = 0
        private set
    private val pool = LinkedHashSet<String>()

    override fun observeBacklog(filter: BacklogFilter, sort: BacklogSort): Flow<List<BacklogItem>> =
        MutableStateFlow(emptyList())

    override suspend fun addAll(items: List<Work>): AddResult {
        addAllCallCount++
        lastAdded = items
        val added = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        items.forEach { work ->
            if (pool.add(work.id)) added += work.id else duplicates += work.id
        }
        return AddResult(addedWorkIds = added, duplicateWorkIds = duplicates)
    }

    override suspend fun setPriority(id: String, p: Priority) = Unit
    override suspend fun setNote(id: String, note: String?) = Unit
    override suspend fun bulk(op: BulkOp, ids: List<String>) = com.acgcompass.core.common.AppResult.Success(Unit)
    override suspend fun archiveToDust(workId: String, prevStatus: String?) = Unit
    override suspend fun restoreFromDust(workId: String): String? = null
    override suspend fun draw(criteria: DrawCriteria): DrawResult = DrawResult(pick = null, reason = "")
}

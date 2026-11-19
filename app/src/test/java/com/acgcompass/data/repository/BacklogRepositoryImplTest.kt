package com.acgcompass.data.repository

import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DrawCriteria
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * 待补池仓库单元测试。使用内存 Fake DAO，无 Room / Android 依赖。
 *
 * 覆盖去重、幂等、被安利计数、筛选 / 排序、
 * 优先级 / 备注编辑、批量操作与一键抽番。
 */
class BacklogRepositoryImplTest : StringSpec({

    fun work(id: String, mediaType: MediaType = MediaType.ANIME): Work = Work(
        id = id,
        titles = Titles(canonical = "作品 $id"),
        mediaType = mediaType,
        primarySource = SourceId.BANGUMI,
    )

    fun newRepo(): Triple<BacklogRepositoryImpl, FakeBacklogDao, FakeWorkDao> {
        val backlogDao = FakeBacklogDao()
        val workDao = FakeWorkDao()
        val repo = BacklogRepositoryImpl(
            backlogDao,
            workDao,
            TestDispatchers(),
            NoWishSync,
            DeletingLocalTrash(backlogDao),
            com.acgcompass.domain.undo.UndoController(),
        )
        return Triple(repo, backlogDao, workDao)
    }

    "addAll dedupes by workId and is idempotent " {
        runTest {
            val (repo, backlogDao, _) = newRepo()
            val items = listOf(work("a"), work("b"), work("a"))

            val first = repo.addAll(items)
            first.addedWorkIds shouldContainExactly listOf("a", "b")
            first.duplicateWorkIds shouldContainExactly listOf("a")
            backlogDao.observeAll().first().map { it.workId }.toSet() shouldBe setOf("a", "b")

            // 再次加入同一集合：池规模不增长（幂等）。
            val second = repo.addAll(items)
            second.addedWorkIds shouldBe emptyList()
            backlogDao.observeAll().first().size shouldBe 2
        }
    }

    "addAll increments recommendedCount once per hit " {
        runTest {
            val (repo, _, workDao) = newRepo()
            // 同一作品命中 3 次 → recommendedCount == 3。
            repo.addAll(listOf(work("x"), work("x"), work("x")))
            workDao.recommendationCounts["x"]?.recommendedCount shouldBe 3
        }
    }

    "observeBacklog filters by priority and media type" {
        runTest {
            val (repo, backlogDao, workDao) = newRepo()
            repo.addAll(listOf(work("a", MediaType.ANIME), work("g", MediaType.GAME)))
            repo.setPriority("a", Priority.HIGH)

            val byPriority = repo.observeBacklog(
                filter = BacklogFilter(priorities = setOf(Priority.HIGH)),
                sort = BacklogSort.ADDED_DESC,
            ).first()
            byPriority.map { it.workId } shouldContainExactly listOf("a")

            val byType = repo.observeBacklog(
                filter = BacklogFilter(mediaTypes = setOf(MediaType.GAME)),
                sort = BacklogSort.ADDED_DESC,
            ).first()
            byType.map { it.workId } shouldContainExactly listOf("g")
        }
    }

    "observeBacklog sorts by priority high to low" {
        runTest {
            val (repo, _, _) = newRepo()
            repo.addAll(listOf(work("low"), work("high"), work("mid")))
            repo.setPriority("low", Priority.LOW)
            repo.setPriority("high", Priority.HIGH)
            repo.setPriority("mid", Priority.MEDIUM)

            val sorted = repo.observeBacklog(sort = BacklogSort.PRIORITY_DESC).first()
            sorted.map { it.workId } shouldContainExactly listOf("high", "mid", "low")
        }
    }

    "setNote stores and clears the note" {
        runTest {
            val (repo, backlogDao, _) = newRepo()
            repo.addAll(listOf(work("a")))

            repo.setNote("a", "  想看的理由  ")
            backlogDao.getByWork("a")?.note shouldBe "想看的理由"

            repo.setNote("a", "   ")
            backlogDao.getByWork("a")?.note shouldBe null
        }
    }

    "bulk DELETE removes selected items" {
        runTest {
            val (repo, backlogDao, _) = newRepo()
            repo.addAll(listOf(work("a"), work("b"), work("c")))

            repo.bulk(BulkOp.DELETE, listOf("a", "c"))
            backlogDao.observeAll().first().map { it.workId } shouldContainExactly listOf("b")
        }
    }

    "bulk SET_PRIORITY_HIGH updates priority" {
        runTest {
            val (repo, backlogDao, _) = newRepo()
            repo.addAll(listOf(work("a"), work("b")))

            repo.bulk(BulkOp.SET_PRIORITY_HIGH, listOf("a", "b"))
            backlogDao.observeAll().first().all { it.priority == Priority.HIGH.name } shouldBe true
        }
    }

    "draw picks a candidate and excludes ids; empty pool returns null pick" {
        runTest {
            val (repo, _, _) = newRepo()
            repo.addAll(listOf(work("a"), work("b")))

            val pick = repo.draw(DrawCriteria(excludeWorkIds = setOf("a")))
            pick.pick.shouldNotBeNull()
            pick.pick!!.workId shouldBe "b"

            val none = repo.draw(DrawCriteria(excludeWorkIds = setOf("a", "b")))
            none.pick shouldBe null
        }
    }
})

/** 内存 Fake [BacklogDao]，仅实现仓库使用到的方法。 */
private class FakeBacklogDao : BacklogDao {
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

/** 内存 Fake [WorkDao]，仅实现仓库使用到的方法。 */
private class FakeWorkDao : WorkDao {
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

    // --- unused in these tests ---
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

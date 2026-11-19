package com.acgcompass.data.repository

import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.remote.bangumi.BangumiApi
import com.acgcompass.data.remote.bangumi.BangumiNextApi
import com.acgcompass.data.remote.bangumi.BangumiNextCommentsResponse
import com.acgcompass.data.remote.bangumi.BangumiRatingDto
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSubjectDto
import com.acgcompass.data.remote.bangumi.BangumiSubjectType
import com.acgcompass.data.remote.bangumi.BangumiTagDto
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkFeatureRepositoryBasicFirstTest : StringSpec({
    "cached BASIC feature is returned without a subject request" {
        runTest {
            val calls = AtomicInteger()
            val repository = repository(MemoryWorkFeatureDao(listOf(entity())), calls)

            val feature = repository.getFeature("42", allowNetwork = true)

            feature?.tagCounts?.single()?.name shouldBe "治愈"
            calls.get() shouldBe 0
        }
    }

    "missing feature performs one subject request and reuses the stored BASIC row" {
        runTest {
            val calls = AtomicInteger()
            val repository = repository(MemoryWorkFeatureDao(), calls)

            repository.getFeature("42", allowNetwork = true)?.completeness shouldBe WorkFeatureCompleteness.BASIC
            repository.getFeature("42", allowNetwork = true)?.tagCounts?.single()?.count shouldBe 321
            calls.get() shouldBe 1
        }
    }

    "single-work flow emits BASIC first and EXTENDED after background enrichment" {
        runTest {
            val calls = AtomicInteger()
            val dao = MemoryWorkFeatureDao()
            val repository = repository(dao, calls)
            val observed = async {
                repository.observeFeature("42").filterNotNull().take(2).toList()
            }
            runCurrent()

            repository.seedLightweight(listOf(feature()))
            runCurrent()
            repository.refresh("42") shouldBe true

            observed.await().map { it.completeness } shouldContainExactly listOf(
                WorkFeatureCompleteness.BASIC,
                WorkFeatureCompleteness.EXTENDED,
            )
            calls.get() shouldBe 1
        }
    }
})

private fun repository(dao: WorkFeatureDao, subjectCalls: AtomicInteger): WorkFeatureRepositoryImpl {
    val bangumiApi = Proxy.newProxyInstance(
        BangumiApi::class.java.classLoader,
        arrayOf(BangumiApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSubject" -> {
                subjectCalls.incrementAndGet()
                Response.success(subject())
            }
            "getSubjectPersons", "getSubjectCharacters" -> Response.success(emptyList<Any>())
            else -> error("Unexpected Bangumi API call: ${method.name}")
        }
    } as BangumiApi
    val nextApi = Proxy.newProxyInstance(
        BangumiNextApi::class.java.classLoader,
        arrayOf(BangumiNextApi::class.java),
    ) { _, method, _ ->
        if (method.name == "getSubjectComments") {
            Response.success(BangumiNextCommentsResponse())
        } else {
            error("Unexpected Bangumi Next API call: ${method.name}")
        }
    } as BangumiNextApi
    return WorkFeatureRepositoryImpl(
        workFeatureDao = dao,
        bangumi = BangumiRemoteDataSource(bangumiApi, nextApi),
        dispatchers = TestDispatchers(),
    )
}

private fun subject() = BangumiSubjectDto(
    id = 42,
    type = BangumiSubjectType.ANIME,
    name = "Original",
    nameCn = "中文标题",
    eps = 12,
    tags = listOf(BangumiTagDto("治愈", 321)),
    rating = BangumiRatingDto(score = 8.1f, total = 1200),
)

private fun feature() = WorkFeature(
    subjectId = "42",
    tagCounts = listOf(TagCount("治愈", 321)),
    bangumiScore = 8.1f,
    bangumiVotes = 1200,
    eps = 12,
    mediaType = MediaType.ANIME,
    titleVariants = listOf("Original", "中文标题"),
    completeness = WorkFeatureCompleteness.BASIC,
    baseDataUpdatedAt = 100L,
    updatedAt = 100L,
)

private fun entity() = WorkFeatureEntity(
    subjectId = "42",
    tagCountsJson = "[{\"name\":\"治愈\",\"count\":321}]",
    staff = emptyList(),
    characters = emptyList(),
    cv = emptyList(),
    bangumiScore = 8.1f,
    bangumiVotes = 1200,
    eps = 12,
    durationMin = 0,
    platform = "TV",
    mediaType = MediaType.ANIME.name,
    titles = listOf("Original", "中文标题"),
    baseDataUpdatedAt = 100L,
    extendedDataUpdatedAt = null,
    completeness = WorkFeatureCompleteness.BASIC.name,
    updatedAt = 100L,
)

private class MemoryWorkFeatureDao(initial: List<WorkFeatureEntity> = emptyList()) : WorkFeatureDao {
    private val rows = MutableStateFlow(initial.associateBy { it.subjectId })

    override suspend fun getById(subjectId: String): WorkFeatureEntity? = rows.value[subjectId]

    override fun observeById(subjectId: String): Flow<WorkFeatureEntity?> = rows.map { it[subjectId] }

    override suspend fun getByIds(subjectIds: List<String>): List<WorkFeatureEntity> =
        subjectIds.mapNotNull(rows.value::get)

    override suspend fun getAllIds(): List<String> = rows.value.keys.toList()

    override suspend fun getAll(limit: Int): List<WorkFeatureEntity> = rows.value.values.take(limit)

    override suspend fun getIncompleteIds(limit: Int): List<String> = rows.value.values
        .filter { it.completeness != WorkFeatureCompleteness.EXTENDED.name }
        .take(limit)
        .map { it.subjectId }

    override suspend fun getIncompleteIds(subjectIds: List<String>, limit: Int): List<String> = rows.value.values
        .filter { it.subjectId in subjectIds && it.completeness != WorkFeatureCompleteness.EXTENDED.name }
        .take(limit)
        .map { it.subjectId }

    override suspend fun getPoolIdsWithoutFeature(mediaType: String, limit: Int): List<String> = emptyList()

    override suspend fun count(): Int = rows.value.size

    override suspend fun countAnime(): Int = rows.value.size

    override suspend fun countPoolWithoutFeature(mediaType: String): Int = 0

    override suspend fun countVerified(): Int =
        rows.value.values.count { it.completeness != WorkFeatureCompleteness.EMBEDDED.name }

    override suspend fun upsert(entity: WorkFeatureEntity) {
        rows.value = rows.value + (entity.subjectId to entity)
    }

    override suspend fun upsertAll(entities: List<WorkFeatureEntity>) {
        rows.value = rows.value + entities.associateBy { it.subjectId }
    }
}

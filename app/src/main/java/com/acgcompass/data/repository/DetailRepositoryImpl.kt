package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.entity.DetailCacheEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.detail.DetailCharacter
import com.acgcompass.domain.detail.DetailComment
import com.acgcompass.domain.detail.DetailExtrasData
import com.acgcompass.domain.detail.DetailRelation
import com.acgcompass.domain.detail.DetailStaff
import com.acgcompass.domain.repository.DetailRepository
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class DetailRepositoryImpl @Inject constructor(
    private val cacheDao: CacheDao,
    private val bangumi: BangumiRemoteDataSource,
    private val json: Json,
) : DetailRepository {
    private val validatedUntil = ConcurrentHashMap<String, Long>()

    override fun observeBangumiExtras(workId: String, subjectId: Int, forceRefresh: Boolean): Flow<DetailExtrasData> =
        flow {
            val now = System.currentTimeMillis()
            val cached = readSections(workId)
            val cachedMerged = cached.merged()
            if (cached.hasAny) emit(cachedMerged)

            val plan = refreshPlan(workId, cached, now, forceRefresh)
            if (!plan.hasWork) return@flow
            val refreshed = refreshSections(subjectId, plan)
            persistRefresh(workId, refreshed, now)
            val merged = refreshed.mergeWith(cachedMerged)
            if (merged != cachedMerged || !cached.hasAny) emit(merged)
        }.distinctUntilChanged()

    private fun refreshPlan(workId: String, cached: CachedSections, now: Long, forceRefresh: Boolean) = RefreshPlan(
        credits = isStale(workId, CREDITS, cached.credits, now, forceRefresh),
        comments = isStale(workId, COMMENTS, cached.comments, now, forceRefresh),
        base = isStale(workId, BASE, cached.base, now, forceRefresh),
    )

    private suspend fun refreshSections(subjectId: Int, plan: RefreshPlan): RefreshedSections = coroutineScope {
        val credits = async { if (plan.credits) refreshCredits(subjectId) else null }
        val comments = async { if (plan.comments) refreshComments(subjectId) else null }
        val base = async { if (plan.base) refreshBase(subjectId) else null }
        RefreshedSections(credits.await(), comments.await(), base.await())
    }

    private suspend fun refreshCredits(subjectId: Int): DetailExtrasData? {
        val characters = (bangumi.getSubjectCharacters(subjectId) as? AppResult.Success)?.data
        val staff = (bangumi.getSubjectPersons(subjectId) as? AppResult.Success)?.data
        val relations = (bangumi.getSubjectRelations(subjectId) as? AppResult.Success)?.data
        return if (characters == null && staff == null && relations == null) {
            null
        } else {
            DetailExtrasData(
                characters = characters.orEmpty().map { item ->
                    DetailCharacter(item.name, item.relation, item.actors.firstOrNull()?.name)
                },
                staff = staff.orEmpty().map { item ->
                    DetailStaff(item.name, item.relation ?: item.career.firstOrNull())
                },
                relations = relations.orEmpty().map { item ->
                    DetailRelation(item.id, item.type, item.name, item.nameCn, item.relation)
                },
            )
        }
    }

    private suspend fun refreshComments(subjectId: Int): DetailExtrasData? =
        (bangumi.getSubjectComments(subjectId, limit = 20) as? AppResult.Success)?.data?.let { items ->
            DetailExtrasData(comments = items.map { DetailComment(it.nickname, it.rate, it.text) })
        }

    private suspend fun refreshBase(subjectId: Int): DetailExtrasData? =
        (bangumi.getSubject(subjectId) as? AppResult.Success)?.data?.let { work ->
            DetailExtrasData(tags = work.tags.map { it.name }.filter { it.isNotBlank() }.distinct())
        }

    private suspend fun persistRefresh(workId: String, refreshed: RefreshedSections, now: Long) {
        refreshed.credits?.let {
            writeIfChanged(workId, CREDITS, it, DetailCacheTtl.CREDITS_RELATIONS_MILLIS, now)
        }
        refreshed.comments?.let { writeIfChanged(workId, COMMENTS, it, DetailCacheTtl.COMMENTS_MILLIS, now) }
        refreshed.base?.let { writeIfChanged(workId, BASE, it, DetailCacheTtl.BASE_MILLIS, now) }
    }

    private suspend fun readSections(workId: String): CachedSections = CachedSections(
        credits = cacheDao.getDetail(workId, SOURCE, CREDITS),
        comments = cacheDao.getDetail(workId, SOURCE, COMMENTS),
        base = cacheDao.getDetail(workId, SOURCE, BASE),
        json = json,
    )

    private suspend fun writeIfChanged(
        workId: String,
        section: String,
        data: DetailExtrasData,
        ttlMillis: Long,
        now: Long,
    ) {
        val payload = json.encodeToString(data)
        val hash = payload.sha256()
        if (cacheDao.getDetail(workId, SOURCE, section)?.contentHash == hash) {
            validatedUntil["$workId|$section"] = now + ttlMillis
            return
        }
        cacheDao.upsertDetail(
            DetailCacheEntity(
                workId = workId,
                source = SOURCE,
                section = section,
                payloadJson = payload,
                contentHash = hash,
                fetchedAt = now,
                expiresAt = now + ttlMillis,
                schemaVersion = SCHEMA_VERSION,
            ),
        )
        validatedUntil.remove("$workId|$section")
    }

    private fun isStale(
        workId: String,
        section: String,
        entity: DetailCacheEntity?,
        now: Long,
        forceRefresh: Boolean,
    ): Boolean {
        if (forceRefresh || entity == null) return true
        return entity.expiresAt <= now && (validatedUntil["$workId|$section"] ?: 0L) <= now
    }

    private data class CachedSections(
        val credits: DetailCacheEntity?,
        val comments: DetailCacheEntity?,
        val base: DetailCacheEntity?,
        val json: Json,
    ) {
        val hasAny: Boolean get() = credits != null || comments != null || base != null

        fun merged(): DetailExtrasData {
            fun decode(entity: DetailCacheEntity?): DetailExtrasData = entity?.let {
                runCatching { json.decodeFromString<DetailExtrasData>(it.payloadJson) }.getOrNull()
            } ?: DetailExtrasData()
            val creditData = decode(credits)
            val commentData = decode(comments)
            val baseData = decode(base)
            return DetailExtrasData(
                characters = creditData.characters,
                staff = creditData.staff,
                relations = creditData.relations,
                comments = commentData.comments,
                tags = baseData.tags,
            )
        }
    }

    private data class RefreshPlan(
        val credits: Boolean,
        val comments: Boolean,
        val base: Boolean,
    ) {
        val hasWork: Boolean get() = credits || comments || base
    }

    private data class RefreshedSections(
        val credits: DetailExtrasData?,
        val comments: DetailExtrasData?,
        val base: DetailExtrasData?,
    ) {
        fun mergeWith(cached: DetailExtrasData) = DetailExtrasData(
            characters = credits?.characters ?: cached.characters,
            staff = credits?.staff ?: cached.staff,
            relations = credits?.relations ?: cached.relations,
            comments = comments?.comments ?: cached.comments,
            tags = base?.tags ?: cached.tags,
        )
    }

    private companion object {
        const val SOURCE = "BANGUMI"
        const val CREDITS = "credits_relations"
        const val COMMENTS = "comments"
        const val BASE = "base"
        const val SCHEMA_VERSION = 1
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

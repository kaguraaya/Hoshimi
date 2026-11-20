package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.logging.RedactingLogger
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.entity.DetailCacheEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.detail.DetailCharacter
import com.acgcompass.domain.detail.DetailComment
import com.acgcompass.domain.detail.DetailExtrasData
import com.acgcompass.domain.detail.DetailRelation
import com.acgcompass.domain.detail.DetailStaff
import com.acgcompass.domain.detail.ExtrasFailure
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.repository.DetailRepository
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
) : DetailRepository {
    private val validatedUntil = ConcurrentHashMap<String, Long>()

    override fun observeBangumiExtras(workId: String, subjectId: Int, forceRefresh: Boolean): Flow<DetailExtrasData> =
        flow {
            val now = System.currentTimeMillis()
            val cached = readSections(workId)
            val cachedMerged = cached.merged()
            if (cached.hasAny) emit(cachedMerged)

            val plan = refreshPlan(workId, cached, now, forceRefresh).let {
                it.copy(credits = it.credits || cachedMerged.looksIncomplete())
            }
            if (!plan.hasWork) return@flow
            val errors = java.util.concurrent.ConcurrentLinkedQueue<AppError>()
            val refreshed = refreshSections(subjectId, plan, cachedMerged, errors)
            persistRefresh(workId, refreshed, now)
            val creditsFailed = plan.credits && refreshed.credits?.complete != true
            val commentsFailed = plan.comments && refreshed.comments == null
            val failure = if (creditsFailed || commentsFailed) failureOf(errors) else null
            taskRecorder.recordExtrasOutcome(subjectId, failure, errors.toList(), plan.credits || plan.comments)
            val merged = refreshed.mergeWith(cachedMerged).copy(
                creditsFailed = creditsFailed,
                commentsFailed = commentsFailed,
                failure = failure,
            )
            if (merged != cachedMerged || !cached.hasAny) emit(merged)
        }.distinctUntilChanged()

    private fun refreshPlan(workId: String, cached: CachedSections, now: Long, forceRefresh: Boolean) = RefreshPlan(
        credits = isStale(workId, CREDITS, cached.credits, now, forceRefresh),
        comments = isStale(workId, COMMENTS, cached.comments, now, forceRefresh),
        base = isStale(workId, BASE, cached.base, now, forceRefresh),
    )

    private suspend fun refreshSections(
        subjectId: Int,
        plan: RefreshPlan,
        cached: DetailExtrasData,
        errors: MutableCollection<AppError>,
    ): RefreshedSections = coroutineScope {
        val credits = async { if (plan.credits) refreshCredits(subjectId, cached, errors) else null }
        val comments = async { if (plan.comments) refreshComments(subjectId, errors) else null }
        val base = async { if (plan.base) refreshBase(subjectId) else null }
        RefreshedSections(credits.await(), comments.await(), base.await())
    }

    /** 三个接口分别重试一次；失败的部分沿用缓存，不用空列表覆盖。 */
    private suspend fun refreshCredits(
        subjectId: Int,
        cached: DetailExtrasData,
        errors: MutableCollection<AppError>,
    ): CreditsFetch = coroutineScope {
        val charactersAsync = async { retryOnce(errors) { bangumi.getSubjectCharacters(subjectId) } }
        val staffAsync = async { retryOnce(errors) { bangumi.getSubjectPersons(subjectId) } }
        val relationsAsync = async { retryOnce(errors) { bangumi.getSubjectRelations(subjectId) } }
        val characters = charactersAsync.await()
        val staff = staffAsync.await()
        val relations = relationsAsync.await()
        val data = DetailExtrasData(
            characters = characters?.map { item ->
                DetailCharacter(item.name, item.relation, item.actors.firstOrNull()?.name)
            } ?: cached.characters,
            staff = staff?.map { item -> DetailStaff(item.name, item.relation ?: item.career.firstOrNull()) }
                ?: cached.staff,
            relations = relations?.map { item ->
                DetailRelation(item.id, item.type, item.name, item.nameCn, item.relation)
            } ?: cached.relations,
        )
        val complete = characters != null && staff != null && relations != null
        val anySuccess = characters != null || staff != null || relations != null
        CreditsFetch(data.takeIf { anySuccess }, complete)
    }

    private suspend fun refreshComments(subjectId: Int, errors: MutableCollection<AppError>): DetailExtrasData? =
        retryOnce(errors) { bangumi.getSubjectComments(subjectId, limit = 20) }?.let { items ->
            DetailExtrasData(comments = items.map { DetailComment(it.nickname, it.rate, it.text) })
        }

    private suspend fun refreshBase(subjectId: Int): DetailExtrasData? =
        (bangumi.getSubject(subjectId) as? AppResult.Success)?.data?.let { work ->
            DetailExtrasData(tags = work.tags.map { it.name }.filter { it.isNotBlank() }.distinct())
        }

    private suspend fun persistRefresh(workId: String, refreshed: RefreshedSections, now: Long) {
        refreshed.credits?.data?.let {
            val ttl = if (refreshed.credits.complete) {
                DetailCacheTtl.CREDITS_RELATIONS_MILLIS
            } else {
                DetailCacheTtl.PARTIAL_MILLIS
            }
            writeIfChanged(workId, CREDITS, it, ttl, now)
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
                runCatchingCancellable { json.decodeFromString<DetailExtrasData>(it.payloadJson) }.getOrNull()
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

    private data class CreditsFetch(val data: DetailExtrasData?, val complete: Boolean)

    private data class RefreshedSections(
        val credits: CreditsFetch?,
        val comments: DetailExtrasData?,
        val base: DetailExtrasData?,
    ) {
        fun mergeWith(cached: DetailExtrasData) = DetailExtrasData(
            characters = credits?.data?.characters ?: cached.characters,
            staff = credits?.data?.staff ?: cached.staff,
            relations = credits?.data?.relations ?: cached.relations,
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

private const val RETRY_DELAY_MILLIS = 2_500L

/** 失败后隔一会儿再试一次，仍失败返回 null；失败原因记进 [errors]。 */
private suspend fun <T> retryOnce(errors: MutableCollection<AppError>, block: suspend () -> AppResult<T>): T? {
    repeat(2) { attempt ->
        if (attempt > 0) delay(RETRY_DELAY_MILLIS)
        when (val result = block()) {
            is AppResult.Success -> return result.data
            is AppResult.Failure -> if (attempt > 0) errors += result.error
        }
    }
    return null
}

/** 几个请求的失败原因里，「请求太频繁」最优先说明，其次是网络。 */
private fun failureOf(errors: Collection<AppError>): ExtrasFailure = when {
    errors.any { it is AppError.RateLimited } -> ExtrasFailure.RATE_LIMITED
    errors.any { it is AppError.Network } -> ExtrasFailure.NETWORK
    else -> ExtrasFailure.OTHER
}

/** 角色和 Staff 都为空多半是之前请求失败留下的，重新拉一次。 */
private fun DetailExtrasData.looksIncomplete(): Boolean = characters.isEmpty() && staff.isEmpty()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** 记下这次拉评论 / 角色的结果：失败写原因和错误类型（给「后台任务」页和日志），成功写一句说明。 */
private suspend fun TaskRunRecorder.recordExtrasOutcome(
    subjectId: Int,
    failure: ExtrasFailure?,
    errors: List<AppError>,
    fetched: Boolean,
) {
    if (failure != null) {
        val kinds = errors.mapNotNull { it::class.simpleName }.distinct()
        extrasLogger.w(DETAIL_EXTRAS_TAG, "条目 $subjectId 扩展资料没拉全：$failure $kinds")
        failed(
            BackgroundTaskKind.DETAIL_EXTRAS,
            "条目 $subjectId 的评论或角色没拉全：" +
                (errors.firstOrNull()?.let(TaskRunRecorder::describe) ?: "原因不明") +
                kinds.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "（", postfix = "）").orEmpty(),
        )
    } else if (fetched) {
        succeeded(BackgroundTaskKind.DETAIL_EXTRAS, "条目 $subjectId 的评论和角色已更新")
    }
}

private val extrasLogger = RedactingLogger()
private const val DETAIL_EXTRAS_TAG = "DetailExtras"

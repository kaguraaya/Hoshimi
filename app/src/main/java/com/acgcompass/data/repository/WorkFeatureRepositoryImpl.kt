package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSubjectDto
import com.acgcompass.data.remote.bangumi.histogram
import com.acgcompass.data.remote.bangumi.mapBangumiMediaType
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import com.acgcompass.domain.taste.WorkFeatureRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [WorkFeatureRepository] 实现：Room `work_features` 为单一可信源，缓存缺失 / 过期（> [TTL_MILLIS]）
 * 时 best-effort 联网（Bangumi 条目 DTO + 关联人物 + 关联角色）补齐并落库。
 *
 * 韧性：任一网络步骤失败都被吞掉（staff / 角色 / CV 退化为空集合，仍可用社区标签建画像），绝不崩溃。
 * 标签计数以 JSON 持久化（[tagCountsJson]）；staff/角色/CV 经 `Converters` 以 `List<String>` 持久化。
 */
@Singleton
class WorkFeatureRepositoryImpl @Inject constructor(
    private val workFeatureDao: WorkFeatureDao,
    private val bangumi: BangumiRemoteDataSource,
    private val dispatchers: DispatcherProvider,
) : WorkFeatureRepository {

    override suspend fun seedLightweight(features: List<WorkFeature>) = withContext(dispatchers.io) {
        if (features.isEmpty()) return@withContext
        val existing = features.map { it.subjectId }
            .distinct()
            .chunked(900)
            .flatMap { workFeatureDao.getByIds(it) }
            .associateBy { it.subjectId }
        workFeatureDao.upsertAll(
            features.distinctBy { it.subjectId }.map { feature ->
                feature.toEntity(existing[feature.subjectId])
            },
        )
    }

    override suspend fun ensureBasic(
        subjectIds: List<String>,
        networkBudget: Int,
        background: Boolean,
        onProgress: (suspend (done: Int, total: Int) -> Unit)?,
    ): Map<String, WorkFeature> = withContext(dispatchers.io) {
        val priority = if (background) RequestPriority.BACKGROUND else RequestPriority.FOREGROUND
        val ids = subjectIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val cached = ids.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        val resolved = ConcurrentHashMap<String, WorkFeature>()
        val missing = ArrayList<Pair<Int, String>>()
        var budget = networkBudget.coerceAtLeast(0)
        val progress = AtomicInteger(0)
        suspend fun tick() = onProgress?.invoke(progress.incrementAndGet(), ids.size)

        ids.forEach { id ->
            val local = cached[id]
            if (local != null && hasUsableBaseData(local)) {
                resolved[id] = local.toDomain()
                tick()
            } else {
                val numericId = id.toIntOrNull()
                if (numericId != null && budget > 0) {
                    budget--
                    missing += numericId to id
                } else {
                    local?.let { resolved[id] = it.toDomain() }
                    tick()
                }
            }
        }

        if (missing.isNotEmpty()) {
            val gate = Semaphore(BASIC_FETCH_CONCURRENCY)
            coroutineScope {
                missing.map { (numericId, id) ->
                    async {
                        gate.withPermit {
                            fetchBasicAndCache(numericId, id, priority)?.let { resolved[id] = it }
                            tick()
                        }
                    }
                }.awaitAll()
            }
        }
        ids.mapNotNull { id -> resolved[id]?.let { id to it } }.toMap(LinkedHashMap())
    }

    override suspend fun countUnverified(subjectIds: Collection<String>): Int = withContext(dispatchers.io) {
        val ids = subjectIds.filter(String::isNotBlank).distinct()
        val cached = ids.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        ids.count { id -> cached[id]?.let(::hasUsableBaseData) != true }
    }

    override suspend fun localStats(): Pair<Int, Int> = withContext(dispatchers.io) {
        workFeatureDao.count() to workFeatureDao.countVerified()
    }

    override suspend fun hasStableBasicFeatures(subjectIds: Collection<String>): Boolean = withContext(dispatchers.io) {
        val ids = subjectIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return@withContext true
        val cached = ids.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        ids.all { id -> cached[id]?.let(::hasUsableBaseData) == true }
    }

    override suspend fun getFeature(subjectId: String, allowNetwork: Boolean): WorkFeature? =
        withContext(dispatchers.io) {
            val cached = workFeatureDao.getById(subjectId)
            if (cached != null && hasAnyBaseData(cached)) {
                return@withContext cached.toDomain()
            }
            val sid = subjectId.toIntOrNull()
            if (!allowNetwork || sid == null) return@withContext cached?.toDomain()
            fetchBasicAndCache(sid, subjectId) ?: cached?.toDomain()
        }

    override fun observeFeature(subjectId: String): Flow<WorkFeature?> =
        workFeatureDao.observeById(subjectId).map { it?.toDomain() }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun getFeatures(
        subjectIds: List<String>,
        networkBudget: Int,
        onProgress: (suspend (done: Int, total: Int) -> Unit)?,
    ): Map<String, WorkFeature> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) return@withContext emptyMap()
        val cached = subjectIds.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        val total = subjectIds.size
        val progress = AtomicInteger(0)
        suspend fun tick() {
            onProgress?.invoke(progress.incrementAndGet(), total)
        }

        // 先解析缓存命中；再挑出需联网者（限额内、数字 id，保序取前 networkBudget 个），其余用陈旧缓存兜底。
        val resolved = ConcurrentHashMap<String, WorkFeature>()
        val toFetch = ArrayList<Pair<Int, String>>()
        var budget = networkBudget
        for (id in subjectIds) {
            val hit = cached[id]
            if (hit != null && isFresh(hit)) {
                resolved[id] = hit.toDomain()
                tick()
                continue
            }
            val sid = id.toIntOrNull()
            if (budget > 0 && sid != null) {
                budget--
                toFetch += sid to id
            } else {
                if (hit != null) resolved[id] = hit.toDomain()
                tick()
            }
        }

        // 需联网的作品并发补齐；外层并发数受限，每部作品内部的独立请求也并发执行。
        // 单项失败时回退到已有缓存，避免整批任务被一个远端错误中断。
        if (toFetch.isNotEmpty()) {
            val gate = Semaphore(FETCH_CONCURRENCY)
            coroutineScope {
                toFetch.map { (sid, id) ->
                    async {
                        gate.withPermit {
                            val value = fetchAndCache(sid, id, RequestPriority.BACKGROUND) ?: cached[id]?.toDomain()
                            if (value != null) resolved[id] = value
                            tick()
                        }
                    }
                }.awaitAll()
            }
        }

        // 保序返回（键顺序 = 入参顺序）。
        val result = LinkedHashMap<String, WorkFeature>(subjectIds.size)
        for (id in subjectIds) resolved[id]?.let { result[id] = it }
        result
    }

    override suspend fun getCached(subjectId: String): WorkFeature? = withContext(dispatchers.io) {
        workFeatureDao.getById(subjectId)?.toDomain()
    }

    override suspend fun getCachedPool(limit: Int): List<WorkFeature> = withContext(dispatchers.io) {
        workFeatureDao.getAll(limit).map { it.toDomain() }
    }

    override suspend fun refresh(subjectId: String): Boolean = withContext(dispatchers.io) {
        val sid = subjectId.toIntOrNull() ?: return@withContext false
        fetchAndCache(sid, subjectId, RequestPriority.BACKGROUND) != null
    }

    /**
     * best-effort 拉取条目 + 人物 + 角色并落库。条目本身拉取失败则返回 null（不落库）。
     * 三个相互独立的请求并发发起，降低单部作品的特征补全等待时间；
     * 抓取的数据与串行完全一致（零质量损失）。人物 / 角色失败仍静默退化为空集。
     */
    private suspend fun fetchAndCache(sid: Int, key: String, priority: RequestPriority): WorkFeature? {
        return coroutineScope {
            val existing = workFeatureDao.getById(key)
            val dtoDeferred = async { (bangumi.getSubjectDto(sid, priority) as? AppResult.Success)?.data }
            val personsDeferred = async {
                safeNames { (bangumi.getSubjectPersons(sid, priority) as? AppResult.Success)?.data?.map { it.name } }
            }
            val charactersDeferred = async {
                (bangumi.getSubjectCharacters(sid, priority) as? AppResult.Success)?.data.orEmpty()
            }

            val dto = dtoDeferred.await() ?: run {
                // 条目本身失败：取消尚在进行的人物 / 角色请求，返回 null（不落库）。
                personsDeferred.cancel()
                charactersDeferred.cancel()
                return@coroutineScope null
            }
            val staff = personsDeferred.await()
            val characterDtos = charactersDeferred.await()
            val characters = characterDtos.map { it.name }.filter { it.isNotBlank() }.distinct()
            val cv = characterDtos.flatMap { c -> c.actors.map { it.name } }.filter { it.isNotBlank() }.distinct()

            val fetched = dto.toFeatureEntity(key, staff, characters, cv)
            val entity = if (existing?.let(::hasUsableBaseData) == true) {
                fetched.copy(
                    tagCountsJson = existing.tagCountsJson,
                    baseDataUpdatedAt = existing.baseDataUpdatedAt,
                )
            } else {
                fetched
            }
            workFeatureDao.upsert(entity)
            entity.toDomain()
        }
    }

    private suspend fun fetchBasicAndCache(
        sid: Int,
        key: String,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): WorkFeature? {
        val dto = (bangumi.getSubjectDto(sid, priority) as? AppResult.Success)?.data
            ?: return null
        val existing = workFeatureDao.getById(key)
        val fetched = dto.toFeatureEntity(key, emptyList(), emptyList(), emptyList())
        // 只刷新标签和数值；已有的 Staff / 角色 / 声优保留，不因补基础资料而丢失。
        val entity = if (existing?.completeness == WorkFeatureCompleteness.EXTENDED.name) {
            fetched.copy(
                staff = existing.staff,
                characters = existing.characters,
                cv = existing.cv,
                extendedDataUpdatedAt = existing.extendedDataUpdatedAt,
            )
        } else {
            fetched.copy(extendedDataUpdatedAt = null, completeness = WorkFeatureCompleteness.BASIC.name)
        }
        workFeatureDao.upsert(entity)
        return entity.toDomain()
    }

    private inline fun safeNames(block: () -> List<String>?): List<String> = try {
        block()
            .orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun isFresh(entity: WorkFeatureEntity): Boolean =
        entity.completeness == WorkFeatureCompleteness.EXTENDED.name &&
            System.currentTimeMillis() - (entity.extendedDataUpdatedAt ?: 0L) < TTL_MILLIS

    private fun hasUsableBaseData(entity: WorkFeatureEntity): Boolean = entity.completeness in STABLE_COMPLETENESS &&
        hasAnyBaseData(entity)

    private fun hasAnyBaseData(entity: WorkFeatureEntity): Boolean =
        entity.tagCountsJson.isNotBlank() && entity.baseDataUpdatedAt > 0L

    // --- mapping -----------------------------------------------------------

    private fun BangumiSubjectDto.toFeatureEntity(
        key: String,
        staff: List<String>,
        characters: List<String>,
        cv: List<String>,
    ): WorkFeatureEntity {
        val tagDtos = tags.filter { it.name.isNotBlank() }.map { TagCountDto(it.name, it.count.coerceAtLeast(0)) }
        val now = System.currentTimeMillis()
        return WorkFeatureEntity(
            subjectId = key,
            tagCountsJson = JSON.encodeToString(TAG_LIST_SERIALIZER, tagDtos),
            staff = staff,
            characters = characters,
            cv = cv,
            bangumiScore = rating?.score?.takeIf { it > 0f } ?: 0f,
            bangumiVotes = rating?.total?.coerceAtLeast(0) ?: 0,
            eps = (eps ?: totalEpisodes ?: 0).coerceAtLeast(0),
            durationMin = 0,
            platform = platform?.takeIf { it.isNotBlank() },
            mediaType = mediaTypeOf(type).name,
            titles = listOfNotNull(
                name.takeIf { it.isNotBlank() },
                nameCn.takeIf { it.isNotBlank() },
            ).distinct(),
            baseDataUpdatedAt = now,
            extendedDataUpdatedAt = now,
            completeness = WorkFeatureCompleteness.EXTENDED.name,
            updatedAt = now,
            coverUrl = images?.large ?: images?.common,
            airDate = date?.takeIf { it.isNotBlank() },
            ratingHistogram = encodeRatingHistogram(rating?.histogram().orEmpty()),
        )
    }

    private fun WorkFeature.toEntity(existing: WorkFeatureEntity?): WorkFeatureEntity {
        val tagDtos = tagCounts.map { TagCountDto(it.name, it.count.coerceAtLeast(0)) }
        val incomingTagsJson = JSON.encodeToString(TAG_LIST_SERIALIZER, tagDtos)
        val now = baseDataUpdatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        return mergeLightweightFeature(this, existing, incomingTagsJson, now)
    }

    private fun WorkFeatureEntity.toDomain(): WorkFeature {
        val tags = runCatchingCancellable { JSON.decodeFromString(TAG_LIST_SERIALIZER, tagCountsJson) }
            .getOrDefault(emptyList())
            .map { TagCount(it.name, it.count) }
        return WorkFeature(
            subjectId = subjectId,
            tagCounts = tags,
            staff = staff,
            characters = characters,
            cv = cv,
            bangumiScore = bangumiScore.takeIf { it > 0f },
            bangumiVotes = bangumiVotes,
            eps = eps,
            durationMin = durationMin,
            platform = platform,
            mediaType = mediaType?.let { MediaType.fromStorage(it) },
            titleVariants = titles,
            completeness = runCatchingCancellable { WorkFeatureCompleteness.valueOf(completeness) }
                .getOrDefault(WorkFeatureCompleteness.BASIC),
            baseDataUpdatedAt = baseDataUpdatedAt,
            extendedDataUpdatedAt = extendedDataUpdatedAt,
            updatedAt = updatedAt,
            coverUrl = coverUrl,
            airDate = airDate,
            ratingHistogram = decodeRatingHistogram(ratingHistogram),
        )
    }

    /**
     * 使用共享的 [mapBangumiMediaType] 映射 Bangumi 类型，确保音乐和真人条目不会进入动画候选池。
     */
    private fun mediaTypeOf(type: Int?): MediaType = mapBangumiMediaType(type)

    /** 持久化用标签计数 DTO（避免给领域 [TagCount] 加序列化注解）。 */
    @Serializable
    private data class TagCountDto(val name: String, val count: Int)

    private companion object {
        /** 特征缓存有效期：14 天（社区标签/评分变化缓慢，过期再联网刷新）。 */
        const val TTL_MILLIS: Long = 14L * 24 * 3600 * 1000

        /**
         * 批量联网补齐特征的并发作品数上限。每部内部再并发 3 个请求（条目/人物/角色），
         * 故峰值并发约 [FETCH_CONCURRENCY] × 3；取 4 在「显著提速」与「不过度冲击 Bangumi 反代」间折中。
         */
        const val FETCH_CONCURRENCY: Int = 4
        const val BASIC_FETCH_CONCURRENCY: Int = 2

        val STABLE_COMPLETENESS = setOf(
            WorkFeatureCompleteness.BASIC.name,
            WorkFeatureCompleteness.EXTENDED.name,
        )

        val JSON = Json { ignoreUnknownKeys = true }
        val TAG_LIST_SERIALIZER = ListSerializer(TagCountDto.serializer())
    }
}

/**
 * 合并一份「轻量」特征（收藏列表内嵌的精简条目、详情页顺带写入的基础资料）。
 *
 * 只升级不降级：收藏列表内嵌的条目只有前 10 个标签、没有评分人数，已经有经条目详情校验的
 * 完整标签（BASIC / EXTENDED）时保留原有标签和完整度；评分、集数等数值只在新数据有效时才覆盖。
 */
@Suppress("CyclomaticComplexMethod") // 逐字段「新值有效才覆盖」，拆开反而难读
internal fun mergeLightweightFeature(
    feature: WorkFeature,
    existing: WorkFeatureEntity?,
    incomingTagsJson: String,
    now: Long,
): WorkFeatureEntity {
    val keepExtended = existing?.completeness == WorkFeatureCompleteness.EXTENDED.name
    val existingStable = existing != null && existing.completeness in STABLE_NAMES &&
        existing.tagCountsJson.isNotBlank()
    val incomingWeaker = feature.completeness == WorkFeatureCompleteness.EMBEDDED
    val keepBase = existingStable && (incomingWeaker || existing?.tagCountsJson == incomingTagsJson)
    val incomingScore = feature.bangumiScore?.takeIf { it > 0f }
    return WorkFeatureEntity(
        subjectId = feature.subjectId,
        tagCountsJson = if (keepBase) existing!!.tagCountsJson else incomingTagsJson,
        staff = if (keepExtended) existing!!.staff else feature.staff,
        characters = if (keepExtended) existing!!.characters else feature.characters,
        cv = if (keepExtended) existing!!.cv else feature.cv,
        bangumiScore = incomingScore ?: existing?.bangumiScore ?: 0f,
        bangumiVotes = feature.bangumiVotes.takeIf { it > 0 } ?: existing?.bangumiVotes ?: 0,
        eps = feature.eps.takeIf { it > 0 } ?: existing?.eps ?: 0,
        durationMin = feature.durationMin.takeIf { it > 0 } ?: existing?.durationMin ?: 0,
        platform = feature.platform ?: existing?.platform,
        mediaType = feature.mediaType?.name ?: existing?.mediaType,
        titles = feature.titleVariants.ifEmpty { existing?.titles.orEmpty() },
        baseDataUpdatedAt = if (keepBase) existing!!.baseDataUpdatedAt else now,
        extendedDataUpdatedAt = existing?.extendedDataUpdatedAt,
        completeness = if (keepBase) existing!!.completeness else feature.completeness.name,
        updatedAt = now,
        coverUrl = feature.coverUrl ?: existing?.coverUrl,
        airDate = feature.airDate ?: existing?.airDate,
        ratingHistogram = encodeRatingHistogram(
            feature.ratingHistogram,
        ).ifEmpty { existing?.ratingHistogram.orEmpty() },
    )
}

private const val HISTOGRAM_BINS = 10

/** 评分分布存成逗号分隔的 10 个数；不是 10 档或全为 0 时存空串（表示没有）。 */
internal fun encodeRatingHistogram(bins: List<Int>): String =
    if (bins.size == HISTOGRAM_BINS && bins.any { it > 0 }) bins.joinToString(",") else ""

internal fun decodeRatingHistogram(text: String): List<Int> {
    val parts = text.split(',')
    val bins = parts.mapNotNull { it.trim().toIntOrNull() }
    return if (parts.size == HISTOGRAM_BINS && bins.size == HISTOGRAM_BINS) bins else emptyList()
}

private val STABLE_NAMES = setOf(WorkFeatureCompleteness.BASIC.name, WorkFeatureCompleteness.EXTENDED.name)

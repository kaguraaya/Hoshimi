package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.missingRatingEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.local.mapper.toSourceRef
import com.acgcompass.data.remote.bangumi.BangumiSubjectBundle
import com.acgcompass.data.remote.bangumi.toBasicWorkFeature
import com.acgcompass.domain.matching.ResolvedLink
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.matching.resolveLink
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkIds
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.taste.WorkFeatureRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * 作品相关的写库操作：搜索 / 榜单命中落库并按跨源聚类写源链接、Bangumi 条目落库、各源评分行与公共缓存登记。
 * 搜索、评分刷新、公共缓存补充、跨源补齐都经这里写，保证主键规则和「手动匹配不被覆盖」的语义一致。
 */
@Singleton
@Suppress("LongParameterList")
class WorkRowWriter @Inject constructor(
    private val workDao: WorkDao,
    private val cacheDao: CacheDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val workTagWriter: WorkTagWriter,
    private val workFeatureRepository: WorkFeatureRepository,
) {
    /**
     * 把命中作品与其源链接写入 Room（upsert）。
     *
     * 源链接经 [resolveLink] 解析：若本地已存在且被用户手动纠正（`userOverridden=true`），
     * 则保留用户选择、不被本次远程结果覆盖；作品的 `createdAt` 在重复 upsert 时保留。
     */
    suspend fun persistMatches(matches: List<WorkMatch>) {
        if (matches.isEmpty()) return
        val now = System.currentTimeMillis()

        val entities = matches.map { match ->
            val createdAt = workDao.getById(match.work.id)?.createdAt ?: now
            match.work.toEntity(createdAt = createdAt, updatedAt = now)
        }
        workDao.upsertAll(entities)
        // 同步持久化作品社区标签（tags+work_tags 连接表，WorkEntity 不含 tags）。经共享
        // WorkTagWriter 写入，与个人收藏同步路径用一致的标签主键规则，修复候选池/题材筛选/今晚看什么标签恒空。
        workTagWriter.persist(matches.map { it.work })

        // 跨源合并——把同一簇内每个来源的链接都指向「代表作品 id」，使详情页 aggregateRatings
        // 能汇总多平台评分；通用聚类规则见 domain/matching/CrossSourceMerge（无样例硬编码）。
        val clusters = com.acgcompass.domain.matching.clusterMatches(matches)
        clusters.forEach { cluster ->
            val representative = com.acgcompass.domain.matching.representativeOf(cluster)
            val repId = representative.work.id
            cluster.forEach { match ->
                val itemId = WorkIds.sourceItemId(match.work.id, match.sourceTag)
                val recomputed = ResolvedLink(
                    sourceRef = SourceRef(
                        sourceId = match.sourceTag,
                        sourceItemId = itemId,
                        matchConfidence = match.matchConfidence,
                        userOverridden = false,
                    ),
                    userOverridden = false,
                )
                val existing = sourceLinkDao.getBySourceItem(match.sourceTag.name, itemId)
                val resolved = existing?.toSourceRef()
                    ?.let { current ->
                        resolveLink(
                            current = ResolvedLink(current, existing.userOverridden),
                            recomputed = recomputed,
                        )
                    }
                    ?: recomputed
                sourceLinkDao.upsert(
                    resolved.sourceRef.toEntity(
                        id = existing?.id ?: WorkRowIds.linkId(repId, match.sourceTag, itemId),
                        workId = repId,
                        linkedAt = now,
                    ),
                )
            }
        }
        cacheDao.upsertEntries(
            clusters.map { cluster ->
                val representative = com.acgcompass.domain.matching.representativeOf(cluster)
                val work = representative.work
                PublicCacheEntryEntity(
                    workId = work.id,
                    originSource = representative.sourceTag.name,
                    qualityScore = representative.matchConfidence,
                    estimatedBytes = estimateMetadataBytes(work),
                    cachedAt = now,
                    lastAccessedAt = now,
                    protected = cacheDao.isWorkProtected(work.id),
                )
            },
        )
    }

    suspend fun persistBangumiBundle(workId: String, bundle: BangumiSubjectBundle) {
        val now = System.currentTimeMillis()
        val current = workDao.getById(workId)
        val updated = if (current == null) {
            bundle.work.copy(id = workId).toEntity(createdAt = now, updatedAt = now)
        } else if (sourceIdFromName(current.primarySource) == SourceId.BANGUMI) {
            bundle.work.copy(id = workId).toEntity(createdAt = current.createdAt, updatedAt = now).copy(
                status = bundle.work.status.takeUnless {
                    it == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN
                }?.name ?: current.status,
            )
        } else {
            localizeFromBangumi(current, bundle.work, now)
        }
        workDao.upsert(updated)
        workTagWriter.persist(listOf(bundle.work.copy(id = workId)))
        // 作品资料按 Bangumi 条目号存（口味画像、评分都按条目号查），本地作品编号可能是别的平台的。
        bundle.subject.toBasicWorkFeature(updatedAt = now)?.let { feature ->
            workFeatureRepository.seedLightweight(listOf(feature))
        }
        val rating = bundle.rating
        if (rating == null) {
            ratingDao.upsert(
                missingRatingEntity(
                    id = WorkRowIds.ratingId(workId, SourceId.BANGUMI),
                    workId = workId,
                    sourceId = SourceId.BANGUMI,
                    fetchedAt = now,
                ),
            )
            registerDetailCacheWork(workId)
        } else {
            persistRatingResult(workId, SourceId.BANGUMI, AppResult.Success(rating))
        }
    }

    private fun localizeFromBangumi(current: WorkEntity, bangumiWork: Work, now: Long): WorkEntity = current.copy(
        canonicalTitle = (bangumiWork.titles.cn ?: bangumiWork.titles.canonical)
            .ifBlank { current.canonicalTitle },
        titleCn = bangumiWork.titles.cn ?: current.titleCn,
        titleJa = bangumiWork.titles.ja ?: current.titleJa,
        aliases = (current.aliases + current.canonicalTitle + bangumiWork.titles.aliases)
            .filter(String::isNotBlank)
            .distinct(),
        year = bangumiWork.year ?: current.year,
        status = bangumiWork.status.takeUnless {
            it == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN
        }?.name ?: current.status,
        episodes = bangumiWork.units.episodes ?: current.episodes,
        coverUrl = bangumiWork.coverUrl ?: current.coverUrl,
        summary = bangumiWork.summary?.takeIf(String::isNotBlank) ?: current.summary,
        airDate = bangumiWork.airDate ?: current.airDate,
        updatedAt = now,
    )

    suspend fun persistRatingResult(
        workId: String,
        source: SourceId,
        result: AppResult<com.acgcompass.domain.model.RatingEntry>?,
    ) {
        val persisted = when (result) {
            is AppResult.Success -> {
                ratingDao.upsert(
                    result.data.toEntity(
                        id = WorkRowIds.ratingId(workId, source),
                        workId = workId,
                        sourceId = source,
                    ),
                )
                true
            }
            is AppResult.Failure -> if (
                result.error is AppError.NotFound || result.error is AppError.FieldMissing
            ) {
                ratingDao.upsert(
                    missingRatingEntity(
                        id = WorkRowIds.ratingId(workId, source),
                        workId = workId,
                        sourceId = source,
                    ),
                )
                true
            } else {
                recordTransientFailure(result.error, source)
                false
            }
            null -> false
        }
        if (persisted) registerDetailCacheWork(workId)
    }

    private suspend fun registerDetailCacheWork(workId: String) {
        val entity = workDao.getById(workId) ?: return
        val work = entity.toDomain()
        val now = System.currentTimeMillis()
        cacheDao.upsertEntries(
            listOf(
                PublicCacheEntryEntity(
                    workId = workId,
                    originSource = work.primarySource.name,
                    qualityScore = 1f,
                    estimatedBytes = estimateMetadataBytes(work),
                    cachedAt = now,
                    lastAccessedAt = now,
                    protected = cacheDao.isWorkProtected(workId),
                ),
            ),
        )
    }

    suspend fun persistSourceLink(workId: String, source: SourceId, sourceItemId: String, confidence: Double) {
        sourceLinkDao.upsert(
            SourceRef(
                sourceId = source,
                sourceItemId = sourceItemId,
                matchConfidence = confidence.toFloat(),
                userOverridden = false,
            ).toEntity(
                id = WorkRowIds.linkId(workId, source, sourceItemId),
                workId = workId,
                linkedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 写入一批带 Bangumi 评分的榜单 / 放送表条目的评分行（没有评分的条目跳过）。 */
    suspend fun upsertRatings(source: SourceId, items: List<Pair<WorkMatch, RatingEntry?>>) {
        items.forEach { (match, entry) ->
            if (entry != null) {
                ratingDao.upsert(
                    entry.toEntity(
                        id = WorkRowIds.ratingId(match.work.id, source),
                        workId = match.work.id,
                        sourceId = source,
                    ),
                )
            }
        }
    }
}

/** 源链接与评分行的稳定主键。 */
internal object WorkRowIds {
    /** 稳定的源链接主键：`workId:sourceId:sourceItemId`（同一三元组重复 upsert 即覆盖）。 */
    fun linkId(workId: String, sourceId: SourceId, sourceItemId: String): String =
        "$workId:${sourceId.name}:$sourceItemId"

    /** 稳定的评分行主键：`workId:sourceId`（每源单行，刷新即覆盖）。 */
    fun ratingId(workId: String, sourceId: SourceId): String = "$workId:${sourceId.name}"
}

/** 从持久化来源名解析 [SourceId]；未知返回 `null`（不臆造）。 */
internal fun sourceIdFromName(raw: String?): SourceId? = SourceId.entries.firstOrNull { it.name == raw }

/** 一部作品可用于标题比对的全部标题（主标题、日文、罗马音、英文、别名），去掉空白。 */
internal fun candidateTitles(match: WorkMatch): List<String> = buildList {
    add(match.work.titles.canonical)
    match.work.titles.ja?.let(::add)
    match.work.titles.romaji?.let(::add)
    match.work.titles.en?.let(::add)
    addAll(match.work.titles.aliases)
}.filter { it.isNotBlank() }

package com.acgcompass.data.repository

import androidx.room.withTransaction
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.DetailCacheEntity
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.VndbRankingCacheEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.missingRatingEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSearchFilterDto
import com.acgcompass.data.remote.bangumi.BangumiSubjectType
import com.acgcompass.data.remote.vndb.VndbRankingRequest
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.matching.allTitleVariants
import com.acgcompass.domain.matching.visualNovelMatchConfidence
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.VisualNovelRepository
import com.acgcompass.domain.visualnovel.VisualNovelCachePolicy
import com.acgcompass.domain.visualnovel.VisualNovelContentPolicy
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelDetailSnapshot
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import com.acgcompass.domain.visualnovel.VisualNovelPage
import com.acgcompass.domain.visualnovel.VisualNovelSort
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

@Singleton
class VisualNovelRepositoryImpl @Inject constructor(
    private val remote: VndbRemoteDataSource,
    private val bangumi: BangumiRemoteDataSource,
    private val database: AcgCompassDatabase,
    private val workDao: WorkDao,
    private val ratingDao: RatingDao,
    private val sourceLinkDao: SourceLinkDao,
    private val cacheDao: CacheDao,
    private val rankingDao: VndbRankingCacheDao,
    private val tagWriter: WorkTagWriter,
    private val settings: SettingsDataStore,
    private val json: Json,
) : VisualNovelRepository {
    override suspend fun featured(
        sort: VisualNovelSort,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
        randomSeed: Long,
    ): AppResult<VisualNovelPage> {
        val scope = visualNovelFeaturedScope(sort, randomSeed)
        return loadPage(scope, page, forceRefresh) {
            remote.getRanking(VndbRankingRequest(sort, page, pageSize, randomSeed))
        }
    }

    override suspend fun search(
        query: String,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
    ): AppResult<VisualNovelPage> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val scope = "$SEARCH_SCOPE:${sha256(normalized)}"
        return loadPage(scope, page, forceRefresh) { remote.searchPage(query.trim(), page, pageSize) }
    }

    override suspend fun detail(id: String, forceRefresh: Boolean): AppResult<VisualNovelDetail> {
        val now = System.currentTimeMillis()
        val allowAdult = settings.showAdultContent.first()
        val cachedRow = cacheDao.getDetail(id, SourceId.VNDB.name, DETAIL_SECTION)
        val cached = cachedRow?.let { row ->
            runCatching { json.decodeFromString(VisualNovelDetail.serializer(), row.payloadJson) }.getOrNull()
        }
        val cachedBlocked = cached != null && !VisualNovelContentPolicy.canDisplay(cached.adult, allowAdult)
        val cachedFresh = !forceRefresh && cached != null && cachedRow?.expiresAt?.let { it > now } == true
        return when {
            cachedBlocked -> AppResult.Failure(AppError.FieldMissing())
            cachedFresh -> AppResult.Success(checkNotNull(cached))
            else -> when (val result = remote.getVnDetail(id)) {
                is AppResult.Failure -> cached?.let { AppResult.Success(it) } ?: result
                is AppResult.Success -> {
                    val enriched = addBangumiRating(result.data)
                    persistDetail(enriched, now)
                    AppResult.Success(enriched)
                }
            }
        }
    }

    override suspend fun cachedDetail(id: String): VisualNovelDetailSnapshot? {
        val row = cacheDao.getDetail(id, SourceId.VNDB.name, DETAIL_SECTION)
        val detail = row?.let {
            runCatching {
                json.decodeFromString(VisualNovelDetail.serializer(), it.payloadJson)
            }.getOrNull()
        }
        val allowAdult = settings.showAdultContent.first()
        return detail
            ?.takeIf { VisualNovelContentPolicy.canDisplay(it.adult, allowAdult) }
            ?.let {
                VisualNovelDetailSnapshot(
                    detail = it,
                    stale = checkNotNull(row).expiresAt <= System.currentTimeMillis(),
                )
            }
    }

    override suspend fun producerWorks(
        producerId: String,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
    ): AppResult<VisualNovelPage> = loadPage(
        scope = "$PRODUCER_SCOPE:$producerId",
        page = page,
        forceRefresh = forceRefresh,
    ) {
        remote.getByDeveloper(producerId, page, pageSize)
    }

    private suspend fun loadPage(
        scope: String,
        page: Int,
        forceRefresh: Boolean,
        fetch: suspend () -> AppResult<VisualNovelPage>,
    ): AppResult<VisualNovelPage> {
        val allowAdult = settings.showAdultContent.first()
        val cached = readCachedPage(scope, page, allowAdult)
        if (!forceRefresh && cached != null) return AppResult.Success(cached)

        return when (val result = fetch()) {
            is AppResult.Success -> {
                persistPage(scope, result.data)
                AppResult.Success(readCachedPage(scope, page, allowAdult) ?: result.data)
            }
            is AppResult.Failure ->
                cached
                    ?.let { AppResult.Success(it.copy(stale = true, refreshFailed = true)) }
                    ?: result
        }
    }

    private suspend fun readCachedPage(scope: String, page: Int, allowAdult: Boolean): VisualNovelPage? {
        val rows = rankingDao.getPage(scope, page).filter {
            VisualNovelContentPolicy.canDisplay(it.adult, allowAdult)
        }
        val items = rows.mapNotNull { row ->
            val work = workDao.getById(row.workId)?.toDomain() ?: return@mapNotNull null
            VisualNovelListItem(
                work = work,
                rating = row.rating,
                voteCount = row.voteCount,
                adult = row.adult,
                previewUrl = row.previewUrl ?: work.coverUrl,
                originalUrl = row.originalUrl ?: work.coverUrl,
            )
        }
        return items.takeIf(List<*>::isNotEmpty)?.let {
            val cachedAt = rows.minOf { row -> row.cachedAt }
            VisualNovelPage(
                items = items,
                page = page,
                hasMore = rows.first().hasMore,
                fromCache = true,
                stale = VisualNovelCachePolicy.rankingIsStale(cachedAt, System.currentTimeMillis()),
                refreshedAt = cachedAt,
            )
        }
    }

    private suspend fun persistPage(scope: String, page: VisualNovelPage) {
        val now = System.currentTimeMillis()
        val contentHash = VisualNovelCachePolicy.pageContentHash(page)
        val existingRows = rankingDao.getPage(scope, page.page)
        if (existingRows.isNotEmpty() && existingRows.first().contentHash == contentHash) {
            rankingDao.touchPage(scope, page.page, now)
            return
        }
        database.withTransaction {
            page.items.forEach { item ->
                persistVisualNovelPageItem(item, now, workDao, ratingDao, cacheDao)
            }
            val rows = page.items.mapIndexed { position, item ->
                VndbRankingCacheEntity(
                    scopeKey = scope,
                    page = page.page,
                    position = position,
                    workId = item.work.id,
                    previewUrl = item.previewUrl,
                    originalUrl = item.originalUrl,
                    rating = item.rating,
                    voteCount = item.voteCount,
                    adult = item.adult,
                    cachedAt = now,
                    contentHash = contentHash,
                    hasMore = page.hasMore,
                )
            }
            rankingDao.replacePageIfChanged(scope, page.page, rows, contentHash, now)
        }
        tagWriter.persist(page.items.map { it.work })
    }

    private suspend fun addBangumiRating(detail: VisualNovelDetail): VisualNovelDetail {
        val best = findBangumiMatch(bangumi, detail) ?: return detail
        val candidate = best.first
        val confidence = best.second
        val subjectId = candidate.work.id.toIntOrNull() ?: return detail
        val now = System.currentTimeMillis()
        persistBangumiLink(sourceLinkDao, detail.id, subjectId, confidence, now)
        val bangumiWork = (bangumi.getSubject(subjectId) as? AppResult.Success)?.data ?: candidate.work
        val rating = fetchAndPersistBangumiRating(bangumi, ratingDao, detail.id, subjectId, now)
        return detail.copy(
            title = bangumiWork.titles.cn
                ?.takeIf { it.isNotBlank() }
                ?: bangumiWork.titles.canonical.takeIf { it.isNotBlank() }
                ?: detail.title,
            bangumiRating = rating?.score,
            bangumiVoteCount = rating?.voteCount ?: 0,
            bangumiSubjectId = subjectId,
            bangumiSummary = bangumiWork.summary?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun persistDetail(detail: VisualNovelDetail, now: Long) {
        val payload = json.encodeToString(VisualNovelDetail.serializer(), detail)
        val hash = sha256(payload)
        val current = cacheDao.getDetail(detail.id, SourceId.VNDB.name, DETAIL_SECTION)
        if (current?.contentHash != hash || current.expiresAt <= now) {
            cacheDao.upsertDetail(
                DetailCacheEntity(
                    workId = detail.id,
                    source = SourceId.VNDB.name,
                    section = DETAIL_SECTION,
                    payloadJson = payload,
                    contentHash = hash,
                    fetchedAt = now,
                    expiresAt = now + VisualNovelCachePolicy.DETAIL_TTL_MILLIS,
                    schemaVersion = DETAIL_SCHEMA_VERSION,
                ),
            )
        }
        val work = workDao.getById(detail.id)
        workDao.upsert(
            if (work == null) {
                WorkEntity(
                    id = detail.id,
                    canonicalTitle = detail.title,
                    titleJa = detail.originalTitle,
                    titleRomaji = null,
                    titleEn = null,
                    aliases = emptyList(),
                    mediaType = MediaType.VN.name,
                    year = detail.released?.take(4)?.toIntOrNull(),
                    status = com.acgcompass.domain.model.ReleaseStatus.UNKNOWN.name,
                    episodes = null,
                    episodeMinutes = null,
                    volumes = null,
                    estPlayMinutes = detail.lengthMinutes,
                    coverUrl = detail.cover?.thumbnailUrl ?: detail.cover?.url,
                    primarySource = SourceId.VNDB.name,
                    completionCostBucket = null,
                    summary = detail.bangumiSummary ?: detail.description,
                    airDate = detail.released,
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                work.copy(
                    canonicalTitle = detail.title.takeIf { it.isNotBlank() } ?: work.canonicalTitle,
                    summary = detail.bangumiSummary ?: detail.description ?: work.summary,
                    airDate = detail.released ?: work.airDate,
                    estPlayMinutes = detail.lengthMinutes ?: work.estPlayMinutes,
                    coverUrl = detail.cover?.thumbnailUrl ?: detail.cover?.url ?: work.coverUrl,
                    updatedAt = now,
                )
            },
        )
    }

    private companion object {
        const val SEARCH_SCOPE = "SEARCH"
        const val PRODUCER_SCOPE = "PRODUCER"
        const val DETAIL_SECTION = "VNDB_FULL"
        const val DETAIL_SCHEMA_VERSION = 1
    }
}

internal fun visualNovelFeaturedScope(sort: VisualNovelSort, randomSeed: Long): String = buildString {
    append("FEATURED").append(':').append(sort.name)
    if (sort == VisualNovelSort.RANDOM) append(':').append(randomSeed)
}

private suspend fun persistVisualNovelPageItem(
    item: VisualNovelListItem,
    now: Long,
    workDao: WorkDao,
    ratingDao: RatingDao,
    cacheDao: CacheDao,
) {
    val existing = workDao.getById(item.work.id)
    workDao.upsert(item.work.toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now))
    val rating = item.rating?.takeIf { it > 0f && item.voteCount > 0 }
    val ratingEntity = if (rating != null) {
        RatingEntry(rating, item.voteCount).toEntity(
            id = "${item.work.id}:${SourceId.VNDB.name}",
            workId = item.work.id,
            sourceId = SourceId.VNDB,
            fetchedAt = now,
        )
    } else {
        missingRatingEntity(
            id = "${item.work.id}:${SourceId.VNDB.name}",
            workId = item.work.id,
            sourceId = SourceId.VNDB,
            fetchedAt = now,
        )
    }
    ratingDao.upsert(ratingEntity)
    cacheDao.upsertEntries(
        listOf(
            PublicCacheEntryEntity(
                workId = item.work.id,
                originSource = SourceId.VNDB.name,
                qualityScore = item.rating ?: 0f,
                estimatedBytes = estimateMetadataBytes(item),
                cachedAt = now,
                lastAccessedAt = now,
                protected = cacheDao.isWorkProtected(item.work.id),
            ),
        ),
    )
}

private suspend fun findBangumiMatch(
    bangumi: BangumiRemoteDataSource,
    detail: VisualNovelDetail,
): Pair<WorkMatch, Double>? {
    val candidates = (
        bangumi.searchSubjects(
            keyword = detail.title,
            filter = BangumiSearchFilterDto(type = listOf(BangumiSubjectType.GAME), nsfw = false),
            limit = 10,
        ) as? AppResult.Success
        )?.data.orEmpty()
    val year = detail.released?.take(RELEASE_YEAR_LENGTH)?.toIntOrNull()
    return candidates
        .filter { it.work.mediaType == MediaType.GAME || it.work.mediaType == MediaType.VN }
        .map { candidate ->
            val confidence = candidate.work.allTitleVariants().maxOfOrNull { title ->
                visualNovelMatchConfidence(
                    candidate = title,
                    query = detail.title,
                    yearMatch = yearsMatch(year, candidate.work.year),
                )
            } ?: 0.0
            candidate to confidence
        }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= BANGUMI_MATCH_THRESHOLD }
}

private suspend fun persistBangumiLink(
    sourceLinkDao: SourceLinkDao,
    detailId: String,
    subjectId: Int,
    confidence: Double,
    now: Long,
) {
    sourceLinkDao.upsert(
        SourceLinkEntity(
            id = "$detailId:${SourceId.BANGUMI.name}",
            workId = detailId,
            sourceId = SourceId.BANGUMI.name,
            sourceItemId = subjectId.toString(),
            matchConfidence = confidence.toFloat(),
            userOverridden = false,
            linkedAt = now,
        ),
    )
}

private suspend fun fetchAndPersistBangumiRating(
    bangumi: BangumiRemoteDataSource,
    ratingDao: RatingDao,
    detailId: String,
    subjectId: Int,
    now: Long,
): RatingEntry? {
    val ratingResult = bangumi.getSubjectRating(subjectId)
    val rating = (ratingResult as? AppResult.Success)?.data
    when (ratingResult) {
        is AppResult.Success -> ratingDao.upsert(
            ratingResult.data.toEntity(
                id = "$detailId:${SourceId.BANGUMI.name}",
                workId = detailId,
                sourceId = SourceId.BANGUMI,
                fetchedAt = now,
            ),
        )
        is AppResult.Failure -> if (
            ratingResult.error is AppError.NotFound || ratingResult.error is AppError.FieldMissing
        ) {
            ratingDao.upsert(
                missingRatingEntity(
                    id = "$detailId:${SourceId.BANGUMI.name}",
                    workId = detailId,
                    sourceId = SourceId.BANGUMI,
                    fetchedAt = now,
                ),
            )
        }
    }
    return rating
}

private fun yearsMatch(left: Int?, right: Int?): Boolean? = if (left != null && right != null) left == right else null

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun estimateMetadataBytes(item: VisualNovelListItem): Long = BASE_METADATA_BYTES + listOfNotNull(
    item.work.titles.canonical,
    item.work.titles.ja,
    item.work.titles.romaji,
    item.work.summary,
    item.work.coverUrl,
).sumOf { it.length * 2L }

private const val BASE_METADATA_BYTES = 512L
private const val BANGUMI_MATCH_THRESHOLD = 0.92
private const val RELEASE_YEAR_LENGTH = 4

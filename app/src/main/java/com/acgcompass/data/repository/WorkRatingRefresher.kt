package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.local.mapper.toRatingAggregate
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.data.sync.isTransient
import com.acgcompass.data.sync.sourceDisplayName
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkIds
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 作品各平台评分的读取与刷新：详情页打开时按缓存期限刷新、后台补评分、只读本地缓存的批量聚合，
 * 以及刷新结果写进「后台任务」页。缺失的平台保持「暂无数据」，绝不跨源回填。
 */
@Singleton
@Suppress("LongParameterList")
class WorkRatingRefresher @Inject constructor(
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val aggregateRatingsUseCase: AggregateRatingsUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val sourceQuota: com.acgcompass.core.network.SourceQuota,
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
    private val rowWriter: WorkRowWriter,
    private val ratingFetcher: SourceRatingFetcher,
    private val crossSourceLinker: CrossSourceLinker,
    private val dispatchers: DispatcherProvider,
) {
    private val profileAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun aggregateRatings(workId: String, forceRefresh: Boolean): AppResult<RatingAggregate> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 按作品源链接尽力刷新各源评分和资料；失败时沿用本地数据。
                val cachedRows = ratingDao.getByWork(workId)
                val now = System.currentTimeMillis()
                val tenraiMissing = cachedRows.none { it.sourceId == SourceId.TENRAI.name } &&
                    settingsDataStore.jikanEnabled.first()
                val stale = cachedRows.isEmpty() || tenraiMissing || cachedRows.any {
                    val ttl = if (it.missing) {
                        missingRatingTtl(workId)
                    } else {
                        DetailCacheTtl.COMMENTS_MILLIS
                    }
                    now - it.fetchedAt >= ttl
                }
                val needsProfile = needsFullProfile(workId, now)
                if (forceRefresh || stale || needsProfile) {
                    val counter = TransientFailureCounter()
                    withContext(counter) { refreshRatingsAndWork(workId, forceRefresh, refreshProfile = needsProfile) }
                    recordDetailRatingOutcome(workId, counter.failedSources)
                }
                // 单一可信源：从 Room 读取每源评分行，聚合（缺失源保留 null，绝不回填）。
                val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
                aggregateRatingsUseCase(perSource)
            }
        }

    /** 「暂无评分」的缓存多久后再查：放送中或近一年的作品一天，其余一周。 */
    private suspend fun missingRatingTtl(workId: String): Long {
        val work = workDao.getById(workId)
        val currentYear = java.time.Year.now().value
        val recent = work != null &&
            (
                work.status == com.acgcompass.domain.model.ReleaseStatus.RELEASING.name ||
                    (work.year ?: 0) >= currentYear - 1
                )
        return if (recent) DetailCacheTtl.MISSING_RATING_RECENT_MILLIS else DetailCacheTtl.MISSING_RATING_MILLIS
    }

    /**
     * 刷新后仍然没有评分的补充平台各是什么情况：已关联但平台上暂无评分（附上次查询时间），还是没找到对应条目。
     * 写进「后台任务」页的说明，方便判断「暂无数据」是真没有还是没对上。
     */
    private suspend fun noDataNotes(workId: String): List<String> {
        val enabled = buildList {
            if (settingsDataStore.anilistEnabled.first()) add(SourceId.ANILIST)
            if (settingsDataStore.jikanEnabled.first()) add(SourceId.TENRAI)
        }
        val ratings = ratingDao.getByWork(workId).associateBy { it.sourceId }
        val links = sourceLinkDao.getByWork(workId).associateBy { it.sourceId }
        return enabled.mapNotNull { source ->
            val row = ratings[source.name]
            if (row != null && !row.missing) return@mapNotNull null
            val link = links[source.name]
            when {
                link == null -> "${source.name} 没找到对应条目"
                row == null -> "${source.name} 已关联 ${link.sourceItemId}，还没查到评分"
                else ->
                    "${source.name} 已关联 ${link.sourceItemId}，平台上暂无评分（${
                        java.text.SimpleDateFormat(
                            "M月d日 HH:mm",
                            java.util.Locale.ROOT,
                        ).format(java.util.Date(row.fetchedAt))
                    } 查询）"
            }
        }
    }

    /** 详情页刷新各平台评分的结果记到「后台任务」页：哪个平台没拿到、为什么。 */
    private suspend fun recordDetailRatingOutcome(workId: String, failed: Map<SourceId, AppError>) {
        val title = workDao.getById(workId)?.canonicalTitle ?: workId
        if (failed.isEmpty()) {
            val notes = noDataNotes(workId)
            val suffix = notes.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "；").orEmpty()
            taskRecorder.succeeded(BackgroundTaskKind.DETAIL_RATINGS, "《$title》各平台评分已更新$suffix")
        } else {
            val parts = failed.entries.joinToString("；") { (source, error) ->
                "${source.name} ${com.acgcompass.data.tasks.TaskRunRecorder.describe(error)}"
            }
            taskRecorder.failed(BackgroundTaskKind.DETAIL_RATINGS, "《$title》有平台的评分没拿到：$parts")
        }
    }

    suspend fun backfillRatings(workId: String): AppResult<RatingAggregate> = withContext(dispatchers.io) {
        runCatchingApp {
            refreshRatingsAndWork(workId, forceRefresh = false, priority = RequestPriority.BACKGROUND)
            aggregateRatingsUseCase(ratingDao.getByWork(workId).toRatingAggregate().perSource)
        }
    }

    suspend fun backfillRatingsOutcome(workId: String): RatingBackfillOutcome = backfillRatingsReport(workId).outcome

    /** 补评分并带出没拿到结果的平台（[TransientFailureCounter.failedSources]），供后台任务页说明原因。 */
    suspend fun backfillRatingsReport(workId: String): com.acgcompass.domain.model.RatingBackfillReport {
        val counter = TransientFailureCounter()
        val result = withContext(counter) { backfillRatings(workId) }
        val outcome = when {
            result is AppResult.Failure && result.error.isTransient() -> RatingBackfillOutcome.TRANSIENT_FAILURE
            result is AppResult.Failure -> RatingBackfillOutcome.FAILED
            counter.count > 0 -> RatingBackfillOutcome.TRANSIENT_FAILURE
            else -> RatingBackfillOutcome.CHECKED
        }
        val reason = when {
            result is AppResult.Failure -> "条目：${TaskRunRecorder.describe(result.error)}"
            outcome == RatingBackfillOutcome.TRANSIENT_FAILURE -> counter.failedSources.entries.firstOrNull()
                ?.let { (source, error) -> "${sourceDisplayName(source.name)}：${TaskRunRecorder.describe(error)}" }
            else -> null
        }
        return com.acgcompass.domain.model.RatingBackfillReport(
            outcome = outcome,
            failedSources = counter.failedSources.keys.mapTo(LinkedHashSet()) { it.name },
            reason = reason,
        )
    }

    /** 仅读本地缓存评分聚合，不触发网络刷新（发现页批量场景用，避免逐作品联网卡顿）。 */
    suspend fun aggregateRatingsCached(workId: String): RatingAggregate = withContext(dispatchers.io) {
        val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
        aggregateRatingsUseCase(perSource)
    }

    suspend fun aggregateRatingsCached(workIds: List<String>): Map<String, RatingAggregate> =
        withContext(dispatchers.io) {
            val rows = workIds.distinct().chunked(SQL_IN_CHUNK)
                .flatMap { ratingDao.getByWorks(it) }
                .groupBy { it.workId }
            workIds.associateWith { id ->
                aggregateRatingsUseCase(rows[id].orEmpty().toRatingAggregate().perSource)
            }
        }

    /**
     * 尽力刷新作品全部来源的评分，并使用主来源更新作品资料字段。
     * 任何失败都被吞掉以保持韧性（绝不崩溃，缺失即「暂无数据」）。
     */
    /**
     * 收藏同步、放送表等列表接口不带完整资料；本地作品缺简介、缺年份或只有小封面时补拉一次完整条目。
     * 同一作品 6 小时内只补一次，避免真没简介的条目反复请求。
     */
    private suspend fun needsFullProfile(workId: String, now: Long): Boolean {
        val incomplete = workDao.getById(workId)?.let { entity ->
            entity.summary.isNullOrBlank() || entity.year == null ||
                entity.coverUrl?.let(::isLowResBangumiCover) == true
        } ?: false
        val recentlyTried = profileAttempts[workId]?.let { now - it < DetailCacheTtl.COMMENTS_MILLIS } ?: false
        val needed = incomplete && !recentlyTried
        if (needed) profileAttempts[workId] = now
        return needed
    }

    /**
     * 刷新作品各来源的评分（和打开详情页时一样）。[priority] 为后台时所有请求让前台先走，
     * 并跳过非 Bangumi 主源作品的资料刷新（后台只补评分）。
     */
    private suspend fun refreshRatingsAndWork(
        workId: String,
        forceRefresh: Boolean,
        refreshProfile: Boolean = false,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ) {
        ensureWorkAvailable(workId, priority)
        val primarySource = workDao.getById(workId)?.let { sourceIdFromName(it.primarySource) }
        val sourcePairs = linkedSourcePairs(workId, primarySource)
        val bangumiPair = sourcePairs.firstOrNull { it.first == SourceId.BANGUMI }
        coroutineScope {
            listOfNotNull(
                async { refreshBangumiBundle(workId, bangumiPair, forceRefresh || refreshProfile, priority) },
                async { refreshLinkedRatings(workId, sourcePairs, forceRefresh, priority) },
                async { crossSourceLinker.enrich(workId, priority) },
                if (priority == RequestPriority.FOREGROUND) async { refreshPrimaryWork(workId, sourcePairs) } else null,
            ).forEach { it.await() }
        }
    }

    private suspend fun ensureWorkAvailable(workId: String, priority: RequestPriority) {
        if (workDao.getById(workId) != null) return
        val subjectId = workId.toIntOrNull() ?: return
        val fetched = bangumi.getSubjectBundle(subjectId, priority)
        if (fetched is AppResult.Success) {
            rowWriter.persistBangumiBundle(workId, fetched.data)
        }
    }

    private suspend fun linkedSourcePairs(workId: String, primarySource: SourceId?): List<Pair<SourceId, String>> {
        val linkedIds = sourceLinkDao.getByWork(workId)
            .mapNotNull { link -> sourceIdFromName(link.sourceId)?.let { it to link.sourceItemId } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.firstOrNull() }

        return buildList {
            primarySource?.let { source ->
                val itemId = linkedIds[source]
                    ?: workId.takeIf { source == SourceId.BANGUMI && it.toIntOrNull() != null }
                itemId?.let { add(source to it) }
            }
            linkedIds.forEach { (source, itemId) ->
                if (source != primarySource && itemId != null) add(source to itemId)
            }
        }
    }

    private suspend fun refreshLinkedRatings(
        workId: String,
        sourcePairs: List<Pair<SourceId, String>>,
        forceRefresh: Boolean,
        priority: RequestPriority,
    ) {
        val cachedRatings = ratingDao.getByWork(workId).associateBy { it.sourceId }
        val now = System.currentTimeMillis()
        val stalePairs = sourcePairs.filter { (source, _) ->
            if (source == SourceId.BANGUMI) return@filter false
            val cached = cachedRatings[source.name]
            val ttl = if (cached?.missing == true) {
                missingRatingTtl(workId)
            } else {
                DetailCacheTtl.COMMENTS_MILLIS
            }
            val stale = cached == null || now - cached.fetchedAt >= ttl
            // 前台打开详情时，这个源的额度快用完而本地已有它的评分：先用本地的，免得排队等额度、挤掉别的请求。
            val keepCached = priority == RequestPriority.FOREGROUND && !forceRefresh &&
                cached != null && !cached.missing && sourceQuota.isTight(source.toNetworkSource())
            (forceRefresh || stale) && !keepCached
        }
        coroutineScope {
            stalePairs.map { (source, itemId) ->
                async { rowWriter.persistRatingResult(workId, source, ratingFetcher.fetch(source, itemId, priority)) }
            }.forEach { it.await() }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun refreshPrimaryWork(workId: String, sourcePairs: List<Pair<SourceId, String>>) {
        val entity = workDao.getById(workId) ?: return
        val primary = sourceIdFromName(entity.primarySource) ?: return
        if (primary == SourceId.BANGUMI) return
        if (primary != SourceId.BANGUMI && sourcePairs.any { it.first == SourceId.BANGUMI }) return
        val primaryItemId = sourcePairs.firstOrNull { it.first == primary }?.second
            ?: fallbackSourceItemId(workId, primary)
            ?: return
        val refreshed: AppResult<Work>? = when (primary) {
            SourceId.BANGUMI -> null
            SourceId.ANILIST -> primaryItemId.toIntOrNull()?.let { anilist.getMedia(it) }
            SourceId.TENRAI -> primaryItemId.toIntOrNull()?.let { tenrai.getAnime(it) }
            SourceId.JIKAN -> null
            SourceId.MAL -> if (mal.isEnabled()) primaryItemId.toIntOrNull()?.let { mal.getAnime(it) } else null
            SourceId.VNDB -> vndb.getVn(primaryItemId)
        }
        if (refreshed is AppResult.Success) {
            val hasBangumiLink = sourceLinkDao.getByWork(workId).any { it.sourceId == SourceId.BANGUMI.name }
            if (hasBangumiLink) return
            val refreshedEntity = refreshed.data.toEntity(
                createdAt = entity.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            workDao.upsert(
                refreshedEntity.copy(
                    // 平台返回的作品 id 可能和本地这一行的写法不同（旧行没带来源前缀），写回原来这一行。
                    id = workId,
                    status = refreshed.data.status.takeUnless {
                        it == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN
                    }?.name ?: entity.status,
                ),
            )
        }
    }

    private suspend fun refreshBangumiBundle(
        workId: String,
        sourcePair: Pair<SourceId, String>?,
        forceRefresh: Boolean,
        priority: RequestPriority,
    ) {
        val subjectId = sourcePair?.second?.toIntOrNull() ?: return
        val cached = ratingDao.getByWorkAndSource(workId, SourceId.BANGUMI.name)
        val ttl = if (cached?.missing == true) {
            missingRatingTtl(workId)
        } else {
            DetailCacheTtl.COMMENTS_MILLIS
        }
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < ttl) return
        val result = bangumi.getSubjectBundle(subjectId, priority, fresh = forceRefresh)
        if (result is AppResult.Success) rowWriter.persistBangumiBundle(workId, result.data)
    }

    private fun fallbackSourceItemId(workId: String, source: SourceId): String? = when (source) {
        SourceId.BANGUMI -> workId.takeIf { it.toIntOrNull() != null }
        SourceId.VNDB -> workId.takeIf { !it.contains(':') }
        SourceId.ANILIST, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL ->
            WorkIds.sourceItemId(workId, source).takeIf { it.toIntOrNull() != null }
    }

    private companion object {
        /** `IN (...)` 查询每批的 id 数，避开 SQLite 变量上限。 */
        const val SQL_IN_CHUNK = 900
    }
}

/** 领域里的数据源对应到限速器用的网络来源。 */
private fun SourceId.toNetworkSource(): com.acgcompass.core.network.NetworkSource = when (this) {
    SourceId.BANGUMI -> com.acgcompass.core.network.NetworkSource.BANGUMI
    SourceId.ANILIST -> com.acgcompass.core.network.NetworkSource.ANILIST
    SourceId.TENRAI -> com.acgcompass.core.network.NetworkSource.TENRAI
    SourceId.JIKAN -> com.acgcompass.core.network.NetworkSource.JIKAN
    SourceId.MAL -> com.acgcompass.core.network.NetworkSource.MAL
    SourceId.VNDB -> com.acgcompass.core.network.NetworkSource.VNDB
}

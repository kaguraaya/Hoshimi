package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.toAppError
import com.acgcompass.core.network.DataSourceOrchestrator
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.SourceData
import com.acgcompass.core.network.SourceFetcher
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.RankingCacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkTagWithCategory
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import com.acgcompass.data.local.entity.RankingCacheEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.missingRatingEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.local.mapper.toEntryOrNull
import com.acgcompass.data.local.mapper.toRatingAggregate
import com.acgcompass.data.local.mapper.toSourceRef
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSubjectBundle
import com.acgcompass.data.remote.bangumi.toBasicWorkFeature
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.data.sync.CacheFillBatch
import com.acgcompass.data.sync.CacheFillSourcePager
import com.acgcompass.data.sync.PublicCacheFillCoordinator
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.cache.CacheSourceAllocation
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.matching.ResolvedLink
import com.acgcompass.domain.matching.decideMatch
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.matching.resolveLink
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.CacheFillController
import com.acgcompass.domain.repository.PublicCacheWorkRepository
import com.acgcompass.domain.repository.RankingPage
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.taste.WorkFeatureRepository
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * [WorkRepository] 的本地优先实现。
 *
 * **单一可信源 = Room**：
 * - [observeWork] 始终从 [WorkDao] 的 Flow 读取并映射为七态 [UiState]，离线可用、状态一致。
 * - [search] 经 [DataSourceOrchestrator] + [BangumiRemoteDataSource] 取数，用匹配器
 *   （[matchConfidence] / [decideMatch]）重算置信度并排序，再把命中作品 upsert 进 Room，
 *   使后续 UI 读取保持一致。
 * - [aggregateRatings] 以 Room 中每源 [com.acgcompass.domain.model.RatingEntry] 为输入，
 *   交 [AggregateRatingsUseCase] 聚合；缺失源标记为 `missing`，绝不跨源回填。
 * - [overrideMatch] 以 [ResolvedLink] / [resolveLink] 语义写入 `userOverridden=true`，
 *   后续同步不再自动改写该链接。
 *
 * **韧性**：所有远程调用都包裹在 [AppResult] 中，异常兜底为领域错误，
 * 绝不崩溃；字段缺失走「暂无数据」语义（[UiState.PartialMissing] / `missing`）。
 */
@Singleton
@Suppress("LongParameterList")
class WorkRepositoryImpl @Inject constructor(
    private val workDao: WorkDao,
    private val cacheDao: CacheDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val tagDao: TagDao,
    private val rankingCacheDao: RankingCacheDao,
    private val vndbRankingCacheDao: VndbRankingCacheDao,
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val orchestrator: DataSourceOrchestrator,
    private val aggregateRatingsUseCase: AggregateRatingsUseCase,
    private val dispatchers: DispatcherProvider,
    private val settingsDataStore: SettingsDataStore,
    private val workTagWriter: WorkTagWriter,
    private val cacheFillCoordinator: PublicCacheFillCoordinator,
    private val cacheFillSourcePager: CacheFillSourcePager,
    private val workFeatureRepository: WorkFeatureRepository,
) : WorkRepository, PublicCacheWorkRepository, CacheFillController {

    private val profileAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // --- observeWork -------------------------------------------------------

    override fun observeWork(workId: String): Flow<UiState<Work>> = combine(
        workDao.observeById(workId),
        tagDao.observeTagsForWork(workId),
    ) { entity, tagRows ->
        if (entity != null) cacheDao.touch(workId, System.currentTimeMillis())
        entity to tagRows
    }
        .map { (entity, tagRows) ->
            if (entity == null) {
                UiState.Empty(EMPTY_WORK_CTA)
            } else {
                val work = entity.toDomain(
                    tags = tagRows.map { row ->
                        Tag(
                            category = TagCategory.fromStorage(row.category) ?: TagCategory.CONTENT,
                            name = row.name,
                        )
                    },
                )
                // 字段级缺失（封面 / 年份 / 状态未知）→ PartialMissing，UI 显示「暂无数据」
                // 而非隐藏整块。
                if (work.hasMissingDisplayFields()) {
                    UiState.PartialMissing(work)
                } else {
                    UiState.Success(work)
                }
            }
        }
        .onStart { emit(UiState.Loading) }
        .catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(UiState.Error(throwable.toAppError()))
        }
        .flowOn(dispatchers.io)

    override fun observeWorks(): Flow<List<Work>> = mapWorks(workDao.observeAll())

    override fun observeRatings(workId: String): Flow<UiState<RatingAggregate>> = ratingDao.observeByWork(workId)
        .map { rows -> UiState.Success(aggregateRatingsUseCase(rows.toRatingAggregate().perSource)) }
        .flowOn(dispatchers.io)

    override fun observeWorkTags(workId: String): Flow<List<Tag>> = tagDao.observeTagsForWork(workId)
        .map { rows ->
            rows.map { row ->
                Tag(
                    category = TagCategory.fromStorage(row.category) ?: TagCategory.CONTENT,
                    name = row.name,
                )
            }
        }
        .flowOn(dispatchers.io)

    override fun observePublicCachedWorks(): Flow<List<Work>> = mapWorks(workDao.observePublicCached())

    private fun mapWorks(source: Flow<List<WorkEntity>>): Flow<List<Work>> = source
        .map { entities ->
            // 回填作品社区标签——候选池/题材筛选/今晚看什么依赖真实 tag，
            // 而 WorkEntity 不含 tags（存于连接表），故此处一次性 join 回填，避免列表恒空 tag。
            val tagsByWork = tagsForWorks(entities.map { it.id })
            entities.map { it.toDomain(tags = tagsByWork[it.id].orEmpty()) }
        }
        .flowOn(dispatchers.io)

    /** 批量读取作品社区标签并按 workId 分组（分批避开 SQLite 变量上限）。 */
    private suspend fun tagsForWorks(workIds: List<String>): Map<String, List<Tag>> {
        if (workIds.isEmpty()) return emptyMap()
        val rows: List<WorkTagWithCategory> = workIds.chunked(900).flatMap { tagDao.getTagsForWorks(it) }
        return rows.groupBy({ it.workId }) { row ->
            Tag(category = TagCategory.fromStorage(row.category) ?: TagCategory.CONTENT, name = row.name)
        }
    }

    // --- search ------------------------------------------------------------

    override suspend fun search(query: String): AppResult<List<WorkMatch>> =
        searchProgressive(query).lastOrNull() ?: AppResult.Success(emptyList())

    /**
     * 渐进式搜索：四个源并行，**哪个先回来就先发一版结果**，不必等最慢的源（通常是 VNDB）；
     * 全部回来后再补 Bangumi 匹配并发最终结果。最终结果与排序与一次性返回时完全一致。
     */
    override fun searchProgressive(query: String): Flow<AppResult<List<WorkMatch>>> = channelFlow {
        val merged = mutableListOf<WorkMatch>()
        val lock = Mutex()
        val sources: List<suspend () -> List<WorkMatch>> = listOf(
            { safeSearch { bangumi.searchSubjects(keyword = query) } },
            { safeSearch { anilist.searchMedia(keyword = query) } },
            { if (mal.isEnabled()) safeSearch { mal.searchAnime(keyword = query) } else emptyList() },
            { safeSearch { vndb.searchVn(keyword = query) } },
        )
        sources.map { source ->
            launch {
                val found = source()
                if (found.isEmpty()) return@launch
                val snapshot = lock.withLock {
                    merged += found
                    merged.toList()
                }
                send(AppResult.Success(rankSearchResults(query, snapshot).also { persistMatches(it) }))
            }
        }.joinAll()
        val all = lock.withLock { merged.toList() }
        val final = runCatchingApp { rankSearchResults(query, all + backfillBangumi(all)).also { persistMatches(it) } }
        send(final)
    }.flowOn(dispatchers.io)

    private fun rankSearchResults(query: String, matches: List<WorkMatch>): List<WorkMatch> = matches
        .map { it.withRecomputedConfidence(query) }
        .sortedWith(
            compareByDescending<WorkMatch> {
                com.acgcompass.domain.matching.representativeScore(it.matchConfidence.toDouble(), it.popularity)
            }
                .thenByDescending { it.popularity }
                .thenBy { sourceCredibilityRank(it.sourceTag) },
        )

    /**
     * 只在其它源出现、Bangumi 没搜到的动画：用它的日文 / 罗马音 / 原名再去 Bangumi 搜一次补上。
     * 各作品之间并行（最多 [BACKFILL_CONCURRENCY] 路，仍受 Bangumi 限流约束）；同一作品的几个标题按顺序试，
     * 搜到就停。总请求数不超过 [BANGUMI_BACKFILL_BUDGET]。
     */
    private suspend fun backfillBangumi(matches: List<WorkMatch>): List<WorkMatch> {
        if (matches.isEmpty()) return emptyList()
        val budget = java.util.concurrent.atomic.AtomicInteger(BANGUMI_BACKFILL_BUDGET)
        val gate = Semaphore(BACKFILL_CONCURRENCY)
        val targets = com.acgcompass.domain.matching.clusterMatches(matches)
            .filter { cluster -> cluster.none { it.sourceTag == SourceId.BANGUMI } }
            .map { com.acgcompass.domain.matching.representativeOf(it) }
            .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
        return coroutineScope {
            targets.map { rep -> async { gate.withPermit { backfillOne(rep, budget) } } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun backfillOne(rep: WorkMatch, budget: java.util.concurrent.atomic.AtomicInteger): WorkMatch? {
        val queries = listOfNotNull(rep.work.titles.ja, rep.work.titles.romaji, rep.work.titles.canonical)
            .filter { it.isNotBlank() }
            .distinct()
        var found: WorkMatch? = null
        for (q in queries) {
            if (budget.getAndDecrement() <= 0) break
            val hits = (bangumi.searchSubjects(keyword = q) as? AppResult.Success)?.data.orEmpty()
            val best = hits
                .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
                // 先排除续作、主题曲、衍生条目：长标题加一个「続」时标题相似度依然很高。
                .filter { com.acgcompass.domain.matching.sameSeasonAndEdition(rep.work, it.work) }
                .map { m -> m to candidateTitles(m).maxOf { matchConfidence(candidate = it, query = q) } }
                .maxWithOrNull(
                    compareBy<Pair<WorkMatch, Double>>(
                        { com.acgcompass.domain.matching.representativeScore(it.second, it.first.popularity) },
                        { it.first.popularity },
                    ),
                )
            if (best != null && best.second >= CROSS_MATCH_THRESHOLD) {
                found = best.first.copy(matchConfidence = best.second.toFloat())
                break
            }
        }
        return found
    }

    private suspend fun safeSearch(block: suspend () -> AppResult<List<WorkMatch>>): List<WorkMatch> = try {
        when (val result = block()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** 构造 Bangumi 搜索取数器；远程失败原样透传 [AppResult.Failure]，由编排器降级处理。 */
    private fun bangumiSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> = SourceFetcher {
        when (val result = bangumi.searchSubjects(keyword = query)) {
            is AppResult.Success -> AppResult.Success(SourceData(result.data))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /**
     * 构造 AniList 搜索取数器；远程失败透传为 [AppResult.Failure]，由编排器降级处理。
     *
     * 中文兜底语义：AniList 命中作品以英文标题作为 `canonical`（无中文名），
     * 当提供中文标题的 Bangumi 不可用时，由 AniList 结果回退展示。
     */
    private fun anilistSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> = SourceFetcher {
        when (val result = anilist.searchMedia(keyword = query)) {
            is AppResult.Success -> AppResult.Success(SourceData(result.data))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /**
     * 构造 MAL 官方搜索取数器。
     *
     * 启用门控：仅当用户显式配置 Client ID（[MalRemoteDataSource.isEnabled]）才参与降级；
     * 未配置时取数器返回 `null`，编排器据此**跳过**本源、不发请求。
     * 已启用时远程失败原样透传 [AppResult.Failure]，由编排器降级到 VNDB。
     */
    private fun malSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> = SourceFetcher {
        if (!mal.isEnabled()) return@SourceFetcher null
        when (val result = mal.searchAnime(keyword = query)) {
            is AppResult.Success -> AppResult.Success(SourceData(result.data))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /** 来源可信度排序（数值越小越靠前）；动画综合资料优先使用 Bangumi 和 AniList。 */
    private fun sourceCredibilityRank(source: SourceId): Int = when (source) {
        SourceId.BANGUMI -> 0
        SourceId.ANILIST -> 1
        SourceId.MAL -> 2
        SourceId.TENRAI -> 3
        SourceId.JIKAN -> 4
        SourceId.VNDB -> 5
    }

    /** 以标题相似度（覆盖所有已知标题 / 别名取最大值）重算匹配置信度。 */
    private fun WorkMatch.withRecomputedConfidence(query: String): WorkMatch {
        val titles = buildList {
            add(work.titles.canonical)
            work.titles.ja?.let(::add)
            work.titles.romaji?.let(::add)
            work.titles.en?.let(::add)
            addAll(work.titles.aliases)
        }
        val best = titles.maxOfOrNull { matchConfidence(candidate = it, query = query) } ?: 0.0
        return copy(matchConfidence = best.toFloat())
    }

    /**
     * 把命中作品与其源链接写入 Room（upsert）。
     *
     * 源链接经 [resolveLink] 解析：若本地已存在且被用户手动纠正（`userOverridden=true`），
     * 则保留用户选择、不被本次远程结果覆盖；作品的 `createdAt` 在重复 upsert 时保留。
     */
    private suspend fun persistMatches(matches: List<WorkMatch>) {
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
                val recomputed = ResolvedLink(
                    sourceRef = SourceRef(
                        sourceId = match.sourceTag,
                        sourceItemId = match.work.id,
                        matchConfidence = match.matchConfidence,
                        userOverridden = false,
                    ),
                    userOverridden = false,
                )
                val existing = sourceLinkDao.getBySourceItem(match.sourceTag.name, match.work.id)
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
                        id = existing?.id ?: linkId(repId, match.sourceTag, match.work.id),
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

    // --- aggregateRatings --------------------------------------------------
    override suspend fun loadPublicDiscovery(background: Boolean): AppResult<Int> = withContext(dispatchers.io) {
        cacheFillCoordinator.run {
            runCatchingApp {
                val requestPriority = if (background) RequestPriority.BACKGROUND else RequestPriority.FOREGROUND
                var written = 0
                // 收集全部公共源命中，最后**一次性** persistMatches → 跨源按标题聚类合并，
                // 同一作品的多平台链接指向同一代表作品，以聚合多平台评分。
                val allMatches = mutableListOf<com.acgcompass.domain.model.WorkMatch>()
                // 评分写入缓冲：(workId, source, entry)，persistMatches 后统一写。
                val ratingRows = mutableListOf<Triple<String, SourceId, com.acgcompass.domain.model.RatingEntry>>()

                // 本季 / 近期热门**默认 Bangumi**（每日放送）。
                val calendar = (bangumi.getCalendar(requestPriority) as? AppResult.Success)?.data.orEmpty()
                calendar.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
                }
                written += calendar.size

                // 分页拉取 Bangumi `sort=rank` 的 Top 动画和本季榜，补充筛选与评分差异样本；
                // 带真实评分，使「榜单/筛选/差异」有量且准确。失败降级（不阻塞）。
                val rankedOverall = buildList {
                    for (page in 0 until 3) {
                        val pageData = (
                            bangumi.searchRankedSubjects(
                                limit = 50,
                                offset = page * 50,
                                priority = requestPriority,
                            ) as? AppResult.Success
                            )?.data?.items.orEmpty()
                        if (pageData.isEmpty()) break
                        addAll(pageData)
                    }
                }
                val seasonRange = currentSeasonAirDateRange()
                val rankedSeason =
                    (
                        bangumi.searchRankedSubjects(
                            airDate = seasonRange,
                            limit = 30,
                            priority = requestPriority,
                        ) as? AppResult.Success
                        )
                        ?.data?.items.orEmpty()
                (rankedOverall + rankedSeason).forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
                }
                written += rankedOverall.size + rankedSeason.size

                // Supplementary sources keep durable cursors and continue until their actual slot target.
                val settingsState = settingsDataStore.settings.first()
                val policy = settingsState.toCachePolicy()
                val sourceTargets = CacheSourceAllocation.countTargets(policy.targetWorkSlots())
                val fillUpdates = mutableListOf<Pair<SourceId, CacheFillBatch<*>>>()
                val tenraiBatch = cacheFillSourcePager.loadTenrai(
                    targetCount = sourceTargets.getValue(SourceId.TENRAI),
                    priority = requestPriority,
                )
                val tenraiItems = tenraiBatch?.items.orEmpty()
                if (tenraiBatch != null) fillUpdates += SourceId.TENRAI to tenraiBatch
                tenraiItems.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.TENRAI, entry)
                }
                written += tenraiItems.size
                val aniListBatch = cacheFillSourcePager.loadAniList(
                    targetCount = sourceTargets.getValue(SourceId.ANILIST),
                    priority = requestPriority,
                )
                val trending = aniListBatch?.items.orEmpty()
                if (aniListBatch != null) fillUpdates += SourceId.ANILIST to aniListBatch
                trending.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.ANILIST, entry)
                }
                written += trending.size

                // VNDB stays in the visual-novel boundary and keeps R18 filtering at the source.
                if (settingsState.vndbEnabled) {
                    val vndbBatch = cacheFillSourcePager.loadVndb(
                        targetCount = sourceTargets.getValue(SourceId.VNDB),
                        showAdultContent = settingsState.showAdultContent,
                        priority = requestPriority,
                    )
                    val vndbItems = vndbBatch?.items.orEmpty()
                    if (vndbBatch != null) fillUpdates += SourceId.VNDB to vndbBatch
                    vndbItems.forEach { item ->
                        allMatches += com.acgcompass.domain.model.WorkMatch(
                            work = item.work,
                            matchConfidence = 1f,
                            sourceTag = SourceId.VNDB,
                            popularity = item.voteCount,
                        )
                        item.rating?.takeIf { it > 0f && item.voteCount > 0 }?.let { score ->
                            ratingRows += Triple(
                                item.work.id,
                                SourceId.VNDB,
                                com.acgcompass.domain.model.RatingEntry(score, item.voteCount),
                            )
                        }
                    }
                    written += vndbItems.size
                }

                if (allMatches.isEmpty()) {
                    cacheFillSourcePager.persist(fillUpdates)
                    if (!background && cacheDao.countUnprotectedEntries() > 0) return@runCatchingApp 0
                    throw AppError.Network(cause = "未能获取公共榜单，请检查网络后重试").asException()
                }
                // 一次性持久化 + 跨源聚类（source links 指向代表作品）。
                persistMatches(allMatches)
                // 写入各源评分行（按各源 work id；详情/差异聚合时 aggregateRatings 会按 source links 汇总）。
                ratingRows.forEach { (workId, source, entry) ->
                    ratingDao.upsert(
                        entry.toEntity(id = ratingId(workId, source), workId = workId, sourceId = source),
                    )
                }
                cacheFillSourcePager.persist(fillUpdates)
                // 基础榜单写入后，如本地作品不足目标规模，则继续分页拉取 Bangumi 排行补齐
                // （rankedOverall 上面已拉 0..2 页，故从第 3 页续拉）。目标上限来自设置：用户未设（0）时
                // 用 App 智能默认目标，设了则以其为准。已达标则仅一次 count 开销后跳过。
                written += backfillCacheToPolicy(startPage = 3, policy = policy, background = background)
                written
            }
        }
    }

    override fun cancelPublicDiscoveryFill() {
        cacheFillCoordinator.cancel()
    }

    /**
     * 渐进补齐本地作品候选池。从 Bangumi 排行（`sort=rank`）的
     * [startPage] 续拉，每页落库后重新计数；达到目标用量或接口耗尽即停。返回本次新写入条数。
     *
     * [target]：目标部数（来自设置的自动缓存上限；`<=0` 直接返回不缓存）。
     *
     * 分段限速：前 [FAST_PHASE_WORKS] 部不附加延迟，以缩短首次填充时间；
     * 越过快速阶段后按「距目标的进度」递增页间延迟（[THROTTLE_MIN_DELAY_MS]→[THROTTLE_MAX_DELAY_MS]），
     * 越接近目标越慢，降低持续联网 / 落库的资源占用。未设上限（用默认目标）与已设上限两种情况均
     * 沿此曲线（曲线相对 target 归一，故自适应不同上限）。
     *
     * 分页游标保存在 Room 中，跨源去重后的实际公共缓存数量决定是否继续；不使用固定页数作为停止条件。
     *
     * 任一页失败（含限流）静默跳出，不阻塞启动；下次进首页仍不足会继续渐进补齐。
     */
    private suspend fun backfillCacheToPolicy(startPage: Int, policy: CachePolicy, background: Boolean): Int {
        val pageSize = 50
        val saved = vndbRankingCacheDao.getFillState(SourceId.BANGUMI.name)
        val currentDate = java.time.LocalDate.now()
        val annualPools = (1..POPULAR_YEAR_POOL_COUNT).map { yearsAgo ->
            val year = currentDate.year - yearsAgo
            "YEAR_$year" to listOf(">=$year-01-01", "<${year + 1}-01-01")
        }
        val pools = listOf(
            "OVERALL" to null,
            "YEAR_${currentDate.year}" to
                listOf(">=${currentDate.withDayOfYear(1)}", "<${currentDate.plusYears(1).withDayOfYear(1)}"),
            "SEASON" to currentSeasonAirDateRange(),
        ) + annualPools
        val exhaustedPools = saved?.exhaustedPools.orEmpty().split(',').filter(String::isNotBlank).toMutableSet()
        var poolIndex = pools.indexOfFirst { it.first !in exhaustedPools }.takeIf { it >= 0 } ?: pools.size
        var page = if (poolIndex == 0) maxOf(startPage, saved?.nextPage ?: startPage) else 0
        val initialCount = cacheDao.countUnprotectedEntries()
        var usage = cacheUsage(policy)
        var calendarFallbackUsed = false
        while (usage < 1f && poolIndex <= pools.size) {
            if (poolIndex == pools.size) {
                if (calendarFallbackUsed) break
                calendarFallbackUsed = true
                val calendar = (bangumi.getCalendar(RequestPriority.BACKGROUND) as? AppResult.Success)?.data.orEmpty()
                if (calendar.isNotEmpty()) {
                    persistMatches(calendar.map { it.first })
                    calendar.forEach { (match, entry) ->
                        entry?.let {
                            ratingDao.upsert(
                                it.toEntity(
                                    id = ratingId(match.work.id, SourceId.BANGUMI),
                                    workId = match.work.id,
                                    sourceId = SourceId.BANGUMI,
                                ),
                            )
                        }
                    }
                    usage = cacheUsage(policy)
                }
                if (usage >= 1f) break
                exhaustedPools += "CALENDAR"
                poolIndex++
                break
            }
            val pool = pools[poolIndex]
            val result = bangumi.searchRankedSubjects(
                airDate = pool.second,
                limit = pageSize,
                offset = page * pageSize,
                priority = if (background) RequestPriority.BACKGROUND else RequestPriority.FOREGROUND,
            )
            if (result is AppResult.Failure) {
                val now = System.currentTimeMillis()
                vndbRankingCacheDao.upsertFillState(
                    CacheFillStateEntity(
                        source = SourceId.BANGUMI.name,
                        nextPage = page,
                        exhausted = false,
                        lastRunAt = now,
                        lastResult = "FAILED",
                        failureReason = listOf(result.error.cause, result.error.nextStep)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        updatedAt = now,
                    ),
                )
                break
            }
            val pageResult = (result as AppResult.Success).data
            val items = pageResult.items
            if (items.isEmpty()) {
                exhaustedPools += pool.first
                poolIndex++
                page = 0
                continue
            }
            persistMatches(items.map { it.first })
            items.forEach { (match, entry) ->
                if (entry != null) {
                    ratingDao.upsert(
                        entry.toEntity(
                            id = ratingId(match.work.id, SourceId.BANGUMI),
                            workId = match.work.id,
                            sourceId = SourceId.BANGUMI,
                        ),
                    )
                }
            }
            usage = cacheUsage(policy)
            page++
            val exhausted = page * pageSize >= pageResult.total
            if (exhausted) {
                exhaustedPools += pool.first
                poolIndex++
                page = 0
            }
            val now = System.currentTimeMillis()
            vndbRankingCacheDao.upsertFillState(
                CacheFillStateEntity(
                    source = SourceId.BANGUMI.name,
                    nextPage = page,
                    exhausted = usage >= 1f || poolIndex >= pools.size + 1,
                    lastRunAt = now,
                    lastResult = when {
                        usage >= 1f -> "TARGET_REACHED"
                        poolIndex >= pools.size + 1 -> "EXHAUSTED"
                        else -> "RUNNING"
                    },
                    failureReason = null,
                    exhaustedPools = exhaustedPools.sorted().joinToString(","),
                    updatedAt = now,
                ),
            )
            if (usage >= 1f) break
            delay(CacheSourceAllocation.delayMillis(usage))
        }
        val now = System.currentTimeMillis()
        val completed = usage >= 1f
        vndbRankingCacheDao.upsertFillState(
            CacheFillStateEntity(
                source = SourceId.BANGUMI.name,
                nextPage = page,
                exhausted = completed || poolIndex > pools.size,
                lastRunAt = now,
                lastResult = when {
                    completed -> "TARGET_REACHED"
                    poolIndex > pools.size -> "EXHAUSTED"
                    else -> "RUNNING"
                },
                failureReason = null,
                updatedAt = now,
                exhaustedPools = exhaustedPools.sorted().joinToString(","),
            ),
        )
        return (cacheDao.countUnprotectedEntries() - initialCount).coerceAtLeast(0)
    }

    private suspend fun cacheUsage(policy: CachePolicy): Float = when (policy.mode) {
        CacheLimitMode.COUNT -> cacheDao.countUnprotectedEntries().toFloat() / policy.normalizedCountLimit
        CacheLimitMode.SPACE -> (cacheDao.metadataBytes() + cacheDao.mediaBytes()).toFloat() /
            policy.normalizedSpaceLimitBytes
    }.coerceAtLeast(0f)

    private fun SettingsState.toCachePolicy(): CachePolicy = CachePolicy(
        mode = cacheLimitMode,
        countLimit = autoCacheMaxCount,
        spaceLimitBytes = cacheSpaceLimitMb.toLong() * 1024L * 1024L,
        backgroundFillEnabled = cacheBackgroundFillEnabled,
    )

    private fun CachePolicy.targetWorkSlots(): Int = when (mode) {
        CacheLimitMode.COUNT -> normalizedCountLimit
        CacheLimitMode.SPACE -> (normalizedSpaceLimitBytes / ESTIMATED_PUBLIC_WORK_SLOT_BYTES)
            .toInt()
            .coerceIn(CachePolicy.MIN_COUNT_LIMIT, CachePolicy.MAX_COUNT_LIMIT)
    }

    private fun estimateMetadataBytes(work: Work): Long = 512L +
        listOfNotNull(
            work.titles.canonical,
            work.titles.ja,
            work.titles.romaji,
            work.titles.en,
            work.summary,
            work.coverUrl,
        ).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
        work.titles.aliases.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

    /** 当前季度的开播日期范围过滤，用于本季榜单 `sort=rank` 浏览。 */
    private fun currentSeasonAirDateRange(): List<String> {
        val now = java.time.LocalDate.now()
        val startMonth = ((now.monthValue - 1) / 3) * 3 + 1
        val start = java.time.LocalDate.of(now.year, startMonth, 1)
        val end = start.plusMonths(3)
        val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        return listOf(">=${start.format(fmt)}", "<${end.format(fmt)}")
    }

    override suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
    ): AppResult<RankingPage> = withContext(dispatchers.io) {
        runCatchingApp {
            // 按 offset/limit 拉取真实排行的一页（Bangumi 已按 rank 排序，跨页顺序由 offset 维持）。
            val page = when (
                val r = bangumi.searchRankedSubjects(airDate = airDate, limit = limit, offset = offset)
            ) {
                is AppResult.Success -> r.data
                is AppResult.Failure -> throw r.error.asException()
            }
            val pageData = page.items
            // 写入 Room（详情可跳转 + 冷启动重建卡片）+ 写 Bangumi 评分行；跨源聚类由 persistMatches 处理。
            // 空页（分页到底）属正常：persistMatches 对空列表直接返回。
            persistMatches(pageData.map { it.first })
            pageData.forEach { (match, entry) ->
                if (entry != null) {
                    ratingDao.upsert(
                        entry.toEntity(
                            id = ratingId(match.work.id, SourceId.BANGUMI),
                            workId = match.work.id,
                            sourceId = SourceId.BANGUMI,
                        ),
                    )
                }
            }
            // 返回该页作品 + 过滤后总数，供上层用 total 判定是否还有更多页（不依赖返回数 >= limit）。
            RankingPage(
                items = pageData.map { (match, entry) -> match.work to entry },
                total = page.total,
            )
        }
    }

    override suspend fun getCachedRanking(
        scopeKey: String,
    ): List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>> = withContext(dispatchers.io) {
        // 按 Room `ranking_cache` 表的有序作品 id 重建榜单卡片所需的作品和 Bangumi 评分；
        // 作品已被清理的行安全跳过（容错缓存）。
        rankingCacheDao.getByScope(scopeKey).mapNotNull { row ->
            val work = workDao.getById(row.workId)?.toDomain() ?: return@mapNotNull null
            val entry = ratingDao.getByWorkAndSource(row.workId, SourceId.BANGUMI.name)?.toEntryOrNull()
            work to entry
        }
    }

    override suspend fun saveRankingCache(scopeKey: String, orderedWorkIds: List<String>) {
        // 在单事务中覆盖写入整个范围；position 是排名次序，cachedAt 用于新鲜度判断。
        val now = System.currentTimeMillis()
        val rows = orderedWorkIds.mapIndexed { index, id ->
            RankingCacheEntity(scopeKey = scopeKey, position = index, workId = id, cachedAt = now)
        }
        rankingCacheDao.replaceScope(scopeKey, rows)
    }

    override suspend fun aggregateRatings(workId: String, forceRefresh: Boolean): AppResult<RatingAggregate> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 按作品源链接尽力刷新各源评分和资料；失败时沿用本地数据。
                val cachedRows = ratingDao.getByWork(workId)
                val now = System.currentTimeMillis()
                val tenraiMissing = cachedRows.none { it.sourceId == SourceId.TENRAI.name } &&
                    settingsDataStore.jikanEnabled.first()
                val stale = cachedRows.isEmpty() || tenraiMissing || cachedRows.any {
                    val ttl = if (it.missing) {
                        DetailCacheTtl.MISSING_RATING_MILLIS
                    } else {
                        DetailCacheTtl.COMMENTS_MILLIS
                    }
                    now - it.fetchedAt >= ttl
                }
                val needsProfile = needsFullProfile(workId, now)
                if (forceRefresh || stale || needsProfile) {
                    refreshRatingsAndWork(workId, forceRefresh, refreshProfile = needsProfile)
                }
                // 单一可信源：从 Room 读取每源评分行，聚合（缺失源保留 null，绝不回填）。
                val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
                aggregateRatingsUseCase(perSource)
            }
        }

    override suspend fun backfillRatings(workId: String): AppResult<RatingAggregate> = withContext(dispatchers.io) {
        runCatchingApp {
            refreshRatingsAndWork(workId, forceRefresh = false, priority = RequestPriority.BACKGROUND)
            aggregateRatingsUseCase(ratingDao.getByWork(workId).toRatingAggregate().perSource)
        }
    }

    /** 仅读本地缓存评分聚合，不触发网络刷新（发现页批量场景用，避免逐作品联网卡顿）。 */
    override suspend fun aggregateRatingsCached(workId: String): RatingAggregate = withContext(dispatchers.io) {
        val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
        aggregateRatingsUseCase(perSource)
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
                async { enrichViaCrossSourceSearch(workId, priority) },
                if (priority == RequestPriority.FOREGROUND) async { refreshPrimaryWork(workId, sourcePairs) } else null,
            ).forEach { it.await() }
        }
    }

    private suspend fun ensureWorkAvailable(workId: String, priority: RequestPriority) {
        if (workDao.getById(workId) != null) return
        val subjectId = workId.toIntOrNull() ?: return
        val fetched = bangumi.getSubjectBundle(subjectId, priority)
        if (fetched is AppResult.Success) {
            persistBangumiBundle(workId, fetched.data)
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
                DetailCacheTtl.MISSING_RATING_MILLIS
            } else {
                DetailCacheTtl.COMMENTS_MILLIS
            }
            val stale = cached == null || now - cached.fetchedAt >= ttl
            forceRefresh || stale
        }
        coroutineScope {
            stalePairs.map { (source, itemId) ->
                async { persistRatingResult(workId, source, fetchSourceRating(source, itemId, priority)) }
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
            DetailCacheTtl.MISSING_RATING_MILLIS
        } else {
            DetailCacheTtl.COMMENTS_MILLIS
        }
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < ttl) return
        val result = bangumi.getSubjectBundle(subjectId, priority, fresh = forceRefresh)
        if (result is AppResult.Success) persistBangumiBundle(workId, result.data)
    }

    private suspend fun persistBangumiBundle(workId: String, bundle: BangumiSubjectBundle) {
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
        bundle.subject.toBasicWorkFeature(subjectId = workId, updatedAt = now)?.let { feature ->
            workFeatureRepository.seedLightweight(listOf(feature))
        }
        val rating = bundle.rating
        if (rating == null) {
            ratingDao.upsert(
                missingRatingEntity(
                    id = ratingId(workId, SourceId.BANGUMI),
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

    private fun fallbackSourceItemId(workId: String, source: SourceId): String? = when (source) {
        SourceId.BANGUMI -> workId.takeIf { it.toIntOrNull() != null }
        SourceId.ANILIST -> workId.removePrefix("ANILIST:").takeIf { it != workId && it.toIntOrNull() != null }
        SourceId.VNDB -> workId.takeIf { !it.contains(':') }
        SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL -> workId.takeIf { it.toIntOrNull() != null }
    }

    /** 从持久化来源名解析 [SourceId]；未知返回 `null`（不臆造）。 */
    private fun sourceIdFromName(raw: String?): SourceId? = SourceId.entries.firstOrNull { it.name == raw }

    /** 按来源拉取单源评分；不支持 / 未启用 / id 非法时返回 `null`。 */
    private suspend fun fetchSourceRating(
        sid: SourceId,
        itemId: String,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<com.acgcompass.domain.model.RatingEntry>? = when (sid) {
        SourceId.BANGUMI -> itemId.toIntOrNull()?.let { bangumi.getSubjectRating(it, priority) }
        SourceId.ANILIST -> itemId.toIntOrNull()?.let { anilist.getMediaRating(it, priority) }
        SourceId.TENRAI -> itemId.toIntOrNull()?.let { tenrai.getAnimeRating(it, priority) }
        SourceId.JIKAN -> null
        SourceId.MAL -> if (mal.isEnabled()) itemId.toIntOrNull()?.let { mal.getAnimeRating(it, priority) } else null
        SourceId.VNDB -> vndb.getVnRating(itemId)
    }

    private suspend fun persistRatingResult(
        workId: String,
        source: SourceId,
        result: AppResult<com.acgcompass.domain.model.RatingEntry>?,
    ) {
        val persisted = when (result) {
            is AppResult.Success -> {
                ratingDao.upsert(
                    result.data.toEntity(
                        id = ratingId(workId, source),
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
                        id = ratingId(workId, source),
                        workId = workId,
                        sourceId = source,
                    ),
                )
                true
            } else {
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

    private suspend fun persistSourceLink(workId: String, source: SourceId, sourceItemId: String, confidence: Double) {
        sourceLinkDao.upsert(
            SourceRef(
                sourceId = source,
                sourceItemId = sourceItemId,
                matchConfidence = confidence.toFloat(),
                userOverridden = false,
            ).toEntity(
                id = linkId(workId, source, sourceItemId),
                workId = workId,
                linkedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 单源作品跨源补齐：通过多源搜索和季度感知聚类补齐评分链接。
     *
     * 将本作作为锚点与各来源搜索结果聚类，只把同簇条目的来源链接和评分写到 [workId]。
     * 搜索与评分均按来源并行，单一来源失败不会阻塞其他来源。
     */
    private suspend fun enrichViaCrossSourceSearch(
        workId: String,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ) {
        val entity = workDao.getById(workId) ?: return
        if (com.acgcompass.domain.model.MediaType.fromStorage(entity.mediaType) !=
            com.acgcompass.domain.model.MediaType.ANIME
        ) {
            return
        }
        val self = entity.toDomain()
        val primary = sourceIdFromName(entity.primarySource) ?: SourceId.BANGUMI
        val existingSources = buildSet {
            add(primary.name)
            sourceLinkDao.getByWork(workId).forEach { add(it.sourceId) }
        }
        val searchers = crossSourceSearchers(existingSources, priority)
        if (searchers.isEmpty()) return
        val queries = com.acgcompass.domain.matching.foreignSearchQueries(self)
        if (queries.isEmpty()) return

        // 以本作为锚点参与聚类（matchConfidence=1f 确保先建簇），取「与本作同簇」的其他源候选。
        val anchor = WorkMatch(work = self, matchConfidence = 1f, sourceTag = primary, popularity = Int.MAX_VALUE)
        fun clusterOfSelf(candidates: List<WorkMatch>): List<WorkMatch>? =
            com.acgcompass.domain.matching.clusterMatches(listOf(anchor) + candidates)
                .firstOrNull { c -> c.any { it.work.id == self.id && it.sourceTag == primary } }

        // 分两步：先搜 Bangumi / AniList；AniList 命中的同簇条目带罗马音和英文名，再用它们去搜 Tenrai / MAL
        // （MAL 的主标题就是罗马音），只有外文名的平台更容易精确命中。
        val (firstStage, secondStage) = searchers.partition { (sid, _) ->
            sid == SourceId.BANGUMI || sid == SourceId.ANILIST
        }
        val fresh = searchAll(firstStage, queries).toMutableList()
        if (secondStage.isNotEmpty()) {
            val bridgeTitles = clusterOfSelf(fresh).orEmpty()
                .filter { it.sourceTag == SourceId.ANILIST }
                .flatMap { listOfNotNull(it.work.titles.romaji, it.work.titles.en) }
            val secondQueries = (bridgeTitles + queries)
                .distinctBy { com.acgcompass.domain.matching.normalizeTitle(it) }
                .take(SECOND_STAGE_QUERY_LIMIT)
            fresh += searchAll(secondStage, secondQueries)
        }
        if (fresh.isEmpty()) return
        val cluster = clusterOfSelf(fresh) ?: return
        persistCrossSourceCluster(workId, primary, existingSources, cluster, priority)
    }

    private suspend fun searchAll(
        searchers: List<Pair<SourceId, suspend (String) -> AppResult<List<WorkMatch>>>>,
        queries: List<String>,
    ): List<WorkMatch> = if (searchers.isEmpty()) {
        emptyList()
    } else {
        coroutineScope {
            queries.flatMap { q -> searchers.map { (_, search) -> async { safeSearch { search(q) } } } }
                .flatMap { it.await() }
        }
    }

    private suspend fun crossSourceSearchers(
        existingSources: Set<String>,
        priority: RequestPriority,
    ): List<Pair<SourceId, suspend (String) -> AppResult<List<WorkMatch>>>> {
        val tenraiEnabled = settingsDataStore.jikanEnabled.first()
        return buildList {
            if (SourceId.BANGUMI.name !in existingSources) {
                add(SourceId.BANGUMI to { query -> bangumi.searchSubjects(keyword = query, priority = priority) })
            }
            if (SourceId.ANILIST.name !in existingSources) {
                add(SourceId.ANILIST to { query -> anilist.searchMedia(keyword = query, priority = priority) })
            }
            if (tenraiEnabled && SourceId.TENRAI.name !in existingSources) {
                add(SourceId.TENRAI to { query -> tenrai.searchAnime(keyword = query, priority = priority) })
            }
            if (mal.isEnabled() && SourceId.MAL.name !in existingSources) {
                add(SourceId.MAL to { query -> mal.searchAnime(keyword = query, priority = priority) })
            }
        }
    }

    private suspend fun persistCrossSourceCluster(
        workId: String,
        primary: SourceId,
        existingSources: Set<String>,
        cluster: List<WorkMatch>,
        priority: RequestPriority,
    ) = coroutineScope {
        cluster
            .filter { it.sourceTag != primary && it.sourceTag.name !in existingSources }
            .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
            .groupBy { it.sourceTag }
            .map { (sid, list) ->
                async {
                    val bestOfSource = com.acgcompass.domain.matching.representativeOf(list)
                    persistSourceLink(workId, sid, bestOfSource.work.id, bestOfSource.matchConfidence.toDouble())
                    if (sid == SourceId.BANGUMI) {
                        val subjectId = bestOfSource.work.id.toIntOrNull()
                        val bundle = subjectId?.let { bangumi.getSubjectBundle(it, priority) }
                        if (bundle is AppResult.Success) persistBangumiBundle(workId, bundle.data)
                    } else {
                        persistRatingResult(workId, sid, fetchSourceRating(sid, bestOfSource.work.id, priority))
                    }
                }
            }.forEach { it.await() }
    }

    private fun candidateTitles(match: WorkMatch): List<String> = buildList {
        add(match.work.titles.canonical)
        match.work.titles.ja?.let(::add)
        match.work.titles.romaji?.let(::add)
        match.work.titles.en?.let(::add)
        addAll(match.work.titles.aliases)
    }.filter { it.isNotBlank() }

    // --- overrideMatch -----------------------------------------------------

    override suspend fun overrideMatch(localId: String, chosen: SourceRef): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 手动纠正：标记 userOverridden=true，后续同步不再自动改写该链接。
                val overridden = ResolvedLink(
                    sourceRef = chosen.copy(userOverridden = true),
                    userOverridden = true,
                )
                val existing = sourceLinkDao.getBySourceItem(
                    chosen.sourceId.name,
                    chosen.sourceItemId,
                )
                sourceLinkDao.upsert(
                    overridden.sourceRef.toEntity(
                        id = existing?.id ?: linkId(localId, chosen.sourceId, chosen.sourceItemId),
                        workId = localId,
                    ),
                )
            }
        }

    private companion object {
        /** 空态「下一步」引导：去搜索添加作品。 */
        val EMPTY_WORK_CTA = Cta(label = "搜索并添加作品", action = "search")

        /** 稳定的源链接主键：`workId:sourceId:sourceItemId`（同一三元组重复 upsert 即覆盖）。 */
        fun linkId(workId: String, sourceId: SourceId, sourceItemId: String): String =
            "$workId:${sourceId.name}:$sourceItemId"

        /** 稳定的评分行主键：`workId:sourceId`（每源单行，刷新即覆盖）。 */
        fun ratingId(workId: String, sourceId: SourceId): String = "$workId:${sourceId.name}"

        /** 跨源补齐 Bangumi 的请求预算（单次搜索最多反查次数，控开销）。 */
        const val BANGUMI_BACKFILL_BUDGET = 8
        const val BACKFILL_CONCURRENCY = 2

        /** 跨平台交叉验证的标题相似度阈值（高阈值避免误配，宁缺毋滥）。 */
        const val CROSS_MATCH_THRESHOLD = 0.86

        /** 第二步（Tenrai / MAL）的查询词上限：AniList 给出的罗马音、英文名在前。 */
        const val SECOND_STAGE_QUERY_LIMIT = 3
        const val SUPPLEMENTARY_PAGE_SIZE = 25
        const val POPULAR_YEAR_POOL_COUNT = 15
        const val ESTIMATED_PUBLIC_WORK_SLOT_BYTES = 64L * 1024L
    }
}

/** 判断作品是否存在字段级缺失（封面 / 年份 / 发行状态未知）→ 触发「暂无数据」展示。 */
private fun Work.hasMissingDisplayFields(): Boolean = coverUrl == null ||
    year == null ||
    status == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN

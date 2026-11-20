package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.toAppError
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.RankingCacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkTagWithCategory
import com.acgcompass.data.local.entity.RankingCacheEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.local.mapper.toEntryOrNull
import com.acgcompass.data.local.mapper.toRatingAggregate
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.matching.ResolvedLink
import com.acgcompass.domain.matching.resolveLink
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.CacheFillController
import com.acgcompass.domain.repository.HomeSeasonal
import com.acgcompass.domain.repository.HomeSeasonalLists
import com.acgcompass.domain.repository.PublicCacheWorkRepository
import com.acgcompass.domain.repository.RankingPage
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [WorkRepository] 的本地优先实现。
 *
 * **单一可信源 = Room**：
 * - [observeWork] 始终从 [WorkDao] 的 Flow 读取并映射为七态 [UiState]，离线可用、状态一致。
 * - 搜索交给 [WorkSearchCoordinator]：多源并行取数、按标题相似度重算置信度并排序，命中作品写进 Room。
 * - 评分交给 [WorkRatingRefresher]：以 Room 中每源评分行为输入聚合，缺失源标记为 `missing`，绝不跨源回填。
 * - 公共缓存补充交给 [PublicCacheFiller]；所有写库经 [WorkRowWriter]，主键规则一致。
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
    private val bangumi: BangumiRemoteDataSource,
    private val aggregateRatingsUseCase: AggregateRatingsUseCase,
    private val dispatchers: DispatcherProvider,
    private val rowWriter: WorkRowWriter,
    private val searchCoordinator: WorkSearchCoordinator,
    private val publicCacheFiller: PublicCacheFiller,
    private val ratingRefresher: WorkRatingRefresher,
) : WorkRepository, PublicCacheWorkRepository, CacheFillController {

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

    override fun observeWorksByMediaType(mediaType: MediaType): Flow<List<Work>> =
        mapWorks(workDao.observeByMediaType(mediaType.name))

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

    override fun searchProgressive(query: String): Flow<AppResult<List<WorkMatch>>> =
        searchCoordinator.searchProgressive(query)

    // --- public cache ------------------------------------------------------

    override suspend fun loadPublicDiscovery(background: Boolean): AppResult<Int> =
        publicCacheFiller.loadPublicDiscovery(background)

    override fun cancelPublicDiscoveryFill() {
        publicCacheFiller.cancel()
    }

    override suspend fun refreshHomeSeasonal(
        fresh: Boolean,
        onFetched: suspend (HomeSeasonal) -> Unit,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            val (calendar, season) = coroutineScope {
                val calendarCall = async { bangumi.getCalendar(RequestPriority.FOREGROUND, fresh) }
                val seasonCall = async {
                    bangumi.searchRankedSubjects(airDate = currentSeasonAirDateRange(), limit = HOME_SEASONAL_LIMIT)
                }
                calendarCall.await() to seasonCall.await()
            }
            val calendarItems = (calendar as? AppResult.Success)?.data.orEmpty()
            val seasonItems = (season as? AppResult.Success)?.data?.items.orEmpty()
            if (calendarItems.isEmpty() && seasonItems.isEmpty()) {
                throw ((calendar as? AppResult.Failure)?.error ?: (season as AppResult.Failure).error).asException()
            }
            val newItems = calendarItems.sortedByDescending { it.second?.voteCount ?: 0 }
            onFetched(
                HomeSeasonal(
                    new = newItems.map { it.first.work to it.second },
                    hot = seasonItems.map { it.first.work to it.second },
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            persistWithBangumiRatings(calendarItems + seasonItems)
            if (newItems.isNotEmpty()) saveRankingCache(HomeSeasonalLists.NEW, newItems.map { it.first.work.id })
            if (seasonItems.isNotEmpty()) saveRankingCache(HomeSeasonalLists.HOT, seasonItems.map { it.first.work.id })
        }
    }

    private suspend fun persistWithBangumiRatings(
        items: List<Pair<WorkMatch, com.acgcompass.domain.model.RatingEntry?>>,
    ) {
        rowWriter.persistMatches(items.map { it.first }.distinctBy { it.work.id })
        rowWriter.upsertRatings(SourceId.BANGUMI, items)
    }

    override suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
        onFetched: suspend (RankingPage) -> Unit,
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
            // 返回该页作品 + 过滤后总数，供上层用 total 判定是否还有更多页（不依赖返回数 >= limit）。
            val result = RankingPage(items = pageData.map { (match, entry) -> match.work to entry }, total = page.total)
            onFetched(result)
            // 写入 Room（详情可跳转 + 冷启动重建卡片）+ 写 Bangumi 评分行；跨源聚类由 persistMatches 处理。
            // 空页（分页到底）属正常：persistMatches 对空列表直接返回。
            rowWriter.persistMatches(pageData.map { it.first })
            rowWriter.upsertRatings(SourceId.BANGUMI, pageData)
            result
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

    // --- ratings -----------------------------------------------------------

    override suspend fun aggregateRatings(workId: String, forceRefresh: Boolean): AppResult<RatingAggregate> =
        ratingRefresher.aggregateRatings(workId, forceRefresh)

    override suspend fun backfillRatings(workId: String): AppResult<RatingAggregate> =
        ratingRefresher.backfillRatings(workId)

    override suspend fun backfillRatingsOutcome(workId: String): RatingBackfillOutcome =
        ratingRefresher.backfillRatingsOutcome(workId)

    override suspend fun backfillRatingsReport(workId: String): com.acgcompass.domain.model.RatingBackfillReport =
        ratingRefresher.backfillRatingsReport(workId)

    override suspend fun aggregateRatingsCached(workId: String): RatingAggregate =
        ratingRefresher.aggregateRatingsCached(workId)

    override suspend fun aggregateRatingsCached(workIds: List<String>): Map<String, RatingAggregate> =
        ratingRefresher.aggregateRatingsCached(workIds)

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
                        id = existing?.id ?: WorkRowIds.linkId(localId, chosen.sourceId, chosen.sourceItemId),
                        workId = localId,
                    ),
                )
            }
        }

    private companion object {
        /** 首页本季热门取的条数（本季排行一页）。 */
        const val HOME_SEASONAL_LIMIT = 30

        /** 空态「下一步」引导：去搜索添加作品。 */
        val EMPTY_WORK_CTA = Cta(label = "搜索并添加作品", action = "search")
    }
}

/** 判断作品是否存在字段级缺失（封面 / 年份 / 发行状态未知）→ 触发「暂无数据」展示。 */
private fun Work.hasMissingDisplayFields(): Boolean = coverUrl == null ||
    year == null ||
    status == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN

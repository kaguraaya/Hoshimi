package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 作品仓库契约（领域层，纯 Kotlin）。负责规范化作品的观察、多源搜索 / 匹配、评分聚合与手动纠正。
 *
 * 单一可信源为本地缓存（Room）；[observeWork] 始终从本地 Flow 读取，离线可用、状态一致
 * （本地优先）。
 *
 */
@Suppress("TooManyFunctions")
interface WorkRepository {

    /**
     * 观察某作品的页面状态。从本地单一可信源读取，远程刷新后自动发射新值。
     *
     * @param workId 规范化作品 id。
     * @return 七态 [UiState] 流；缺失字段时发射 [UiState.PartialMissing]。
     */
    fun observeWork(workId: String): Flow<UiState<Work>>

    /** Local community tags are observed independently so detail can render them in its first frame. */
    fun observeWorkTags(workId: String): Flow<List<com.acgcompass.domain.model.Tag>> = flowOf(emptyList())

    /** Observe per-source rating rows as they are written by a foreground refresh. */
    fun observeRatings(workId: String): Flow<UiState<RatingAggregate>> = flowOf(
        UiState.Success(RatingAggregate()),
    )

    /**
     * 观察本地全部规范化作品（单一可信源 = Room）。供需要批量作品展示数据的页面使用
     * （如待补池把 [Work] 折叠为统一作品卡片）。按最近更新降序。
     *
     * @return 本地作品列表流；为空时发射空列表（由调用方决定空态展示）。
     */
    fun observeWorks(): Flow<List<Work>>

    /** 只观察某一媒介类型的作品（含标签），在数据库里筛选，不读其它类型的作品和标签。 */
    fun observeWorksByMediaType(mediaType: MediaType): Flow<List<Work>> =
        observeWorks().map { works -> works.filter { it.mediaType == mediaType } }

    /**
     * 多源搜索并合并，返回带匹配置信度的候选。
     *
     * 高于阈值的结果自动合并到同一规范化 [Work]，低于阈值的标记为待用户确认（不自动合并）。
     *
     * @param query 搜索关键词（标题 / 别名等）。
     * @return 成功时为按相关性排序的 [WorkMatch] 列表；失败时为 [AppResult.Failure]。
     */
    suspend fun search(query: String): AppResult<List<WorkMatch>>

    /** 渐进式搜索：每个源回来就发一版合并结果，最后一版补完 Bangumi 匹配（与 [search] 的结果一致）。 */
    fun searchProgressive(query: String): kotlinx.coroutines.flow.Flow<AppResult<List<WorkMatch>>>

    /**
     * 聚合某作品的多源评分。缺失源标记为 `missing`，**绝不**用其它源回填；
     * 有效样本不足时社区共识为 `null` 或低置信。
     *
     * @param workId 规范化作品 id。
     * @return 成功时为 [RatingAggregate]；失败时为 [AppResult.Failure]。
     */
    suspend fun aggregateRatings(workId: String, forceRefresh: Boolean = false): AppResult<RatingAggregate>

    /**
     * 后台补充其它平台评分：和打开详情页时一样刷新各来源评分（含按标题去其它平台匹配），
     * 但请求都走后台优先级，前台页面的请求先走；不补作品资料。
     */
    suspend fun backfillRatings(workId: String): AppResult<RatingAggregate> =
        aggregateRatings(workId, forceRefresh = false)

    /**
     * 后台补评分并给出结果分类：查过了（不论有没有找到）、这次因网络 / 限流没查成、或其它错误。
     * 默认实现只看 [backfillRatings] 的返回值。
     */
    suspend fun backfillRatingsOutcome(workId: String): RatingBackfillOutcome = when (backfillRatings(workId)) {
        is AppResult.Success -> RatingBackfillOutcome.CHECKED
        is AppResult.Failure -> RatingBackfillOutcome.TRANSIENT_FAILURE
    }

    /** 同 [backfillRatingsOutcome]，另外带出这次没拿到结果的平台。默认实现不区分平台。 */
    suspend fun backfillRatingsReport(workId: String): com.acgcompass.domain.model.RatingBackfillReport =
        com.acgcompass.domain.model.RatingBackfillReport(backfillRatingsOutcome(workId))

    /**
     * 仅读取本地已缓存的多源评分聚合（**不触发网络刷新**）。用于发现页榜单 / 评分差异 / 筛选
     * 等需要遍历大量作品的场景，避免逐作品网络刷新造成的卡顿 / 永不收敛。
     *
     * @return 本地评分聚合；无缓存评分时各源为 `null`（不伪造）。
     */
    suspend fun aggregateRatingsCached(workId: String): RatingAggregate

    /** 一次读出多部作品的本地评分聚合（不联网），键为作品 id；比逐部调用 [aggregateRatingsCached] 少很多次查库。 */
    suspend fun aggregateRatingsCached(workIds: List<String>): Map<String, RatingAggregate> =
        workIds.associateWith { aggregateRatingsCached(it) }

    /**
     * 手动纠正某本地作品的源匹配。写入后置 `userOverridden=true`，
     * 后续同步不再自动改写该链接。
     *
     * @param localId 本地规范化作品 id。
     * @param chosen 用户选择的外部源条目引用。
     * @return 成功时为 [AppResult.Success]（Unit）；失败时为 [AppResult.Failure]。
     */
    suspend fun overrideMatch(localId: String, chosen: SourceRef): AppResult<Unit>

    /**
     * 加载公共发现池（本季 / Top 榜单，无需 token）。拉取公共榜单作品并连同其评分写入 Room，
     * 使发现页本季榜单 / 评分差异 / 筛选有内容；网络失败返回 [AppResult.Failure]（UI 显示错误 + 重试）。
     *
     * @return 成功时为写入 / 更新的作品数；失败时为 [AppResult.Failure]。
     */
    suspend fun loadPublicDiscovery(background: Boolean = false): AppResult<Int>

    /**
     * 按范围分页加载 Bangumi 真实排行榜的**一页**（`sort=rank`，offset/limit）。
     * 总榜（airDate=null）/ 今年 / 本季由 [airDate] 过滤区间决定（如 `[">=2026-01-01", "<2027-01-01"]`）。
     * 命中作品 + 该源评分写入 Room（详情可跳转、冷启动可重建卡片），返回该页作品 + 评分（缺失为 null，不伪造），
     * 按真实排名顺序。空页（分页到底）属正常，返回空列表；仅网络/解析失败返回 [AppResult.Failure]。
     * 网络结果一到就先交给 [onFetched]（页面立即显示），再写数据库。
     */
    suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
        onFetched: suspend (RankingPage) -> Unit = {},
    ): AppResult<RankingPage>

    /**
     * 读取某榜单范围已持久化的缓存（冷启动秒开）。按缓存的有序作品 id 从 Room 重建作品 + Bangumi 评分；
     * 无缓存或作品已被清理时返回空。[scopeKey] 为范围稳定键（如 OVERALL/YEAR/SEASON）。
     */
    suspend fun getCachedRanking(scopeKey: String): List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>

    /** 持久化某榜单范围的有序作品 id（覆盖写），供下次冷启动秒开。 */
    suspend fun saveRankingCache(scopeKey: String, orderedWorkIds: List<String>)

    /**
     * 刷新首页「本季新番 / 本季热门」：Bangumi 每日放送（按评分人数排）和本季排行各一次请求，前台优先级，
     * 结果按顺序存为 [HomeSeasonalLists.NEW] / [HomeSeasonalLists.HOT]，用 [getCachedRanking] 读出。
     * [fresh] 为 true（用户下拉）时跳过 HTTP 缓存。
     *
     * 网络结果一到就先交给 [onFetched]（页面立即显示），再写数据库：写库要和后台补资料排队，可能要等几秒。
     */
    suspend fun refreshHomeSeasonal(fresh: Boolean, onFetched: suspend (HomeSeasonal) -> Unit = {}): AppResult<Unit>
}

/** 首页本季列表：作品 + Bangumi 评分，按显示顺序。[fetchedAt] 是联网取到的时间，读本地缓存时为 null。 */
data class HomeSeasonal(
    val new: List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>,
    val hot: List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>,
    val fetchedAt: Long? = null,
) {
    val isEmpty: Boolean get() = new.isEmpty() && hot.isEmpty()
}

/** 首页本季列表在榜单缓存里的键。 */
object HomeSeasonalLists {
    const val NEW: String = "HOME_SEASON_NEW"
    const val HOT: String = "HOME_SEASON_HOT"
}

/**
 * 榜单分页结果。[items] 为该页作品 + Bangumi 评分（按真实排名顺序，缺失评分为 null）；
 * [total] 为该范围过滤后的总条目数（来自 Bangumi 分页响应），供上层判断是否还有更多页——
 * 不得仅因某页返回数小于 limit 就判断结束；Bangumi 搜索页可能返回较少条目。
 */
data class RankingPage(
    val items: List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>,
    val total: Int,
)

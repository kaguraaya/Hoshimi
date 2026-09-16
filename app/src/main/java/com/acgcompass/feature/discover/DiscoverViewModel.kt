package com.acgcompass.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.PublicCacheWorkRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 发现 / 搜索页 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 职责：
 * - 持有搜索关键词的可变状态，**防抖**（[DEBOUNCE_MS]）后委托 [WorkRepository.search] 做多源搜索 /
 *   合并，结果带来源标签与 Match_Confidence。
 * - 把结果折叠为可直接渲染的 [DiscoverResultItem] 列表，对外暴露
 *   `StateFlow<UiState<List<DiscoverResultItem>>>`（七态 Page_State）。
 * - 低置信手动纠正：界面对低置信结果选择正确条目后，调用
 *   [onOverrideMatch] 委托 [WorkRepository.overrideMatch] 持久化（`userOverridden=true`）。
 *
 * 状态语义：
 * - 查询为空 → [UiState.Empty]（携带搜索引导 CTA）。
 * - 查询非空、搜索中 → [UiState.Loading]。
 * - 搜索成功但无结果 → [UiState.Empty]（换关键词引导）。
 * - 搜索失败 → 按 [AppError] 映射为对应错误态。
 *
 * 韧性：搜索流异常兜底为 [UiState.Error]，绝不让页面崩溃。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val publicCacheWorkRepository: PublicCacheWorkRepository,
    private val backlogRepository: BacklogRepository,
    private val settingsDataStore: com.acgcompass.data.datastore.SettingsDataStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** 当前搜索关键词（供搜索框双向绑定）。 */
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState: StateFlow<UiState<List<DiscoverResultItem>>> =
        _query
            // 防抖：用户停止输入 [DEBOUNCE_MS] 后再触发搜索，避免每个字符都打一次远程。
            // 空查询立即响应（无需防抖即可回到空态）。
            .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }
            .flatMapLatest { raw ->
                flow {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) {
                        emit(UiState.Empty(SEARCH_CTA))
                        return@flow
                    }
                    emit(UiState.Loading)
                    // 每个源回来就刷新一次结果，不用等最慢的源。
                    emitAll(workRepository.searchProgressive(trimmed).map { toSearchState(trimmed, it) })
                }
            }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                // 搜索结果在 ViewModel 生命周期内常驻缓存，避免返回搜索页时因订阅超时而重搜。
                // 仅当关键词真正变化（_query 发新值）才重新搜索；返回页面不触发自动重搜。
                started = SharingStarted.Lazily,
                initialValue = UiState.Empty(SEARCH_CTA),
            )

    /** 搜索框输入变化。 */
    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** 最近搜索历史（最新在前），供搜索页空态展示。 */
    val searchHistory: StateFlow<List<String>> =
        settingsDataStore.searchHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 最近浏览过的作品（搜索页空态展示「上次点开的条目」）。最近浏览 id × 本地作品，缺失的跳过。 */
    val recentlyViewedWorks: StateFlow<List<RankedWork>> =
        combine(settingsDataStore.recentlyViewedWorkIds, workRepository.observeWorks()) { ids, works ->
            val byId = works.associateBy { it.id }
            ids.mapNotNull { id -> byId[id]?.let { RankedWork(it.id, WorkRatings(it).toFilteredCard()) } }
        }.catch { emit(emptyList()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** 用户通过 IME 提交搜索时记录历史；搜索仍由 [onQueryChange] 防抖触发。 */
    fun onSearchSubmit() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { settingsDataStore.addSearchHistory(q) }
    }

    /** 点击历史项后填入搜索框、触发搜索并将该词置顶。 */
    fun onSelectHistory(query: String) {
        _query.value = query
        viewModelScope.launch { settingsDataStore.addSearchHistory(query) }
    }

    /** 删除单条搜索历史。 */
    fun onRemoveHistory(query: String) {
        viewModelScope.launch { settingsDataStore.removeSearchHistory(query) }
    }

    /** 清空全部搜索历史。 */
    fun onClearHistory() {
        viewModelScope.launch { settingsDataStore.clearSearchHistory() }
    }

    /** 「重试」：对当前关键词重新触发搜索（错误态 ）。 */
    fun onRetry() {
        val current = _query.value
        // 触发 flatMapLatest 重新计算：先清空再写回当前值，确保重新发起一次搜索。
        _query.value = ""
        _query.value = current
    }

    // region 榜单 / 评分差异 / 高级筛选

    private val _tab = MutableStateFlow(DiscoverTab.SEARCH)

    /** 当前发现页分区（搜索 / 本季榜单 / 评分差异 / 筛选）。 */
    val tab: StateFlow<DiscoverTab> = _tab.asStateFlow()

    private val _filter = MutableStateFlow(DiscoverFilter())

    /** 当前高级筛选条件。 */
    val filter: StateFlow<DiscoverFilter> = _filter.asStateFlow()

    /**
     * 发现页公共池：本地缓存的多源作品和聚合评分。
     * 榜单 / 评分差异 / 筛选三视图共用。
     *
     * 公共池不限制为待补池交集，确保新用户也能使用榜单和筛选。
     * 改为覆盖「已缓存的公共条目」作为公共发现池；个人筛选范围由 UI 文案说明。评分逐作品聚合，
     * 缺失为 `null`（不伪造）。整流异常兜底空列表。
     */
    private val worksWithRatings: StateFlow<List<WorkRatings>> =
        publicCacheWorkRepository.observePublicCachedWorks()
            .mapLatest { works ->
                works.map { work ->
                    // 发现页榜单/差异/筛选只读本地缓存评分，避免逐作品联网刷新导致卡顿/永不收敛。
                    WorkRatings(work = work, ratings = workRepository.aggregateRatingsCached(work.id))
                }
            }
            .catch { emit(emptyList()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /** 各源榜单，按数据源分别成榜并标注来源。 */
    val rankingBoards: StateFlow<List<RankingBoard>> =
        worksWithRatings
            .map { buildRankingBoards(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /** 评分差异榜，按归一化评分差距降序。 */
    val scoreDiffItems: StateFlow<List<ScoreDiffItem>> =
        worksWithRatings
            .map { buildScoreDiffBoard(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /**
     * 筛选页作品快照。进入筛选 Tab / 手动刷新时取一次「已缓存作品 + 缓存评分」快照并**洗牌**，
     * 之后不随公共池缓存写入持续刷新，避免用户浏览时列表跳动；再次进入页面时重新取样。
     * 由 [refreshFilterSnapshot] 填充。
     */
    private val filterSnapshotState = MutableStateFlow<List<WorkRatings>>(emptyList())

    /** 当前作品集合可选的筛选取值（年份 / 篇幅 / 风险 / 心情标签）。基于快照，避免缓存写库时选项抖动。 */
    val filterFacets: StateFlow<FilterFacets> =
        filterSnapshotState
            .map { buildFilterFacets(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = FilterFacets(),
            )

    /** 应用高级筛选后的作品卡片列表，保留真实 work id 供详情跳转。 */
    val filteredCards: StateFlow<List<RankedWork>> =
        combine(filterSnapshotState, _filter) { works, filter ->
            applyFilter(works, filter).map { RankedWork(it.work.id, it.toFilteredCard()) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /**
     * 刷新筛选页快照（洗牌）。首次进入 / 每次重进筛选 Tab / 公共池加载完成后调用。
     * 直接从 Room 取一次当前已缓存作品并算缓存评分（**不**订阅其后续变化，故缓存写库不再触发筛选页刷新），
     * 再洗牌 → 每次进入筛选内容有变化。旧快照在重算期间保留，避免刷新瞬间空屏。
     */
    fun refreshFilterSnapshot() {
        viewModelScope.launch {
            val works = runCatching { publicCacheWorkRepository.observePublicCachedWorks().first() }
                .getOrDefault(emptyList())
            filterSnapshotState.value = works
                .map { WorkRatings(work = it, ratings = workRepository.aggregateRatingsCached(it.id)) }
                .shuffled()
        }
    }

    /** 切换发现页分区。 */
    fun onTabSelect(tab: DiscoverTab) {
        _tab.value = tab
        // 进入「本季榜单 / 评分差异 / 筛选」时按需加载一次公共发现池（无需 token）。
        // 筛选页也需要触发首次加载，避免空缓存时始终无结果。
        if ((tab == DiscoverTab.RANKING || tab == DiscoverTab.SCORE_DIFF || tab == DiscoverTab.FILTER) &&
            !publicPoolLoadedOnce
        ) {
            loadPublicPool()
        }
        // 进入榜单时按需加载一次真实排行（默认总榜）。
        if (tab == DiscoverTab.RANKING && scopedRanking.value is UiState.Loading) {
            loadInitialRanking(_rankingScope.value)
        }
        // 进入评分差异页时按需回填第二来源评分（内部自等公共池就绪），使差异榜不再恒空。
        if (tab == DiscoverTab.SCORE_DIFF) {
            backfillScoreDiffRatings()
        }
        // 每次进入筛选 Tab 重取并洗牌快照；不订阅后续缓存写入。
        if (tab == DiscoverTab.FILTER) {
            refreshFilterSnapshot()
        }
    }

    // region 真实排行子分类（总榜 / 今年 / 本季）

    private val _rankingScope = MutableStateFlow(RankingScope.OVERALL)

    /** 当前榜单时间范围子分类。 */
    val rankingScope: StateFlow<RankingScope> = _rankingScope.asStateFlow()

    private val _scopedRanking = MutableStateFlow<UiState<List<RankedWork>>>(UiState.Loading)

    /** 当前范围的 Bangumi 真实排行榜（七态）。 */
    val scopedRanking: StateFlow<UiState<List<RankedWork>>> = _scopedRanking.asStateFlow()

    private val _rankingLoadingMore = MutableStateFlow(false)

    /** 是否正在加载下一页榜单（触底加载更多时的页脚指示）。 */
    val rankingLoadingMore: StateFlow<Boolean> = _rankingLoadingMore.asStateFlow()

    private val _rankingCanLoadMore = MutableStateFlow(true)

    /** 当前范围是否还有更多可加载（分页到底后置 false，隐藏「加载更多」）。 */
    val rankingCanLoadMore: StateFlow<Boolean> = _rankingCanLoadMore.asStateFlow()

    /** 某范围的分页累积状态（会话内存）。[nextOffset] 为下一页的真实排名 offset。 */
    private data class RankingScopeState(
        val items: List<RankedWork>,
        val nextOffset: Int,
        val canLoadMore: Boolean,
    )

    /** 各范围已累积的榜单分页状态，切回不重载（替代旧的整段内存缓存）。 */
    private val scopeStates = mutableMapOf<RankingScope, RankingScopeState>()
    private var rankingGeneration: Long = 0L

    /** 选择榜单范围（总榜 / 今年 / 本季）。命中会话状态直接用，否则走「缓存秒开 + 联网刷新首页」。 */
    fun onSelectRankingScope(scope: RankingScope) {
        if (_rankingScope.value == scope && _scopedRanking.value is UiState.Success) return
        rankingGeneration += 1L
        val generation = rankingGeneration
        _rankingScope.value = scope
        val cached = scopeStates[scope]
        if (cached != null) {
            publishScopeState(cached)
        } else {
            _scopedRanking.value = UiState.Loading
            _rankingCanLoadMore.value = true
            _rankingLoadingMore.value = false
            loadInitialRanking(scope, generation)
        }
    }

    /** 重试 / 刷新当前范围排行（强制重载，丢弃会话累积）。 */
    fun onRetryRanking() {
        rankingGeneration += 1L
        scopeStates.remove(_rankingScope.value)
        _scopedRanking.value = UiState.Loading
        loadInitialRanking(_rankingScope.value, rankingGeneration)
    }

    /** 把某范围的累积状态发布到对外 StateFlow（含可加载更多标记）。 */
    private fun publishScopeState(state: RankingScopeState) {
        _scopedRanking.value =
            if (state.items.isEmpty()) UiState.Empty(NO_RANKING_CTA) else UiState.Success(state.items)
        _rankingCanLoadMore.value = state.canLoadMore
        _rankingLoadingMore.value = false
    }

    /**
     * 加载某范围榜单首页（offset=0）。
     * 先用本地持久化缓存秒开（若有），再联网刷新首页并覆盖；联网失败且无缓存时显示错误态。
     */
    private fun loadInitialRanking(scope: RankingScope, generation: Long = rankingGeneration) {
        scopeStates[scope]?.let {
            publishScopeState(it)
            return
        }
        _rankingLoadingMore.value = false
        _rankingCanLoadMore.value = true
        viewModelScope.launch {
            // 冷启动缓存——立即展示上次持久化的榜单顺序，避免白屏等待网络。
            val cached = workRepository.getCachedRanking(scope.name)
            if (cached.isNotEmpty()) {
                if (shouldPublishRankingResult(scope, _rankingScope.value, generation, rankingGeneration)) {
                    _scopedRanking.value = UiState.Success(cached.map { (w, e) -> rankedCardOf(w, e) })
                }
            } else if (shouldPublishRankingResult(scope, _rankingScope.value, generation, rankingGeneration)) {
                _scopedRanking.value = UiState.Loading
            }
            // 联网刷新首页。
            when (
                val r = workRepository.loadBangumiRankingPage(
                    airDateOf(scope),
                    offset = 0,
                    limit = RANKING_PAGE_SIZE,
                )
            ) {
                is AppResult.Success -> {
                    val page = r.data
                    val items = page.items.map { (w, e) -> rankedCardOf(w, e) }
                    val state = RankingScopeState(
                        items = items,
                        // offset 按实际返回数推进（某页短返回也不跳号）；canLoadMore 用 total 判定。
                        nextOffset = items.size,
                        canLoadMore = items.isNotEmpty() && items.size < page.total,
                    )
                    scopeStates[scope] = state
                    workRepository.saveRankingCache(scope.name, items.map { it.workId })
                    if (shouldPublishRankingResult(scope, _rankingScope.value, generation, rankingGeneration)) {
                        publishScopeState(state)
                    }
                }
                is AppResult.Failure -> {
                    // 已用缓存秒开则保留缓存内容，仅在无缓存时显示错误态。
                    if (shouldPublishRankingResult(scope, _rankingScope.value, generation, rankingGeneration) &&
                        _scopedRanking.value !is UiState.Success
                    ) {
                        _scopedRanking.value = r.error.toUiState()
                    }
                }
            }
        }
    }

    /** 触底加载下一页并追加（按真实排名 offset 递增，跨页去重）。 */
    fun onLoadMoreRanking() {
        val scope = _rankingScope.value
        val state = scopeStates[scope] ?: return
        val generation = rankingGeneration
        if (!state.canLoadMore || _rankingLoadingMore.value) return
        _rankingLoadingMore.value = true
        viewModelScope.launch {
            when (
                val r = workRepository.loadBangumiRankingPage(
                    airDateOf(scope),
                    offset = state.nextOffset,
                    limit = RANKING_PAGE_SIZE,
                )
            ) {
                is AppResult.Success -> {
                    val page = r.data
                    val existingIds = state.items.mapTo(mutableSetOf()) { it.workId }
                    val appended = page.items.map { (w, e) -> rankedCardOf(w, e) }
                        .filter { it.workId !in existingIds }
                    val merged = state.items + appended
                    // offset 按服务端本页返回数推进（非 RANKING_PAGE_SIZE，避免短返回跳号）；
                    // canLoadMore 用 total 判定，并以「本页非空」兜底防空页死循环。
                    val newNextOffset = state.nextOffset + page.items.size
                    val newState = RankingScopeState(
                        items = merged,
                        nextOffset = newNextOffset,
                        canLoadMore = page.items.isNotEmpty() && newNextOffset < page.total,
                    )
                    scopeStates[scope] = newState
                    workRepository.saveRankingCache(scope.name, merged.map { it.workId })
                    if (shouldPublishRankingResult(scope, _rankingScope.value, generation, rankingGeneration)) {
                        publishScopeState(newState)
                    }
                }
                is AppResult.Failure -> {
                    // 加载更多失败：保留已加载内容，停掉指示，下次触底可重试。
                    _rankingLoadingMore.value = false
                }
            }
        }
    }

    private fun airDateOf(scope: RankingScope): List<String>? = when (scope) {
        RankingScope.OVERALL -> null
        RankingScope.YEAR -> yearAirDateRange()
        RankingScope.SEASON -> seasonAirDateRange()
    }

    private fun yearAirDateRange(): List<String> {
        val now = java.time.LocalDate.now()
        return listOf(">=${now.year}-01-01", "<${now.year + 1}-01-01")
    }

    private fun seasonAirDateRange(): List<String> {
        val now = java.time.LocalDate.now()
        val startMonth = ((now.monthValue - 1) / 3) * 3 + 1
        val start = java.time.LocalDate.of(now.year, startMonth, 1)
        val end = start.plusMonths(3)
        val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        return listOf(">=${start.format(fmt)}", "<${end.format(fmt)}")
    }

    // endregion

    private var publicPoolLoadedOnce = false

    private val _poolLoading = MutableStateFlow(false)

    /** 公共发现池是否正在加载。 */
    val poolLoading: StateFlow<Boolean> = _poolLoading.asStateFlow()

    private val _poolError = MutableStateFlow<String?>(null)

    /** 公共发现池加载错误原因；`null` 表示无错误。 */
    val poolError: StateFlow<String?> = _poolError.asStateFlow()

    /** 加载公共发现池（本季 / Top 榜单）。成功后 observeWorks 流自动刷新榜单/差异/筛选。 */
    fun loadPublicPool() {
        if (_poolLoading.value) return
        _poolLoading.value = true
        _poolError.value = null
        viewModelScope.launch {
            when (val r = workRepository.loadPublicDiscovery()) {
                is AppResult.Success -> {
                    publicPoolLoadedOnce = true
                    // 公共池写库完成后刷新一次筛选快照，保证首次进入即可看到洗牌后的内容。
                    if (_tab.value == DiscoverTab.FILTER) refreshFilterSnapshot()
                }
                is AppResult.Failure -> {
                    _poolError.value = r.error.cause
                }
            }
            _poolLoading.value = false
        }
    }

    private var scoreDiffBackfilledOnce = false

    private val _scoreDiffBackfilling = MutableStateFlow(false)

    /** 评分差异回填进行中（评分差异页可用作页脚指示）。 */
    val scoreDiffBackfilling: StateFlow<Boolean> = _scoreDiffBackfilling.asStateFlow()

    /** 该作品当前缓存中「有效（分值>0）来源」的个数。 */
    private fun WorkRatings.validSourceCount(): Int =
        ratings?.perSource?.values?.count { it != null && it.score > 0f } ?: 0

    /** 该作品各源评分人数的最大值（择优回填顺序用，越热门越可能匹配到第二源）。 */
    private fun WorkRatings.topVotes(): Int =
        ratings?.perSource?.values?.filterNotNull()?.maxOfOrNull { it.voteCount } ?: 0

    /**
     * 评分差异回填。评分差异要求单作品至少有两个有效来源评分，而发现页批量只读本地缓存，
     * 公共池作品多半仅主源一条评分 → 差异榜恒空。进入该页时，对「当前仅 1 个有效来源、评分人数最多」的
     * 前 [SCORE_DIFF_BACKFILL_LIMIT] 部作品按需联网交叉验证（[WorkRepository.aggregateRatings] →
     * crossValidateRatings 落第二源），best-effort、有预算上限、失败吞掉；写回后 observeWorks 自动刷新差异榜
     *。先等公共池写回再选目标，避免「池未就绪 → 0 目标 → 提前 latch」。
     */
    fun backfillScoreDiffRatings() {
        if (scoreDiffBackfilledOnce || _scoreDiffBackfilling.value) return
        _scoreDiffBackfilling.value = true
        viewModelScope.launch {
            try {
                val pool = withTimeoutOrNull(POOL_READY_TIMEOUT_MS) {
                    worksWithRatings.first { it.isNotEmpty() }
                } ?: worksWithRatings.value
                if (pool.isEmpty()) return@launch // 池仍未就绪，不 latch，下次进入再试。
                val targets = pool
                    .filter { it.validSourceCount() == 1 }
                    .sortedByDescending { it.topVotes() }
                    .take(SCORE_DIFF_BACKFILL_LIMIT)
                    .map { it.work.id }
                // 逐部去其它平台查评分（按标题交叉匹配 AniList / MAL 等），查到就落库，差异榜随之出现。
                // 一次进入最多查 [SCORE_DIFF_BACKFILL_LIMIT] 部、按顺序进行，不和详情页抢限流额度；失败的跳过。
                for (id in targets) {
                    runCatching { workRepository.aggregateRatings(id, forceRefresh = false) }
                }
                scoreDiffBackfilledOnce = true
            } finally {
                _scoreDiffBackfilling.value = false
            }
        }
    }

    /** 更新高级筛选条件。 */
    fun onFilterChange(filter: DiscoverFilter) {
        _filter.value = filter
    }

    /** 清空全部筛选条件。 */
    fun onClearFilter() {
        _filter.value = DiscoverFilter()
    }

    // endregion

    /**
     * 低置信手动纠正：把某规范化作品的源链接固定为用户选中的条目。
     *
     * @param localId 本地规范化作品 id（被纠正的作品）。
     * @param chosen 用户选中的正确源条目引用（`userOverridden=true`）。
     */
    fun onOverrideMatch(localId: String, chosen: SourceRef) {
        viewModelScope.launch {
            workRepository.overrideMatch(localId, chosen)
        }
    }

    /** 把一版（可能是中间版）跨源合并结果映射为 [UiState]。 */
    private fun toSearchState(query: String, result: AppResult<List<WorkMatch>>): UiState<List<DiscoverResultItem>> =
        when (result) {
            is AppResult.Success -> {
                val items = sortResults(query, mergeCrossSource(result.data))
                if (items.isEmpty()) UiState.Empty(NO_RESULT_CTA) else UiState.Success(items)
            }

            is AppResult.Failure -> result.error.toUiState()
        }

    /**
     * 排序依次考虑精确匹配、合并来源数、匹配置信度和来源可信度。
     * 评分人数/热度在搜索期不可得（评分在详情期才拉取），故用「合并来源数」作为热度近似的二级信号；
     * 来源可信度仅作最后裁决，不让低信号高可信源压过高信号准确条目。
     */
    private fun sortResults(query: String, items: List<DiscoverResultItem>): List<DiscoverResultItem> {
        val q = com.acgcompass.domain.matching.normalizeCompact(query)
        fun exact(it: DiscoverResultItem): Int =
            if (q.isNotEmpty() && com.acgcompass.domain.matching.normalizeCompact(it.card.title) == q) 1 else 0
        fun sourceCount(it: DiscoverResultItem): Int = maxOf(it.mergeCandidates.size, 1)
        fun trust(it: DiscoverResultItem): Int = when (it.sourceId) {
            com.acgcompass.domain.model.SourceId.BANGUMI -> 5
            com.acgcompass.domain.model.SourceId.ANILIST -> 4
            com.acgcompass.domain.model.SourceId.MAL -> 3
            com.acgcompass.domain.model.SourceId.TENRAI -> 2
            com.acgcompass.domain.model.SourceId.JIKAN -> 1
            com.acgcompass.domain.model.SourceId.VNDB -> 1
        }
        return items.sortedWith(
            compareByDescending<DiscoverResultItem> { exact(it) }
                .thenByDescending { sourceCount(it) }
                .thenByDescending { it.matchConfidence }
                .thenByDescending { trust(it) },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 350L

        /** 榜单每页条数（触底加载更多的步长）。 */
        const val RANKING_PAGE_SIZE = 30

        /** 评分差异回填单次联网交叉验证的作品数上限（控请求量）。 */
        const val SCORE_DIFF_BACKFILL_LIMIT = 20

        /** 等待公共池写回（observeWorks）的超时，超时则用当前快照（毫秒）。 */
        const val POOL_READY_TIMEOUT_MS = 8_000L
    }
}

/**
 * 把 [AppError] 映射到对应的非数据 [UiState]：网络 / 未授权 / 限流分别映射到专用态，
 * 其余统一为 [UiState.Error]，由 `StateScaffold` 渲染错误卡片四要素。
 */
private fun AppError.toUiState(): UiState<Nothing> = when (this) {
    is AppError.Network -> UiState.NoNetwork
    is AppError.Unauthorized -> UiState.Unauthorized
    is AppError.RateLimited -> UiState.RateLimited
    else -> UiState.Error(this)
}

internal fun shouldPublishRankingResult(
    resultScope: RankingScope,
    selectedScope: RankingScope,
    resultGeneration: Long,
    currentGeneration: Long,
): Boolean = resultScope == selectedScope && resultGeneration == currentGeneration

package com.acgcompass.feature.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.AppSearchField
import com.acgcompass.core.designsystem.AppSearchFieldState
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.PrismMetrics
import com.acgcompass.core.designsystem.SectionHeader
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.LocalHeaderInset
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarScrim

/** 发现页分区（Tab）横滑切换动画时长（ms）：快速、有方向感、不拖沓。 */
private const val TAB_ANIM_MS = 220

/**
 * 发现 / 搜索页路由入口。连接 [DiscoverViewModel] 并把状态与导航回调下发给
 * 无状态的 [DiscoverScreen]。
 *
 * @param onOpenWork 点击结果进入作品详情（`detailRoute(workId)`）。
 */
@Suppress("LongMethod") // 收集各状态流并逐个传给无状态界面
@Composable
fun DiscoverRoute(
    onOpenWork: (String) -> Unit,
    onOpenExploreQueue: () -> Unit = {},
    onOpenVisualNovels: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val showMessage = com.acgcompass.core.ui.LocalAppMessenger.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            showMessage(it)
            viewModel.clearMessage()
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val rankingBoards by viewModel.rankingBoards.collectAsStateWithLifecycle()
    val scoreDiffItems by viewModel.scoreDiffItems.collectAsStateWithLifecycle()
    val scoreDiffBackfilling by viewModel.scoreDiffBackfilling.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filterFacets by viewModel.filterFacets.collectAsStateWithLifecycle()
    val filteredCards by viewModel.filteredCards.collectAsStateWithLifecycle()
    val poolLoading by viewModel.poolLoading.collectAsStateWithLifecycle()
    val poolError by viewModel.poolError.collectAsStateWithLifecycle()
    val rankingScope by viewModel.rankingScope.collectAsStateWithLifecycle()
    val scopedRanking by viewModel.scopedRanking.collectAsStateWithLifecycle()
    val rankingLoadingMore by viewModel.rankingLoadingMore.collectAsStateWithLifecycle()
    val rankingCanLoadMore by viewModel.rankingCanLoadMore.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewedWorks.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    DiscoverScreen(
        state = state,
        query = query,
        searchHistory = searchHistory,
        recentlyViewed = recentlyViewed,
        tab = tab,
        rankingBoards = rankingBoards,
        scoreDiffItems = scoreDiffItems,
        scoreDiffBackfill = ScoreDiffBackfillUi(running = scoreDiffBackfilling),
        filter = filter,
        filterFacets = filterFacets,
        filteredCards = filteredCards,
        poolLoading = poolLoading,
        poolError = poolError,
        rankingScope = rankingScope,
        scopedRanking = scopedRanking,
        rankingLoadingMore = rankingLoadingMore,
        rankingCanLoadMore = rankingCanLoadMore,
        onSelectRankingScope = viewModel::onSelectRankingScope,
        onRetryRanking = viewModel::onRetryRanking,
        onLoadMoreRanking = viewModel::onLoadMoreRanking,
        onLoadPool = viewModel::loadPublicPool,
        onQueryChange = viewModel::onQueryChange,
        onSearchSubmit = viewModel::onSearchSubmit,
        onSelectHistory = viewModel::onSelectHistory,
        onRemoveHistory = viewModel::onRemoveHistory,
        onClearHistory = viewModel::onClearHistory,
        onRetry = viewModel::onRetry,
        onOpenWork = onOpenWork,
        onOpenExploreQueue = onOpenExploreQueue,
        onOpenVisualNovels = onOpenVisualNovels,
        onOverrideMatch = viewModel::onOverrideMatch,
        onTabSelect = viewModel::onTabSelect,
        onFilterChange = viewModel::onFilterChange,
        onClearFilter = viewModel::onClearFilter,
        modifier = modifier,
        refreshing = refreshing,
        onPullRefresh = viewModel::onPullRefresh,
    )
}

/**
 * 无状态发现 / 搜索界面（Material 3）。
 *
 * 顶部为搜索框（支持中文 / 日文 / 罗马音 / 英文 / 别名）；下方按 [UiState] 渲染搜索结果。
 * 每条结果为统一作品卡片并旁标来源 + Match_Confidence。低置信结果附「手动选择正确条目」
 * 入口，确认后回调 [onOverrideMatch]。
 *
 * 榜单、评分差异榜和高级筛选由同一页面的状态模型接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod") // 发现页四个分区的全部状态与回调；布局拆分在各分区函数里
@Composable
fun DiscoverScreen(
    state: UiState<List<DiscoverResultItem>>,
    query: String,
    searchHistory: List<String>,
    recentlyViewed: List<RankedWork> = emptyList(),
    tab: DiscoverTab,
    rankingBoards: List<RankingBoard>,
    scoreDiffItems: List<ScoreDiffItem>,
    filter: DiscoverFilter,
    filterFacets: FilterFacets,
    filteredCards: List<RankedWork>,
    poolLoading: Boolean,
    poolError: String?,
    rankingScope: RankingScope,
    scopedRanking: UiState<List<RankedWork>>,
    rankingLoadingMore: Boolean,
    rankingCanLoadMore: Boolean,
    onSelectRankingScope: (RankingScope) -> Unit,
    onRetryRanking: () -> Unit,
    onLoadMoreRanking: () -> Unit,
    onLoadPool: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenExploreQueue: () -> Unit = {},
    onOpenVisualNovels: () -> Unit = {},
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
    onTabSelect: (DiscoverTab) -> Unit,
    onFilterChange: (DiscoverFilter) -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
    scoreDiffBackfill: ScoreDiffBackfillUi = ScoreDiffBackfillUi(),
    refreshing: Boolean = false,
    onPullRefresh: () -> Unit = {},
) {
    // 顶栏 + 分区药丸共用一层遮罩，内容从下面滚过；搜索框是列表的第一项，跟着内容一起滚走。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.topBarScrim()) {
                AcgTopLevelTopBar(
                    title = "发现",
                    scrim = false,
                    actions = {
                        IconButton(onClick = onOpenVisualNovels) {
                            Icon(Icons.Outlined.AutoStories, contentDescription = "视觉小说")
                        }
                        // 探索队列入口使用与应用图标一致的指南针图形。
                        ExploreQueueCompassAction(onClick = onOpenExploreQueue)
                    },
                )
                DiscoverTabRow(selected = tab, onTabSelect = onTabSelect)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // 筛选只在本地数据上进行；搜索框为空时没有可刷新的结果。
            // 为你排行有自己的下拉（重新计算），外层不处理。
            val pullEnabled = tab != DiscoverTab.FILTER && tab != DiscoverTab.TASTE &&
                !(tab == DiscoverTab.SEARCH && query.isBlank())
            com.acgcompass.core.designsystem.AcgPullRefreshBox(
                refreshing = refreshing,
                onRefresh = onPullRefresh,
                enabled = pullEnabled,
                indicatorTopPadding = innerPadding.calculateTopPadding(),
                modifier = Modifier.fillMaxSize(),
            ) {
                CompositionLocalProvider(LocalHeaderInset provides innerPadding.calculateTopPadding()) {
                    // 分区切换动画（去掉淡入淡出，纯整屏横滑，更自然，参考 Kotatsu / ViewPager）：
                    // 序号增大（如 搜索→榜单）新内容自右整屏滑入、旧内容整屏向左滑出；序号减小则反向——与顶部 Tab
                    // 左右位置一致，符合直觉。整屏位移（dir*w）而非小位移，避免去 fade 后新旧内容重叠显脏。
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val dir = if (forward) 1 else -1
                            slideInHorizontally(tween(TAB_ANIM_MS)) { w -> dir * w }
                                .togetherWith(slideOutHorizontally(tween(TAB_ANIM_MS)) { w -> -dir * w })
                        },
                        label = "discoverTab",
                    ) { current ->
                        when (current) {
                            DiscoverTab.SEARCH -> SearchSection(
                                state = state,
                                query = query,
                                searchHistory = searchHistory,
                                recentlyViewed = recentlyViewed,
                                onQueryChange = onQueryChange,
                                onSearchSubmit = onSearchSubmit,
                                onSelectHistory = onSelectHistory,
                                onRemoveHistory = onRemoveHistory,
                                onClearHistory = onClearHistory,
                                onRetry = onRetry,
                                onOpenWork = onOpenWork,
                                onOverrideMatch = onOverrideMatch,
                            )

                            DiscoverTab.RANKING -> RankingSection(
                                boards = rankingBoards,
                                poolLoading = poolLoading,
                                poolError = poolError,
                                rankingScope = rankingScope,
                                scopedRanking = scopedRanking,
                                rankingLoadingMore = rankingLoadingMore,
                                rankingCanLoadMore = rankingCanLoadMore,
                                onSelectRankingScope = onSelectRankingScope,
                                onRetryRanking = onRetryRanking,
                                onLoadMoreRanking = onLoadMoreRanking,
                                onLoadPool = onLoadPool,
                                onOpenWork = onOpenWork,
                            )

                            DiscoverTab.TASTE -> com.acgcompass.feature.tasteranking.TasteRankingBoard(
                                onOpenWork = onOpenWork,
                                contentPadding = PaddingValues(
                                    start = ScreenContentPadding.Horizontal,
                                    end = ScreenContentPadding.Horizontal,
                                    top = ScreenContentPadding.UnderBarTop,
                                    bottom = ScreenContentPadding.Bottom,
                                ),
                                indicatorTopPadding = innerPadding.calculateTopPadding(),
                            )

                            DiscoverTab.SCORE_DIFF -> ScoreDiffSection(
                                items = scoreDiffItems,
                                backfill = scoreDiffBackfill,
                                poolLoading = poolLoading,
                                poolError = poolError,
                                onLoadPool = onLoadPool,
                                onOpenWork = onOpenWork,
                            )

                            DiscoverTab.FILTER -> FilterSection(
                                filter = filter,
                                facets = filterFacets,
                                results = filteredCards,
                                onFilterChange = onFilterChange,
                                onClearFilter = onClearFilter,
                                onOpenWork = onOpenWork,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 发现页顶栏探索队列入口使用自绘指南针图标，点击进入主动探索队列。
 * 图标随主题着色，并与底部导航的发现图标区分。
 */
@Composable
private fun ExploreQueueCompassAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        // 与顶栏其它图标同色（取 IconButton 提供的内容色）。
        val tint = androidx.compose.material3.LocalContentColor.current
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = "探索队列" },
        ) {
            val s = size.minDimension
            val c = Offset(size.width / 2f, size.height / 2f)
            // 罗盘圆环。
            drawCircle(color = tint, radius = s * 0.44f, center = c, style = Stroke(width = s * 0.08f))
            // 四芒星指针（南北长、东西短），指向感明确，尖端抵圆环。
            val longR = s * 0.42f
            val shortR = s * 0.17f
            val d = s * 0.085f
            val needle = Path().apply {
                moveTo(c.x, c.y - longR)
                lineTo(c.x + d, c.y - d)
                lineTo(c.x + shortR, c.y)
                lineTo(c.x + d, c.y + d)
                lineTo(c.x, c.y + longR)
                lineTo(c.x - d, c.y + d)
                lineTo(c.x - shortR, c.y)
                lineTo(c.x - d, c.y - d)
                close()
            }
            drawPath(needle, color = tint)
        }
    }
}

/** 发现页分区切换（搜索 / 本季榜单 / 评分差异 / 筛选）。 */
@Composable
private fun DiscoverTabRow(selected: DiscoverTab, onTabSelect: (DiscoverTab) -> Unit) {
    // 照 FlClash 的分组切换：一排药丸，当前分区实心主色；不用带下划线的 Material 标签栏。
    PillTabs(
        options = DiscoverTab.entries,
        selected = selected,
        onSelect = onTabSelect,
        label = { it.label },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        scrollable = true,
    )
}

/**
 * 搜索分区：搜索框是列表的第一项，下面依次是历史搜索、最近浏览（查询为空时）或搜索结果——
 * 它们在同一个列表里一起滚动，往上滑时搜索框跟着滚走，可见区域更大。
 */
@Suppress("LongParameterList") // 搜索分区的全部状态与回调
@Composable
private fun SearchSection(
    state: UiState<List<DiscoverResultItem>>,
    query: String,
    searchHistory: List<String>,
    recentlyViewed: List<RankedWork>,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    val showStart = query.isBlank() && (recentlyViewed.isNotEmpty() || searchHistory.isNotEmpty())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(PrismMetrics.CardGap),
    ) {
        item(key = "search_field") {
            SearchBar(query = query, onQueryChange = onQueryChange, onSearchSubmit = onSearchSubmit)
        }
        if (showStart) {
            if (searchHistory.isNotEmpty()) {
                item(key = "history") {
                    SearchHistorySection(
                        history = searchHistory,
                        onSelectHistory = onSelectHistory,
                        onRemoveHistory = onRemoveHistory,
                        onClearHistory = onClearHistory,
                    )
                }
            }
            if (recentlyViewed.isNotEmpty()) {
                item(key = "recent_title") { SectionHeader("最近浏览") }
                items(recentlyViewed, key = { "recent_${it.workId}" }) { rw ->
                    WorkCard(model = rw.card, onClick = { onOpenWork(rw.workId) })
                }
            }
        } else {
            searchResultItems(
                state = state,
                onRetry = onRetry,
                onOpenWork = onOpenWork,
                onOverrideMatch = onOverrideMatch,
            )
        }
    }
}

/** 搜索结果：成功时逐条列出；加载 / 出错 / 空结果时在搜索框下方占一块区域显示状态。 */
private fun LazyListScope.searchResultItems(
    state: UiState<List<DiscoverResultItem>>,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    val results = when (state) {
        is UiState.Success -> state.data
        is UiState.PartialMissing -> state.data
        else -> null
    }
    if (results == null) {
        item(key = "search_state") {
            StateScaffold(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = STATE_BLOCK_HEIGHT),
                onRetry = onRetry,
                onCta = {},
            ) {}
        }
        return
    }
    // 低置信提示只在顶部统一展示一处。
    if (results.any { it.isLowConfidence }) {
        item(key = "low_conf_hint") {
            PrismCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "部分结果匹配置信度较低，自动匹配可能不准确，打开详情后可手动纠正。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    items(results, key = { "${it.sourceId}_${it.workId}" }) { item ->
        DiscoverResultCard(
            item = item,
            candidates = results,
            manageMode = false,
            onOpenWork = onOpenWork,
            onOverrideMatch = onOverrideMatch,
        )
    }
}

private val STATE_BLOCK_HEIGHT = 360.dp

/** 搜索框：填充的药丸，单行输入、可清除；ViewModel 防抖触发搜索，按键盘的搜索键时记录历史。 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onSearchSubmit: () -> Unit) {
    val focusManager = LocalFocusManager.current
    AppSearchField(
        state = AppSearchFieldState(
            value = query,
            placeholder = "搜索作品名（中 / 日 / 罗马音 / 别名）",
        ),
        onValueChange = onQueryChange,
        onSearch = {
            onSearchSubmit()
            focusManager.clearFocus()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 最近搜索：一张卡片里的列表（时钟图标 + 关键词，点一下就搜，右侧删除单条），标题行右侧「清空」；
 * 超过 [HISTORY_COLLAPSED] 条时折叠，可展开全部。
 */
@Composable
private fun SearchHistorySection(
    history: List<String>,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) history else history.take(HISTORY_COLLAPSED)
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近搜索", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearHistory) { Text("清空") }
            }
            shown.forEachIndexed { index, item ->
                if (index > 0) FlatDivider(Modifier.padding(start = 44.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(PrismCorner.Badge)
                        .clickable { onSelectHistory(item) }
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveHistory(item) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除「$item」",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (history.size > HISTORY_COLLAPSED) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(if (expanded) "收起" else "展开全部 ${history.size} 条")
                }
            }
        }
    }
}

private const val HISTORY_COLLAPSED = 6

/**
 * 单条搜索结果：统一作品卡片 + （低置信时）手动纠正横幅与入口。
 */
@Composable
private fun DiscoverResultCard(
    item: DiscoverResultItem,
    candidates: List<DiscoverResultItem>,
    manageMode: Boolean,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    var showPicker by remember(item.workId, item.sourceId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkCard(model = item.card, onClick = { onOpenWork(item.workId) })

        if (manageMode && item.isLowConfidence) {
            // 「手动纠正」默认隐藏，仅在「管理匹配模式」下对低置信结果显示。
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
            ) {
                Text("手动纠正", style = MaterialTheme.typography.labelMedium)
            }
        } else if (manageMode && item.mergeCandidates.size > 1) {
            // 多源合并卡的「调整」入口仅在「管理匹配模式」下出现，普通浏览不打扰。
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
            ) {
                Text("已合并 ${item.mergeCandidates.size} 个来源 · 调整", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showPicker) {
        MatchPickerDialog(
            target = item,
            candidates = item.mergeCandidates.ifEmpty { candidates },
            onDismiss = { showPicker = false },
            onChoose = { chosen ->
                // 把目标作品的源链接固定为用户选中的条目（userOverridden=true）。
                onOverrideMatch(item.workId, chosen.toSourceRef())
                showPicker = false
            },
        )
    }
}

/** 低置信横幅：提示匹配置信度较低，并提供「手动选择正确条目」入口。 */
@Composable
private fun LowConfidenceBanner(onPick: () -> Unit) {
    PrismCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Surfaces.raisedColor,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "匹配置信度较低",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "自动匹配可能不准确，可手动选择正确条目以固定该作品的数据源。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onPick) {
                Text("手动选择正确条目")
            }
        }
    }
}

/**
 * 手动纠正选择对话框。列出当前搜索的全部候选条目，用户选中一项即作为正确链接。
 * 由调用方写入 `overrideMatch`（`userOverridden=true`）。
 */
@Composable
private fun MatchPickerDialog(
    target: DiscoverResultItem,
    candidates: List<DiscoverResultItem>,
    onDismiss: () -> Unit,
    onChoose: (DiscoverResultItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择正确条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "为「${target.card.title}」选择正确的数据源条目。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                candidates.forEach { candidate ->
                    FilledTonalButton(
                        onClick = { onChoose(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${candidate.sourceId.discoverLabel()} · ${candidate.card.title} · " +
                                confidenceLabel(candidate.matchConfidence),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun DiscoverScreenEmptyPreview() {
    AcgCompassTheme {
        DiscoverScreen(
            state = UiState.Empty(SEARCH_CTA),
            query = "",
            searchHistory = emptyList(),
            tab = DiscoverTab.SEARCH,
            rankingBoards = emptyList(),
            scoreDiffItems = emptyList(),
            filter = DiscoverFilter(),
            filterFacets = FilterFacets(),
            filteredCards = emptyList(),
            poolLoading = false,
            poolError = null,
            rankingScope = RankingScope.OVERALL,
            scopedRanking = UiState.Empty(SEARCH_CTA),
            rankingLoadingMore = false,
            rankingCanLoadMore = false,
            onSelectRankingScope = {},
            onRetryRanking = {},
            onLoadMoreRanking = {},
            onLoadPool = {},
            onQueryChange = {},
            onSearchSubmit = {},
            onSelectHistory = {},
            onRemoveHistory = {},
            onClearHistory = {},
            onRetry = {},
            onOpenWork = {},
            onOverrideMatch = { _, _ -> },
            onTabSelect = {},
            onFilterChange = {},
            onClearFilter = {},
        )
    }
}

// region 榜单 / 评分差异 / 高级筛选界面

/** 各来源榜单分区：顶部为 Bangumi 总榜、年度和季度排行，下方为本地多源缓存榜单。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankingSection(
    boards: List<RankingBoard>,
    poolLoading: Boolean,
    poolError: String?,
    rankingScope: RankingScope,
    scopedRanking: UiState<List<RankedWork>>,
    rankingLoadingMore: Boolean,
    rankingCanLoadMore: Boolean,
    onSelectRankingScope: (RankingScope) -> Unit,
    onRetryRanking: () -> Unit,
    onLoadMoreRanking: () -> Unit,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    // 触底加载更多——共享 LazyListState，最后可见项接近末尾且仍可加载时触发下一页。
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, rankingCanLoadMore) {
        if (shouldLoadMore && rankingCanLoadMore) onLoadMoreRanking()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bangumi 真实排行子分类切换。
        item(key = "scope_tabs") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Bangumi 排行榜",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RankingScope.entries.forEach { scope ->
                        FlatFilterChip(
                            selected = scope == rankingScope,
                            onClick = { onSelectRankingScope(scope) },
                            label = { Text(scope.label) },
                        )
                    }
                }
            }
        }
        // 当前范围真实排行（七态）。
        when (val s = scopedRanking) {
            is UiState.Loading -> item(key = "rank_loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在加载排行榜…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UiState.Success -> {
                itemsIndexed(s.data, key = { _, rw -> "rank_${rw.workId}" }) { index, rw ->
                    RankedWorkCard(rank = index + 1, card = rw.card, onClick = { onOpenWork(rw.workId) })
                }
                // 分页页脚——加载中显示指示；到底显示提示。
                if (rankingLoadingMore) {
                    item(key = "rank_loading_more") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("  正在加载更多…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (!rankingCanLoadMore && s.data.isNotEmpty()) {
                    item(key = "rank_end") {
                        Text(
                            text = "已到榜单底部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            is UiState.Empty -> item(key = "rank_empty") {
                EmptyDataPlaceholder("该范围暂无排行数据，换个范围或稍后重试")
            }
            else -> item(key = "rank_error") {
                PrismCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("排行榜加载失败", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = onRetryRanking) { Text("重试") }
                    }
                }
            }
        }
        // 榜单仅保留 Bangumi 真实排行（其他源榜单后续再加）。
        item(key = "rank_note") {
            Text(
                text = "按 Bangumi 的排名排序，尚未获得排名的条目不显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 评分差异页「补充其它平台评分」的进行状态。 */
data class ScoreDiffBackfillUi(val running: Boolean = false)

/** 评分差异榜分区：中性说明 + 差距榜；无数据时显示「暂无数据」。 */
@Suppress("LongParameterList", "LongMethod") // 一个分区的完整列表
@Composable
private fun ScoreDiffSection(
    items: List<ScoreDiffItem>,
    poolLoading: Boolean,
    poolError: String?,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
    backfill: ScoreDiffBackfillUi = ScoreDiffBackfillUi(),
) {
    val backfilling = backfill.running
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留白保证末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pool_status") {
            PublicPoolStatusRow(poolLoading = poolLoading, poolError = poolError, onLoadPool = onLoadPool)
        }
        item {
            PrismCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Surfaces.raisedColor,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = SCORE_DIFF_NEUTRAL_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (backfilling) {
            item(key = "backfilling") {
                Text(
                    text = "正在为热门作品补充其它平台的评分，补到的作品会陆续出现…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (items.isEmpty()) {
            item { EmptyDataPlaceholder(if (backfilling) "暂时还没有评分差距较大的作品" else "暂无评分差距较大的作品") }
        } else {
            items(items, key = { it.workId }) { diff ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkCard(model = diff.card, onClick = { onOpenWork(diff.workId) })
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = diff.spreadLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DivergenceBadge(level = diff.divergenceLevel)
                    }
                    ScoreSpreadBar(sources = diff.sourceScores)
                    Text(
                        text = diff.perSourceLabels.joinToString("  /  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 分歧程度徽标：显著=错误色，明显=中性底正文色，轻微=中性底次要色。 */
@Composable
private fun DivergenceBadge(level: DivergenceLevel) {
    val (bg, fg) = when (level) {
        DivergenceLevel.STRONG ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        // 颜色只用来表示状态：显著分歧用错误色，其余用中性底。
        DivergenceLevel.NOTABLE ->
            Surfaces.raisedColor to MaterialTheme.colorScheme.onSurface
        DivergenceLevel.SLIGHT ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = level.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 评分分布条（丰富化）：在 0–10 轨道上以高亮带表示各源最低→最高区间，并为每个来源画圆点标记，
 * 使「评分差距」客观可视。仅呈现归一化数值，不下结论。少于 2 源时不绘制。
 */
@Composable
private fun ScoreSpreadBar(sources: List<SourceScore>, modifier: Modifier = Modifier) {
    if (sources.size < 2) return
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val rangeColor = MaterialTheme.colorScheme.primary
    val dotFill = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        val cy = size.height / 2f
        val trackH = 6.dp.toPx()
        val radius = 5.dp.toPx()
        // 两端各留出圆点半径，避免端点圆点被裁切。
        val left = radius
        val usable = (size.width - radius * 2f).coerceAtLeast(1f)
        fun xOf(score: Float): Float = left + (score / 10f).coerceIn(0f, 1f) * usable
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f),
        )
        val xs = sources.map { xOf(it.score) }
        val minX = xs.minOrNull() ?: left
        val maxX = xs.maxOrNull() ?: left
        drawRoundRect(
            color = rangeColor,
            topLeft = Offset(minX, cy - trackH / 2f),
            size = Size((maxX - minX).coerceAtLeast(trackH), trackH),
            cornerRadius = CornerRadius(trackH / 2f),
        )
        xs.forEach { x ->
            drawCircle(color = dotFill, radius = radius, center = Offset(x, cy))
            drawCircle(
                color = rangeColor,
                radius = radius,
                center = Offset(x, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/** 高级筛选分区：多组 chips 驱动筛选状态，应用到结果列表。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSection(
    filter: DiscoverFilter,
    facets: FilterFacets,
    results: List<RankedWork>,
    onFilterChange: (DiscoverFilter) -> Unit,
    onClearFilter: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    // 筛选面板默认收起；状态放在列表外面，面板滑出屏幕后再滑回来仍保持原样。
    var expanded by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留白保证筛选结果末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "filter_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val activeCount = filter.activeDimensionCount()
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "公共发现池里已浏览 / 搜索过的作品 · 共 ${results.size} 部",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (activeCount > 0) "筛选 · $activeCount" else "筛选")
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
                // 展开后跟着列表一起滚，不在里面再套一层滚动；展开状态放在列表外面，滑远了也不会自己收起。
                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    DiscoverFilterPanel(filter, facets, onFilterChange, onClearFilter)
                }
            }
        }

        if (results.isEmpty()) {
            item { EmptyDataPlaceholder("没有符合筛选条件的作品") }
        } else {
            itemsIndexed(results, key = { index, rw -> "${index}_${rw.workId}" }) { _, rw ->
                WorkCard(model = rw.card, onClick = { onOpenWork(rw.workId) })
            }
        }
    }
}

@Composable
private fun SourceHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** 榜单条目：名次放在卡片封面的角标里，卡片之间不插单独的名次行。 */
@Composable
private fun RankedWorkCard(rank: Int, card: WorkCardUiModel, onClick: () -> Unit) {
    WorkCard(model = card, rank = rank, onClick = onClick)
}

/** 公共发现池加载状态行（加载中 / 错误+重试 / 提示）。 */
@Composable
private fun PublicPoolStatusRow(poolLoading: Boolean, poolError: String?, onLoadPool: () -> Unit) {
    when {
        poolLoading -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text("正在加载本季 / 公共榜单…", style = MaterialTheme.typography.bodyMedium)
        }
        poolError != null -> PrismCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加载失败：$poolError", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onLoadPool) { Text("重试") }
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "公共榜单（Jikan / MAL）无需登录即可浏览",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onLoadPool,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                ),
            ) {
                Text("刷新", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun EmptyDataPlaceholder(message: String) {
    PrismCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Surfaces.raisedColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

/** 卡片缺少作品 id，列表 key / 点击回调以标题兜底（界面层稳定标识，不影响领域数据）。 */
private fun WorkCardUiModel.titleKey(): String = title

internal fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

internal fun com.acgcompass.domain.model.MediaType.filterLabel(): String = when (this) {
    com.acgcompass.domain.model.MediaType.ANIME -> "动画"
    com.acgcompass.domain.model.MediaType.MANGA -> "漫画"
    com.acgcompass.domain.model.MediaType.NOVEL -> "小说"
    com.acgcompass.domain.model.MediaType.GAME -> "游戏"
    com.acgcompass.domain.model.MediaType.VN -> "视觉小说"
    com.acgcompass.domain.model.MediaType.OTHER -> "其他"
}

internal fun com.acgcompass.domain.model.ReleaseStatus.filterLabel(): String = when (this) {
    com.acgcompass.domain.model.ReleaseStatus.UNKNOWN -> "未知"
    com.acgcompass.domain.model.ReleaseStatus.NOT_RELEASED -> "未发布"
    com.acgcompass.domain.model.ReleaseStatus.RELEASING -> "连载中"
    com.acgcompass.domain.model.ReleaseStatus.FINISHED -> "已完结"
    com.acgcompass.domain.model.ReleaseStatus.ON_HIATUS -> "搁置"
    com.acgcompass.domain.model.ReleaseStatus.CANCELLED -> "腰斩"
}

internal fun FinishedFilter.filterLabel(): String = when (this) {
    FinishedFilter.ALL -> "全部"
    FinishedFilter.FINISHED -> "已完结"
    FinishedFilter.ONGOING -> "未完结"
}

// endregion

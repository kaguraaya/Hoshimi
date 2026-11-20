package com.acgcompass.feature.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.LocalHeaderInset
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarScrim

/** 发现页分区（Tab）横滑切换动画时长（ms）：快速、有方向感、不拖沓。 */
internal const val TAB_ANIM_MS = 220

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
internal fun ExploreQueueCompassAction(onClick: () -> Unit) {
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
internal fun DiscoverTabRow(selected: DiscoverTab, onTabSelect: (DiscoverTab) -> Unit) {
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

internal val STATE_BLOCK_HEIGHT = 360.dp

@Preview(showBackground = true)
@Composable
internal fun DiscoverScreenEmptyPreview() {
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

/** 卡片缺少作品 id，列表 key / 点击回调以标题兜底（界面层稳定标识，不影响领域数据）。 */
internal fun WorkCardUiModel.titleKey(): String = title

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

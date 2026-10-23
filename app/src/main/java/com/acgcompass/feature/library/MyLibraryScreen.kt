package com.acgcompass.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/** 导航参数键：我的库初始 Tab（状态分组）。 */
const val LIBRARY_ARG_TAB: String = "tab"

/** 我的库路由模板（含可选 Tab 参数）。 */
const val LIBRARY_ROUTE_PATTERN: String = "library?tab={$LIBRARY_ARG_TAB}"

/** 构造跳转到我的库的具体路由；[tab] 为 `null` 进入「全部」。 */
fun libraryRoute(tab: String? = null): String = if (tab.isNullOrBlank()) "library" else "library?$LIBRARY_ARG_TAB=$tab"

/**
 * 「我的库 / 我的收藏」路由入口，连接 [MyLibraryViewModel] 并向无状态屏下发状态和回调。
 *
 * @param onBack 返回上一页。
 * @param onOpenWork 进入作品详情。
 */
@Composable
fun MyLibraryRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    MyLibraryScreen(
        state = state,
        selectedTab = selectedTab,
        onSelectTab = viewModel::onSelectTab,
        onAddToBacklog = viewModel::onAddToBacklog,
        onOpenWork = onOpenWork,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * 无状态「我的库」界面（Material 3）。统一 [AcgScreenScaffold]（标题「我的库」+ 返回）+
 * [ScreenContentPadding.lazyList]，并按全部、想看、在看、看过、搁置和抛弃分组
 * 并显示各组计数；每条以统一作品卡片展示，想看 / 在看 提供「加入待补池」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MyLibraryScreen(
    state: UiState<MyLibraryData>,
    selectedTab: LibraryStatusTab,
    onSelectTab: (LibraryStatusTab) -> Unit,
    onAddToBacklog: (String) -> Unit,
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AcgScreenScaffold(
        title = "我的库",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
        },
    ) { innerPadding ->
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) { data ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = LIBRARY_TOP_GAP),
            ) {
                StatusTabs(
                    selectedTab = selectedTab,
                    counts = data.counts,
                    onSelectTab = onSelectTab,
                )
                val visible = data.itemsFor(selectedTab)
                if (data.items.isEmpty()) {
                    LibraryEmptyState()
                } else if (visible.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "「${selectedTab.label}」分组下暂无作品",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 与上方分组药丸拉开一点距离，首张卡片不贴着顶部。
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = ScreenContentPadding.Horizontal,
                            end = ScreenContentPadding.Horizontal,
                            top = LIBRARY_LIST_GAP,
                            bottom = ScreenContentPadding.Bottom,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.workId }) { item ->
                            LibraryListItem(
                                item = item,
                                onOpenWork = onOpenWork,
                                onAddToBacklog = onAddToBacklog,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 状态分组 Tab（带计数）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StatusTabs(
    selectedTab: LibraryStatusTab,
    counts: Map<LibraryStatusTab, Int>,
    onSelectTab: (LibraryStatusTab) -> Unit,
) {
    val tabs = LibraryStatusTab.entries
    PillTabs(
        options = tabs,
        selected = selectedTab,
        onSelect = onSelectTab,
        label = { tab -> "${tab.label} ${counts[tab] ?: 0}" },
        modifier = Modifier.padding(horizontal = ScreenContentPadding.Horizontal, vertical = 8.dp),
        scrollable = true,
    )
}

/** 单条目：作品卡片 + 详情 / 加入待补池操作。 */
@Composable
private fun LibraryListItem(item: LibraryItem, onOpenWork: (String) -> Unit, onAddToBacklog: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkCard(
            model = item.toWorkCardUiModel(),
            onClick = { onOpenWork(item.workId) },
        )
        if (item.canAddToBacklog) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = { onAddToBacklog(item.workId) }) {
                    Text("加入待补池", maxLines = 1)
                }
            }
        }
    }
}

/** 无收藏时说明如何同步入库。 */
@Composable
private fun LibraryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PrismCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "我的库还是空的",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "「收藏入库」= 把 Bangumi 的收藏 / 评分 / 进度 / 短评同步到本地。" +
                        "前往「我的 → 个人数据同步」配置 Bangumi 账号并点击同步，同步后这里就能浏览到具体作品。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MyLibraryScreenPreview() {
    AcgCompassTheme {
        MyLibraryScreen(
            state = UiState.Success(
                MyLibraryData(
                    items = listOf(
                        LibraryItem(
                            workId = "1",
                            title = "葬送的芙莉莲",
                            coverUrl = null,
                            typeText = "动画",
                            yearText = "2023",
                            status = "在看",
                            ratingText = "9 / 10",
                            progressText = "已看 12 集",
                            sourceText = "Bangumi",
                            inBacklog = false,
                            canAddToBacklog = true,
                        ),
                        LibraryItem(
                            workId = "2",
                            title = "某看过的作品",
                            coverUrl = null,
                            typeText = "游戏",
                            yearText = NO_DATA,
                            status = "看过",
                            ratingText = "8 / 10",
                            progressText = null,
                            sourceText = "Bangumi",
                            inBacklog = false,
                            canAddToBacklog = false,
                        ),
                    ),
                    counts = mapOf(
                        LibraryStatusTab.ALL to 2,
                        LibraryStatusTab.WATCHING to 1,
                        LibraryStatusTab.WATCHED to 1,
                    ),
                ),
            ),
            selectedTab = LibraryStatusTab.ALL,
            onSelectTab = {},
            onAddToBacklog = {},
            onOpenWork = {},
            onBack = {},
        )
    }
}

/** 我的库自己的间距：顶栏到分组药丸、药丸到第一张卡片。 */
private val LIBRARY_TOP_GAP = 28.dp
private val LIBRARY_LIST_GAP = 20.dp

package com.acgcompass.feature.tasteranking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgPullRefreshBox
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.TonalFilterChip
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.LocalAppMessenger
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.taste.PoolExpansion
import com.acgcompass.domain.taste.WorkFormat

/** 口味排行的独立页面（从口味画像页进入）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TasteRankingRoute(onBack: () -> Unit, onOpenWork: (String) -> Unit) {
    AcgScreenScaffold(
        title = "口味排行",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    ) { innerPadding ->
        TasteRankingBoard(
            onOpenWork = onOpenWork,
            contentPadding = ScreenContentPadding.lazyList(innerPadding),
            indicatorTopPadding = innerPadding.calculateTopPadding(),
        )
    }
}

/** 口味排行列表（发现页「为你排行」分区和独立页面共用）。下拉重新计算。 */
@Composable
fun TasteRankingBoard(
    onOpenWork: (String) -> Unit,
    contentPadding: PaddingValues,
    indicatorTopPadding: Dp,
    viewModel: TasteRankingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showMessage = LocalAppMessenger.current
    LaunchedEffect(state.message) {
        state.message?.let {
            showMessage(it)
            viewModel.onMessageShown()
        }
    }
    TasteRankingContent(
        state = state,
        actions = TasteRankingActions(
            onOpenWork = onOpenWork,
            onRefresh = viewModel::onRefresh,
            onToggleFormat = viewModel::onToggleFormat,
            onEraChange = viewModel::onEraChange,
            onToggleFinishedOnly = viewModel::onToggleFinishedOnly,
            onToggleHideOwned = viewModel::onToggleHideOwned,
            onExpandPool = viewModel::onExpandPool,
            onToggleFilters = viewModel::onToggleFilters,
            onDismissHint = viewModel::onDismissHint,
        ),
        contentPadding = contentPadding,
        indicatorTopPadding = indicatorTopPadding,
    )
}

/** 口味排行页的操作。 */
data class TasteRankingActions(
    val onOpenWork: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onToggleFormat: (WorkFormat) -> Unit = {},
    val onEraChange: (EraFilter) -> Unit = {},
    val onToggleFinishedOnly: () -> Unit = {},
    val onToggleHideOwned: () -> Unit = {},
    val onExpandPool: () -> Unit = {},
    val onToggleFilters: () -> Unit = {},
    val onDismissHint: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasteRankingContent(
    state: TasteRankingUiState,
    actions: TasteRankingActions,
    contentPadding: PaddingValues,
    indicatorTopPadding: Dp,
) {
    AcgPullRefreshBox(
        refreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        indicatorTopPadding = indicatorTopPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.loading -> item(key = "loading") { Loading() }
                state.unavailable -> item(key = "unavailable") {
                    Hint("还没有口味画像", "先同步 Bangumi 收藏并给看过的作品打分，算出口味画像后就能排出为你推荐的作品。")
                }
                else -> {
                    item(key = "header") { Header(state, actions) }
                    if (state.visible.isEmpty()) {
                        item(key = "empty") { Hint("没有符合条件的作品", "放宽一下筛选条件试试。") }
                    }
                    itemsIndexed(state.visible, key = { _, item -> item.subjectId }) { index, item ->
                        RankRow(index + 1, item) { actions.onOpenWork(item.subjectId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            "正在给本地作品库算匹配度…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Hint(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 头部：一行规模说明 +「筛选」开关；补充进度条；作品不多时的说明卡；展开后的筛选面板。 */
@Composable
private fun Header(state: TasteRankingUiState, actions: TasteRankingActions) {
    val ranking = state.ranking
    val unrated = ranking?.unratedCount ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ranking?.poolSize ?: 0} 部参与排序 · 没评过分 $unrated 部" + if (state.updating) " · 正在更新" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val active = state.filter.activeCount
            TextButton(onClick = actions.onToggleFilters) {
                Text(if (active > 0) "筛选 · $active" else "筛选")
                Icon(
                    if (state.filtersExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        state.expansion?.takeIf { it.running }?.let { ExpansionProgress(it) }
        state.pool?.missingFeatures?.takeIf { it > 0 }?.let { missing ->
            Text(
                "作品库里还有 $missing 部在后台补标签，补完会自动加入排行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (unrated < SMALL_POOL && !state.hintDismissed && state.expansion?.running != true) {
            PoolHintCard(onExpand = actions.onExpandPool, onDismiss = actions.onDismissHint)
        }
        AnimatedVisibility(visible = state.filtersExpanded) {
            FilterPanel(state, actions)
        }
    }
}

@Composable
private fun ExpansionProgress(expansion: PoolExpansion) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { expansion.done.toFloat() / expansion.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "正在补充作品库 ${expansion.done} / ${expansion.total} 部（排行第 ${expansion.startRank} 名起，离开页面也会继续）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 没评过分的作品不多时的说明，可以关掉（之后在筛选面板里也能补充）。 */
@Composable
private fun PoolHintCard(onExpand: () -> Unit, onDismiss: () -> Unit) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("想看更多候选？", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭说明") }
            }
            Text(
                "浏览过的榜单和搜索结果会自动存进作品库。也可以从 Bangumi 排行一次补充 1000 部，大约一分钟。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 10.dp),
            )
            FilledTonalButton(onClick = onExpand, modifier = Modifier.padding(top = 8.dp)) { Text("补充作品库") }
        }
    }
}

/** 筛选面板：形式、年代、完结、隐藏已评分和已收藏；最下面是补充作品库。 */
@Composable
private fun FilterPanel(state: TasteRankingUiState, actions: TasteRankingActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow {
            WorkFormat.entries.filter { it != WorkFormat.OTHER }.forEach { format ->
                TonalFilterChip(format.label, format in state.filter.formats, { actions.onToggleFormat(format) })
            }
        }
        ChipRow {
            EraFilter.entries.forEach { era ->
                TonalFilterChip(era.label, state.filter.era == era, { actions.onEraChange(era) })
            }
        }
        ChipRow {
            TonalFilterChip("只看已完结", state.filter.finishedOnly, actions.onToggleFinishedOnly)
            TonalFilterChip("隐藏已评分和已收藏", state.filter.hideOwned, actions.onToggleHideOwned)
        }
        TextButton(onClick = actions.onExpandPool, enabled = state.expansion?.running != true) {
            Text("从 Bangumi 排行补充 1000 部作品")
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private const val SMALL_POOL = 300

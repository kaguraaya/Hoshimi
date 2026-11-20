package com.acgcompass.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AppSearchField
import com.acgcompass.core.designsystem.AppSearchFieldState
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.PrismMetrics
import com.acgcompass.core.designsystem.SectionHeader
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/**
 * 搜索分区：搜索框是列表的第一项，下面依次是历史搜索、最近浏览（查询为空时）或搜索结果——
 * 它们在同一个列表里一起滚动，往上滑时搜索框跟着滚走，可见区域更大。
 */
@Suppress("LongParameterList") // 搜索分区的全部状态与回调
@Composable
internal fun SearchSection(
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
internal fun LazyListScope.searchResultItems(
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

/** 搜索框：填充的药丸，单行输入、可清除；ViewModel 防抖触发搜索，按键盘的搜索键时记录历史。 */
@Composable
internal fun SearchBar(query: String, onQueryChange: (String) -> Unit, onSearchSubmit: () -> Unit) {
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
internal fun SearchHistorySection(
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

internal const val HISTORY_COLLAPSED = 6

/**
 * 单条搜索结果：统一作品卡片 + （低置信时）手动纠正横幅与入口。
 */
@Composable
internal fun DiscoverResultCard(
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
internal fun LowConfidenceBanner(onPick: () -> Unit) {
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
internal fun MatchPickerDialog(
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

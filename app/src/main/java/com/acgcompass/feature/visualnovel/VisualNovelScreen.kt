package com.acgcompass.feature.visualnovel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.AppSearchField
import com.acgcompass.core.designsystem.AppSearchFieldState
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import com.acgcompass.domain.visualnovel.VisualNovelSort
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualNovelRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: VisualNovelViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("视觉小说 · Galgame") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新榜单")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            VisualNovelList(state, viewModel, onOpenWork)
            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun VisualNovelList(state: VisualNovelUiState, viewModel: VisualNovelViewModel, onOpenWork: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size, state.hasMore, state.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val triggerIndex = (listState.layoutInfo.totalItemsCount - AUTO_LOAD_DISTANCE).coerceAtLeast(0)
                if (state.hasMore && !state.loadingMore && lastVisible >= triggerIndex) viewModel.loadMore()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { VisualNovelControls(state, viewModel) }
        if (state.refreshing || state.stale || state.refreshFailed) item { RankingRefreshStatus(state) }
        state.error?.let { error ->
            item { VisualNovelError(error, viewModel::retry) }
        }
        items(state.items, key = { it.work.id }) { item ->
            VisualNovelCard(item, onClick = { onOpenWork(item.work.id) })
        }
        item { VisualNovelListFooter(state, viewModel::retryLoadMore) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun VisualNovelControls(state: VisualNovelUiState, viewModel: VisualNovelViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSearchField(
            state = AppSearchFieldState(
                value = state.query,
                placeholder = "搜索视觉小说（至少 2 个字符）",
            ),
            onValueChange = viewModel::setQuery,
            onSearch = viewModel::submitSearch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(VisualNovelSort.entries.size) { index ->
                val sort = VisualNovelSort.entries[index]
                FlatFilterChip(
                    selected = state.sort == sort && state.query.isBlank(),
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(sort.label()) },
                )
            }
        }
    }
}

@Composable
private fun VisualNovelError(message: String, onRetry: () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun VisualNovelListFooter(state: VisualNovelUiState, onRetry: () -> Unit) {
    when {
        state.loadingMore -> Box(
            Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        state.loadMoreError != null -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.loadMoreError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun RankingRefreshStatus(state: VisualNovelUiState) {
    Text(
        text = when {
            state.refreshing -> "正在后台更新，当前先显示本地内容"
            state.refreshFailed -> "更新失败，已保留上次可用内容"
            else -> "当前内容已过期，将在后台更新"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun VisualNovelCard(item: VisualNovelListItem, onClick: () -> Unit) {
    PrismCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp)) {
            AsyncImage(
                model = item.previewUrl?.let { url ->
                    managedImageRequest(
                        url = url,
                        workId = item.work.id,
                        kind = CacheAssetKind.COVER,
                        adult = item.adult,
                    )
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Surfaces.raisedColor),
                error = ColorPainter(Surfaces.raisedColor),
                modifier = Modifier.size(width = 84.dp, height = 116.dp).clip(PrismCorner.Cover),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.work.titles.canonical, style = MaterialTheme.typography.titleMedium)
                item.work.titles.ja?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(item.work.year?.toString() ?: "发行日期暂无数据")
                Text(
                    item.rating?.let { "VNDB %.1f/100 · %,d 票".format(it, item.voteCount) }
                        ?: "VNDB 评分暂无数据",
                )
            }
        }
    }
}

private fun VisualNovelSort.label(): String = when (this) {
    VisualNovelSort.RATING -> "评分"
    VisualNovelSort.POPULARITY -> "热度"
    VisualNovelSort.RELEASE_DATE -> "发行时间"
    VisualNovelSort.RANDOM -> "随机"
}

private const val AUTO_LOAD_DISTANCE = 4

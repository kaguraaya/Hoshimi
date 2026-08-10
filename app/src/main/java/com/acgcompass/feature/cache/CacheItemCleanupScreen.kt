package com.acgcompass.feature.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.model.SourceId

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CacheItemCleanupScreen(onBack: () -> Unit, viewModel: CacheManagementViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf<SourceId?>(null) }
    var kind by remember { mutableStateOf<CacheAssetKind?>(null) }
    val filters = CacheItemFilters(source = source, kind = kind)
    val visible = state.filteredItems.filter { item ->
        (source == null || item.source == source) && (kind == null || kind in item.assetKinds)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择作品缓存") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CacheItemFilterControls(
                    query = state.query,
                    filters = filters,
                    onQueryChange = viewModel::setQuery,
                    onFiltersChange = { updated ->
                        source = updated.source
                        kind = updated.kind
                    },
                )
            }
            items(visible, key = { it.workId }) { item ->
                CacheItemRow(item = item, state = state, kind = kind, viewModel = viewModel)
            }
            if (state.selectedWorkIds.isNotEmpty()) {
                item {
                    Button(
                        onClick = { viewModel.clearSelected(kind) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("清理已选 ${state.selectedWorkIds.size} 项") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CacheItemFilterControls(
    query: String,
    filters: CacheItemFilters,
    onQueryChange: (String) -> Unit,
    onFiltersChange: (CacheItemFilters) -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("标题或作品 ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = filters.source == null,
                onClick = { onFiltersChange(filters.copy(source = null)) },
                label = { Text("全部来源") },
            )
            listOf(SourceId.BANGUMI, SourceId.TENRAI, SourceId.ANILIST, SourceId.VNDB).forEach { source ->
                FilterChip(
                    selected = filters.source == source,
                    onClick = { onFiltersChange(filters.copy(source = source)) },
                    label = { Text(source.name) },
                )
            }
        }
        CacheKindFilters(filters, onFiltersChange)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CacheKindFilters(filters: CacheItemFilters, onFiltersChange: (CacheItemFilters) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = filters.kind == null,
            onClick = { onFiltersChange(filters.copy(kind = null)) },
            label = { Text("整项缓存") },
        )
        ClearableAssetKinds.forEach { kind ->
            FilterChip(
                selected = filters.kind == kind,
                onClick = { onFiltersChange(filters.copy(kind = kind)) },
                label = { Text(kind.label()) },
            )
        }
    }
}

@Composable
private fun CacheItemRow(
    item: com.acgcompass.domain.cache.CacheItem,
    state: CacheManagementUiState,
    kind: CacheAssetKind?,
    viewModel: CacheManagementViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.workId in state.selectedWorkIds,
                onCheckedChange = { viewModel.toggleSelection(item.workId) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title)
                Text(
                    "${item.source.name}" + if (item.protected) " · 个人记录受保护" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { viewModel.clearOne(item.workId, kind) }, enabled = !state.busy) {
                Text("清理")
            }
        }
    }
}

private data class CacheItemFilters(
    val source: SourceId?,
    val kind: CacheAssetKind?,
)

private val ClearableAssetKinds = listOf(
    CacheAssetKind.DETAIL,
    CacheAssetKind.COVER,
    CacheAssetKind.SCREENSHOT_THUMBNAIL,
    CacheAssetKind.SCREENSHOT_ORIGINAL,
)

private fun CacheAssetKind.label(): String = when (this) {
    CacheAssetKind.DETAIL -> "详情"
    CacheAssetKind.COVER -> "封面"
    CacheAssetKind.SCREENSHOT_THUMBNAIL -> "截图缩略图"
    CacheAssetKind.SCREENSHOT_ORIGINAL -> "截图原图"
    CacheAssetKind.WORK -> "整项缓存"
}

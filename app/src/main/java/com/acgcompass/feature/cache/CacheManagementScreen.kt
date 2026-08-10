package com.acgcompass.feature.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.model.SourceId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit,
    onOpenItemCleanup: () -> Unit,
    onResetComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CacheManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PolicyCard(
                    state = state,
                    onEvent = viewModel::onPolicyEvent,
                )
            }
            item { UsageCard(state) }
            item { SourceCard(state, viewModel) }
            item { CleanupCard(state, viewModel, onOpenItemCleanup) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "危险操作",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "清除本地数据会删除数据库、设置、登录状态、媒体和后台任务，应用将回到首次启动状态。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("清除本地数据")
                        }
                    }
                }
            }
        }
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("确认清除本地数据") },
            text = { Text("此操作不可撤销，所有本地记录和登录状态都会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        scope.launch {
                            if (viewModel.resetLocalData()) onResetComplete()
                        }
                    },
                ) { Text("确认清除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UsageCard(state: CacheManagementUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前占用", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { state.utilization }, modifier = Modifier.fillMaxWidth())
            Text(
                "${state.usage.publicWorkCount} 部公共作品 · 元数据约 ${formatBytes(state.usage.metadataBytes)} · " +
                    "图片 ${formatBytes(state.usage.mediaBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "可管理缓存合计约 ${formatBytes(state.usage.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "应用可见文件约 ${formatBytes(state.usage.appStorageBytes)}（含数据库、图片目录、日志和运行时文件）。" +
                    " Android 系统统计还可能包含 APK、运行时缓存及其他不可按作品删除的内容。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.usage.protectedWorkCount > 0) {
                Text("${state.usage.protectedWorkCount} 部作品受个人记录保护，不占自动缓存名额")
            }
        }
    }
}

@Composable
private fun SourceCard(state: CacheManagementUiState, viewModel: CacheManagementViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按来源", style = MaterialTheme.typography.titleMedium)
            Text(
                "来源覆盖数按唯一作品统计；同一作品可覆盖多个来源，各行不应相加。",
                style = MaterialTheme.typography.bodySmall,
            )
            listOf(SourceId.BANGUMI, SourceId.TENRAI, SourceId.ANILIST, SourceId.VNDB).forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${source.name} · ${state.sourceCoverage[source] ?: 0}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.clearSource(source) }, enabled = !state.busy) {
                        Text("清理")
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupCard(
    state: CacheManagementUiState,
    viewModel: CacheManagementViewModel,
    onOpenItemCleanup: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("批量清理", style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::trimNow, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("按当前上限整理")
            }
            OutlinedButton(onClick = onOpenItemCleanup, modifier = Modifier.fillMaxWidth()) {
                Text("搜索并选择作品…")
            }
            OutlinedButton(
                onClick = viewModel::clearAllCache,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清除可重新下载的缓存")
            }
            HorizontalDivider()
            listOf(
                "截图原图" to CacheAssetKind.SCREENSHOT_ORIGINAL,
                "截图缩略图" to CacheAssetKind.SCREENSHOT_THUMBNAIL,
                "封面" to CacheAssetKind.COVER,
                "详情" to CacheAssetKind.DETAIL,
            ).forEach { (label, kind) ->
                OutlinedButton(
                    onClick = { viewModel.clearAsset(kind) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清理$label") }
            }
            OutlinedButton(
                onClick = viewModel::clearExpiredDetails,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清理过期详情") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f KB".format(bytes / 1024.0)
}

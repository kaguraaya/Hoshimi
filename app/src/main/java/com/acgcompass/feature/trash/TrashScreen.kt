package com.acgcompass.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.model.TrashEntry
import com.acgcompass.domain.model.TrashKind
import com.acgcompass.domain.model.TrashReason
import com.acgcompass.domain.repository.TrashRepository
import java.time.Instant
import java.time.ZoneId

@Composable
fun TrashRoute(onBack: () -> Unit, onOpenWork: (String) -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    TrashScreen(
        entries = entries,
        snackbarHostState = snackbar,
        onBack = onBack,
        onOpenWork = onOpenWork,
        onRestore = viewModel::onRestore,
        onDeleteForever = viewModel::onDeleteForever,
        onEmptyTrash = viewModel::onEmptyTrash,
    )
}

/**
 * 回收站：移出待补池、清空的记录，以及 Bangumi 上已删除的收藏，保留 [TrashRepository.RETENTION_DAYS] 天。
 * 恢复后加入时间、状态和评分保持原样；回收站只在本地，不同步到 Bangumi。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun TrashScreen(
    entries: List<TrashEntry>?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onDeleteForever: (TrashEntry) -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    AcgScreenScaffold(
        title = "回收站",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            if (!entries.isNullOrEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
            when {
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                entries.isEmpty() -> EmptyTrash()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = ScreenContentPadding.Horizontal,
                        end = ScreenContentPadding.Horizontal,
                        top = 12.dp,
                        bottom = ScreenContentPadding.Bottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "hint") { RetentionHint() }
                    items(entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            onOpenWork = onOpenWork,
                            onRestore = onRestore,
                            onDeleteForever = onDeleteForever,
                        )
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("回收站里的记录会被彻底删除，不能再恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmEmpty = false
                    onEmptyTrash()
                }) { Text("清空") }
            },
            dismissButton = { FilledTonalButton(onClick = { confirmEmpty = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RetentionHint() {
    Text(
        "移出待补池或清空的记录、Bangumi 上已删除的收藏会在这里保留 ${TrashRepository.RETENTION_DAYS} 天。" +
            "恢复后加入时间、状态和评分都和原来一样。回收站只在本机，不会同步到 Bangumi。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    onOpenWork: (String) -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onDeleteForever: (TrashEntry) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    PrismCard(onClick = { onOpenWork(entry.workId) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(
                        width = 44.dp,
                        height = 58.dp,
                    ).clip(PrismCorner.Thumb).background(Surfaces.raisedColor),
                ) {
                    if (entry.coverUrl != null) {
                        AsyncImage(
                            model = entry.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${formatDate(entry.removedAt)} ${entry.reasonLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.stillOnBangumi()) {
                Text(
                    "Bangumi 网页上仍有这条收藏（公开接口不能删除收藏），如需移除请到网页操作。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (entry.stillOnBangumi()) {
                    TextButton(onClick = { uriHandler.openUri("https://bgm.tv/subject/${entry.workId}") }) {
                        Text("去网页移除")
                    }
                }
                TextButton(onClick = { onDeleteForever(entry) }) { Text("彻底删除") }
                FilledTonalButton(onClick = { onRestore(entry) }) { Text("恢复") }
            }
        }
    }
}

@Composable
private fun EmptyTrash() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("回收站是空的", style = MaterialTheme.typography.titleMedium)
        Text(
            "移出待补池或清空记录时，原记录会先放在这里，${TrashRepository.RETENTION_DAYS} 天内可以恢复。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 记录内容：待补池条目显示原来的加入日期；收藏显示状态、评分和原来的更新日期。 */
internal fun TrashEntry.describe(): String = when (kind) {
    TrashKind.BACKLOG -> "待补池 · 加入于 ${addedAt?.let(::formatDate) ?: "未知"}"
    TrashKind.COLLECTION -> buildString {
        append(status ?: "收藏")
        rating?.let { append(" · 评分 $it") }
        addedAt?.let { append(" · 记录于 ${formatDate(it)}") }
    }
}

internal fun TrashEntry.reasonLabel(): String = when (reason) {
    TrashReason.LOCAL -> if (kind == TrashKind.BACKLOG) "移出待补池" else "在应用里清空"
    TrashReason.REMOTE_REMOVED -> "Bangumi 上已删除"
    TrashReason.ACCOUNT_SWITCH -> "切换账号时移入"
}

/** 在应用里移除的 Bangumi 收藏：网页上那条还在（Bangumi 公开接口不能删除收藏）。 */
internal fun TrashEntry.stillOnBangumi(): Boolean =
    kind == TrashKind.COLLECTION && reason == TrashReason.LOCAL && workId.toIntOrNull() != null

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

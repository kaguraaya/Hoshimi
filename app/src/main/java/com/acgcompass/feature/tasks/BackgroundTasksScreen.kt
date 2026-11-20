package com.acgcompass.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgSnackbarHost
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import com.acgcompass.feature.cache.toCacheSourceLabel
import com.acgcompass.feature.cache.toSourceStatusText

@Composable
fun BackgroundTasksRoute(onBack: () -> Unit, viewModel: BackgroundTasksViewModel = hiltViewModel()) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    BackgroundTasksScreen(
        statuses = statuses,
        now = System.currentTimeMillis(),
        snackbarHostState = snackbar,
        onBack = onBack,
        onRetry = viewModel::onRetry,
    )
}

/**
 * 设置 → 后台任务：同步、推送、补资料、补评分、公共缓存、扩充作品库、自动备份和详情页扩展资料，
 * 每类一张卡片，写着是否在运行、上次成功 / 失败（含原因）、下次计划时间，能手动重试的有「立即重试」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // 页面骨架：状态与回调逐个传入，便于截图测试。
@Composable
fun BackgroundTasksScreen(
    statuses: List<BackgroundTaskStatus>?,
    now: Long,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRetry: (BackgroundTaskKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    AcgScreenScaffold(
        title = "后台任务",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        snackbarHost = { AcgSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenContentPadding.lazyList(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    "这些任务在后台自己跑，失败了大多会自动再试。这里能看到每类最近的情况；" +
                        "觉得哪个卡住了，可以点「立即重试」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statuses == null) {
                item(key = "loading") {
                    Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(statuses, key = { it.kind.key }) { status ->
                    TaskCard(status = status, now = now, onRetry = { onRetry(status.kind) })
                }
            }
        }
    }
}

@Composable
private fun TaskCard(status: BackgroundTaskStatus, now: Long, onRetry: () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.kind.label(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Badge(status.badge())
            }
            Text(
                status.kind.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 补充公共缓存：逐个来源照「缓存管理」页的格式列出。
            status.cacheRuns.forEach { run ->
                val time = run.lastRunAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }
                    ?: "尚未运行"
                Text(
                    "${run.source.toCacheSourceLabel()}：${run.result.toSourceStatusText()} · $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status.detailLines(now).forEach { line ->
                val isError = line.startsWith("上次失败")
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (status.retryable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onRetry, enabled = !status.running) {
                        Text(if (status.running) "运行中…" else "立即重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(badge: TaskBadge) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (badge) {
        TaskBadge.RUNNING -> scheme.primaryContainer to scheme.onPrimaryContainer
        TaskBadge.OK -> scheme.secondaryContainer to scheme.onSecondaryContainer
        TaskBadge.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        TaskBadge.DISABLED, TaskBadge.IDLE -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    BadgeSurface(badge.text(), container, content)
}

@Composable
private fun BadgeSurface(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

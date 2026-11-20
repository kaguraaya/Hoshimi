package com.acgcompass.feature.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.domain.cache.CacheLimitMode
import java.text.DateFormat
import java.util.Date

@Composable
@Suppress("MagicNumber") // Product-defined preset values; custom input remains available separately.
internal fun PolicyCard(
    state: CacheManagementUiState,
    onEvent: (CachePolicyEvent) -> Unit,
) {
    val countPresets = listOf(500, 1_000, 2_000, 3_000)
    val spacePresets = listOf(100, 250, 500, 1_000)
    FlatCard(
        title = "缓存上限",
        icon = Icons.Outlined.Storage,
        supporting = "超出上限时先清最久没打开的作品；最近 3 天看过的、待补池和收藏里的不清。" +
            "AI 生成的评论摘要、翻译和匹配分析不会被清理，避免重复消耗额度。",
    ) {
        CacheModeControls(state, onEvent)
        val presets = if (state.mode == CacheLimitMode.COUNT) countPresets else spacePresets
        CacheLimitControls(state, presets, onEvent)
        BackgroundFillControl(state, onEvent)
        FillAction(state, onEvent)
        FillRunStatus(state)
    }
}

@Composable
internal fun CacheModeControls(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    PillTabs(
        options = listOf(CacheLimitMode.COUNT, CacheLimitMode.SPACE),
        selected = state.mode,
        onSelect = { onEvent(CachePolicyEvent.ModeChanged(it)) },
        label = { if (it == CacheLimitMode.COUNT) "按数量" else "按空间" },
    )
}

@Composable
internal fun CacheLimitControls(
    state: CacheManagementUiState,
    presets: List<Int>,
    onEvent: (CachePolicyEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillTabs(
            options = presets,
            selected = presets.firstOrNull { state.isLimitSelected(it) },
            onSelect = { state.updateLimit(it, onEvent) },
            label = { if (state.mode == CacheLimitMode.COUNT) "$it 部" else "$it MB" },
            scrollable = true,
        )
        FlatTextField(
            value = state.limitText(),
            onValueChange = { raw ->
                raw.toIntOrNull()?.let { state.updateLimit(it, onEvent) }
            },
            label = { Text(if (state.mode == CacheLimitMode.COUNT) "自定义数量" else "自定义 MB") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
internal fun BackgroundFillControl(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("后台补充")
            Text("应用不在前台时也定期补充：仅在非计费网络且充电时运行，约每 12 小时一次", style = MaterialTheme.typography.bodySmall)
        }
        FlatSwitch(
            checked = state.policy.backgroundFillEnabled,
            onCheckedChange = { onEvent(CachePolicyEvent.BackgroundFillChanged(it)) },
        )
    }
}

@Composable
internal fun FillAction(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    // 公共缓存受各数据源限流约束，不提供「立即填充」：只说明实际行为，填充进行中时给出停止入口。
    Text(
        "公共缓存会在你使用应用时自动补充（打开首页、浏览榜单时按各数据源的限速慢慢补），不需要手动触发。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.fillInProgress) {
        FilledTonalButton(onClick = { onEvent(CachePolicyEvent.StopFill) }, modifier = Modifier.fillMaxWidth()) {
            Text("停止本次补充")
        }
    }
}

@Composable
internal fun FillRunStatus(state: CacheManagementUiState) {
    val target = when (state.mode) {
        CacheLimitMode.COUNT -> state.policy.normalizedCountLimit
        CacheLimitMode.SPACE -> null
    }
    Text(
        text = target?.let { "公共缓存 ${state.usage.publicWorkCount}/$it 部" }
            ?: "公共缓存 ${state.usage.publicWorkCount} 部，按空间上限管理",
        style = MaterialTheme.typography.bodySmall,
    )
    state.fillRuns.forEach { run ->
        FlatDivider()
        val time = run.lastRunAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "尚未运行"
        Text(
            "${run.source.toCacheSourceLabel()}：${run.result.toSourceStatusText()} · $time",
            style = MaterialTheme.typography.bodySmall,
        )
        run.failureReason?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun CacheManagementUiState.isLimitSelected(value: Int): Boolean = when (mode) {
    CacheLimitMode.COUNT -> policy.countLimit == value
    CacheLimitMode.SPACE -> policy.spaceLimitBytes / BYTES_PER_MEGABYTE == value.toLong()
}

internal fun CacheManagementUiState.limitText(): String = when (mode) {
    CacheLimitMode.COUNT -> policy.countLimit.toString()
    CacheLimitMode.SPACE -> (policy.spaceLimitBytes / BYTES_PER_MEGABYTE).toString()
}

internal fun CacheManagementUiState.updateLimit(value: Int, onEvent: (CachePolicyEvent) -> Unit) {
    when (mode) {
        CacheLimitMode.COUNT -> onEvent(CachePolicyEvent.CountLimitChanged(value))
        CacheLimitMode.SPACE -> onEvent(CachePolicyEvent.SpaceLimitChanged(value))
    }
}

internal const val BYTES_PER_MEGABYTE = 1024L * 1024L

package com.acgcompass.feature.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("缓存上限", style = MaterialTheme.typography.titleMedium)
            CacheModeControls(state, onEvent)
            val presets = if (state.mode == CacheLimitMode.COUNT) countPresets else spacePresets
            CacheLimitControls(state, presets, onEvent)
            BackgroundFillControl(state, onEvent)
            FillAction(state, onEvent)
            FillRunStatus(state)
        }
    }
}

@Composable
private fun CacheModeControls(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.mode == CacheLimitMode.COUNT,
            onClick = { onEvent(CachePolicyEvent.ModeChanged(CacheLimitMode.COUNT)) },
            label = { Text("按数量") },
        )
        FilterChip(
            selected = state.mode == CacheLimitMode.SPACE,
            onClick = { onEvent(CachePolicyEvent.ModeChanged(CacheLimitMode.SPACE)) },
            label = { Text("按空间") },
        )
    }
}

@Composable
private fun CacheLimitControls(
    state: CacheManagementUiState,
    presets: List<Int>,
    onEvent: (CachePolicyEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(presets.size) { index ->
                val value = presets[index]
                FilterChip(
                    selected = state.isLimitSelected(value),
                    onClick = { state.updateLimit(value, onEvent) },
                    label = { Text(if (state.mode == CacheLimitMode.COUNT) "$value" else "$value MB") },
                    modifier = Modifier.width(PRESET_WIDTH),
                )
            }
        }
        OutlinedTextField(
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
private fun BackgroundFillControl(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("后台补充")
            Text("仅在非计费网络且充电时运行", style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = state.policy.backgroundFillEnabled,
            onCheckedChange = { onEvent(CachePolicyEvent.BackgroundFillChanged(it)) },
        )
    }
}

@Composable
private fun FillAction(state: CacheManagementUiState, onEvent: (CachePolicyEvent) -> Unit) {
    if (state.fillInProgress) {
        OutlinedButton(onClick = { onEvent(CachePolicyEvent.StopFill) }, modifier = Modifier.fillMaxWidth()) {
            Text("停止填充")
        }
    } else {
        Button(
            onClick = { onEvent(CachePolicyEvent.StartFill) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("继续填充到当前上限")
        }
    }
}

@Composable
private fun FillRunStatus(state: CacheManagementUiState) {
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
        HorizontalDivider()
        val time = run.lastRunAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "尚未运行"
        Text(
            "${run.source}：${run.result.toSourceStatusText()} · $time",
            style = MaterialTheme.typography.bodySmall,
        )
        run.failureReason?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun String?.toSourceStatusText(): String = when (this) {
    "TARGET_REACHED" -> "此源本轮配额已完成"
    "EXHAUSTED" -> "此源候选已耗尽"
    "RUNNING" -> "正在补充"
    "FAILED" -> "本轮失败，可稍后继续"
    null -> "暂无"
    else -> this
}

private fun CacheManagementUiState.isLimitSelected(value: Int): Boolean = when (mode) {
    CacheLimitMode.COUNT -> policy.countLimit == value
    CacheLimitMode.SPACE -> policy.spaceLimitBytes / BYTES_PER_MEGABYTE == value.toLong()
}

private fun CacheManagementUiState.limitText(): String = when (mode) {
    CacheLimitMode.COUNT -> policy.countLimit.toString()
    CacheLimitMode.SPACE -> (policy.spaceLimitBytes / BYTES_PER_MEGABYTE).toString()
}

private fun CacheManagementUiState.updateLimit(value: Int, onEvent: (CachePolicyEvent) -> Unit) {
    when (mode) {
        CacheLimitMode.COUNT -> onEvent(CachePolicyEvent.CountLimitChanged(value))
        CacheLimitMode.SPACE -> onEvent(CachePolicyEvent.SpaceLimitChanged(value))
    }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
private val PRESET_WIDTH = 96.dp

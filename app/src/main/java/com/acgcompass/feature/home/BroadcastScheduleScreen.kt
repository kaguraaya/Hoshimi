@file:Suppress("MatchingDeclarationName")

package com.acgcompass.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import com.acgcompass.domain.schedule.WeeklyBroadcastSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BroadcastScheduleViewModel @Inject constructor(
    private val repository: BroadcastScheduleRepository,
) : ViewModel() {
    private val refreshState = MutableStateFlow(ScheduleRefreshState())
    val schedule: StateFlow<WeeklyBroadcastSchedule> =
        combine(repository.observeWeek(), refreshState) { cached, state ->
            cached.copy(
                refreshing = state.refreshing,
                stale = state.errorMessage != null && cached.entriesByDay.isNotEmpty(),
                errorMessage = state.errorMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SCHEDULE_STOP_TIMEOUT_MILLIS),
            initialValue = WeeklyBroadcastSchedule(emptyMap(), refreshing = true),
        )

    init {
        refresh(force = false)
    }

    fun retry() {
        refresh(force = true)
    }

    private fun refresh(force: Boolean) {
        if (refreshState.value.refreshing && force) return
        refreshState.value = ScheduleRefreshState(refreshing = true)
        viewModelScope.launch {
            val result = repository.refreshWeek(force)
            refreshState.value = ScheduleRefreshState(
                refreshing = false,
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }
}

private const val SCHEDULE_STOP_TIMEOUT_MILLIS = 5_000L
private data class ScheduleRefreshState(val refreshing: Boolean = true, val errorMessage: String? = null)

@Composable
fun BroadcastScheduleRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: BroadcastScheduleViewModel = hiltViewModel(),
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    BroadcastScheduleScreen(schedule, onBack, onOpenWork, viewModel::retry)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BroadcastScheduleScreen(
    schedule: WeeklyBroadcastSchedule,
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var showOtherSources by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("放送时间表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            DayOfWeek.entries.forEach { day ->
                item(key = "day_${day.value}") {
                    Text(
                        text = dayLabel(day),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(schedule.entries(day), key = { it.scheduleId }) { entry ->
                    ScheduleEntryRow(entry, onClick = { onOpenWork(entry.workId) })
                }
            }
            otherSourceItems(
                entries = schedule.otherSourceEntries,
                expanded = showOtherSources,
                onExpandedChange = { showOtherSources = it },
                onOpenWork = onOpenWork,
            )
            scheduleStatus(schedule, onRetry)
        }
    }
}

private fun LazyListScope.otherSourceItems(
    entries: List<BroadcastScheduleEntry>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenWork: (String) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "other_sources_toggle") {
        TextButton(onClick = { onExpandedChange(!expanded) }) {
            Text("其他来源（${entries.size}）")
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
    }
    if (expanded) {
        items(entries, key = { it.scheduleId }) { entry ->
            ScheduleEntryRow(entry, onClick = { onOpenWork(entry.workId) })
        }
    }
}

private fun LazyListScope.scheduleStatus(schedule: WeeklyBroadcastSchedule, onRetry: () -> Unit) {
    if (schedule.entriesByDay.values.all { it.isEmpty() }) {
        item {
            if (schedule.refreshing) {
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂无可用放送数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (schedule.errorMessage != null) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        }
    } else if (schedule.refreshing || schedule.stale) {
        item(key = "refresh_status") {
            Text(
                if (schedule.refreshing) "正在刷新时间表…" else "刷新失败，当前显示上次缓存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleEntryRow(entry: BroadcastScheduleEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(PrismCorner.Cover)
                .background(Surfaces.raisedColor),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(entry.titleCn ?: entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = scheduleText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}

private fun scheduleText(entry: BroadcastScheduleEntry): String {
    val time = entry.airingAtUtcMillis?.let {
        val dateTime = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        if (entry.timeToBeDetermined) {
            dateTime.format(DateTimeFormatter.ofPattern("M月d日 · E"))
        } else {
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    } ?: dayLabel(entry.dayOfWeek)
    return buildString {
        append(time)
        entry.episode?.let { append(" · 第").append(it).append("集") }
    }
}

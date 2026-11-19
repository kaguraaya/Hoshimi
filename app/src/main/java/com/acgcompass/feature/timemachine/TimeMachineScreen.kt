package com.acgcompass.feature.timemachine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.LocalHeaderInset
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarScrim

/**
 * 时光机路由入口。连接 [TimeMachineViewModel] 并把状态下发给无状态 [TimeMachineScreen]。
 *
 * 本地快照 / Bangumi 同步动作由 VM 提供，结果经 Snackbar 反馈（按钮不再无反应）。
 */
@Composable
fun TimeMachineRoute(
    modifier: Modifier = Modifier,
    onOpenWork: (String) -> Unit = {},
    viewModel: TimeMachineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    TimeMachineScreen(
        state = uiState,
        message = message,
        syncing = syncing,
        onSyncFromBangumi = viewModel::onSyncFromBangumi,
        onMessageShown = viewModel::clearMessage,
        onOpenWork = onOpenWork,
        modifier = modifier,
    )
}

/**
 * 无状态时光机界面（Material 3）：顶部动作栏（拍快照 / Bangumi 同步）+ 月/年报告卡 + 时间线，
 * 由 [StateScaffold] 统一渲染七态。动作栏始终可见，空态下也能拍首张快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeMachineScreen(
    state: UiState<TimeMachineUiState>,
    message: String? = null,
    syncing: Boolean = false,
    onSyncFromBangumi: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(message) {
        val m = message
        if (m != null) {
            snackbarHostState.showSnackbar(m)
            onMessageShown()
        }
    }
    // 同步按钮随列表滚动，不固定在顶栏里。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(Modifier.topBarScrim()) {
                AcgTopLevelTopBar(title = "时光机", scrim = false)
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { com.acgcompass.core.designsystem.AcgSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalHeaderInset provides innerPadding.calculateTopPadding()) {
                val syncBar: @Composable () -> Unit = {
                    SyncActionBar(syncing = syncing, onSyncFromBangumi = onSyncFromBangumi)
                }
                val hasContent = state is UiState.Success || state is UiState.PartialMissing
                if (!hasContent) {
                    Column(Modifier.fillMaxSize().padding(top = ScreenContentPadding.UnderBarTop)) {
                        Box(Modifier.padding(horizontal = ScreenContentPadding.Horizontal)) { syncBar() }
                        StateScaffold(state = state, modifier = Modifier.weight(1f)) {}
                    }
                } else {
                    StateScaffold(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                    ) { data ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // 底部留白避开悬浮底栏。
                            contentPadding = PaddingValues(
                                start = ScreenContentPadding.Horizontal,
                                end = ScreenContentPadding.Horizontal,
                                top = ScreenContentPadding.UnderBarTop,
                                bottom = ScreenContentPadding.Bottom,
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item(key = "sync") { syncBar() }
                            data.overview?.let { overview ->
                                item(key = "overview") { OverviewCard(overview = overview) }
                            }
                            data.baselineHeadline?.let { headline ->
                                item(key = "headline") {
                                    Text(
                                        text = headline,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (data.baselineGroups.isNotEmpty()) {
                                item(key = "baseline-title") {
                                    Text(
                                        text = "基线收藏（点击分组展开）",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                items(
                                    count = data.baselineGroups.size,
                                    key = { idx -> "baseline-${data.baselineGroups[idx].statusLabel}" },
                                ) { idx ->
                                    BaselineGroupCard(group = data.baselineGroups[idx], onOpenWork = onOpenWork)
                                }
                            }
                            item(key = "report") { ReportCard(report = data.report) }
                            data.timeline.forEach { period ->
                                item(key = "period-${period.label}") {
                                    Text(
                                        text = period.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                items(
                                    count = period.entries.size,
                                    key = { idx -> "${period.label}-$idx" },
                                ) { idx ->
                                    TimelineRow(entry = period.entries[idx], onOpenWork = onOpenWork)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 同步动作：从 Bangumi 同步收藏并生成时间线。 */
@Composable
private fun SyncActionBar(syncing: Boolean, onSyncFromBangumi: () -> Unit) {
    Button(
        onClick = onSyncFromBangumi,
        enabled = !syncing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (syncing) "同步中…" else "从 Bangumi 同步收藏",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶部概览卡：当前收藏数、最后同步时间、近期变化和本期评分均值。 */
@Composable
private fun OverviewCard(overview: TimeMachineOverviewUi) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth()) {
                OverviewMetric(
                    label = "当前收藏",
                    value = "${overview.collectionCount}",
                    modifier = Modifier.weight(1f),
                )
                OverviewMetric(
                    label = "近期变化",
                    value = "${overview.recentChangeCount}",
                    modifier = Modifier.weight(1f),
                )
                OverviewMetric(
                    label = "本期评分均值",
                    value = overview.avgRatingText,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "最后同步：${overview.lastSyncText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 概览卡内的单个指标列。 */
@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 可折叠的基线收藏分组卡，列出该状态下的作品。 */
@Composable
private fun BaselineGroupCard(group: BaselineGroupUi, onOpenWork: (String) -> Unit = {}) {
    var expanded by rememberSaveable(group.statusLabel) { mutableStateOf(false) }
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        // 标题行铺满卡片宽度，按压涟漪由卡片的圆角裁切（收起时就是整张卡片）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${group.statusLabel} · ${group.works.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (expanded) "收起 ▴" else "展开 ▾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.works.forEachIndexed { index, work ->
                    if (index > 0) FlatDivider()
                    BaselineWorkRow(work = work, onOpenWork = onOpenWork)
                }
            }
        }
    }
}

/** 基线单部作品：标题 / 类型 / 状态 / 我的评分 / 进度 / 同步时间。 */
@Composable
private fun BaselineWorkRow(work: BaselineWorkUi, onOpenWork: (String) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PrismCorner.Badge)
            .let { if (work.workId.isNotBlank()) it.clickable { onOpenWork(work.workId) } else it },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(work.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${work.typeLabel} · ${work.statusLabel} · ${work.ratingText} · ${work.progressText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "同步时间：${work.syncTimeText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 月/年报告卡。 */
@Composable
private fun ReportCard(report: TimeMachineReportUi) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("补番报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "变更总数 ${report.totalChanges} · 新增 ${report.addedCount} · 状态 ${report.statusChanges} · " +
                    "评分 ${report.ratingChanges} · 短评 ${report.reviewChanges} · 进度 ${report.progressChanges}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "本期评分均值 ${report.avgNewRatingText} · 最高 ${report.maxNewRatingText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.ratingFlipflops.isNotEmpty()) {
                Text("评分打脸现场", style = MaterialTheme.typography.titleSmall)
                report.ratingFlipflops.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (report.pastThoughts.isNotEmpty()) {
                Text("当时的短评", style = MaterialTheme.typography.titleSmall)
                report.pastThoughts.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** 单条时间线变更，展示日期、作品和当时的状态、评分与评价。 */
@Composable
private fun TimelineRow(entry: TimelineEntryUi, onOpenWork: (String) -> Unit = {}) {
    val wid = entry.workId
    PrismCard(
        onClick = { if (!wid.isNullOrBlank()) onOpenWork(wid) },
        enabled = !wid.isNullOrBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = TIMELINE_DATE_FORMATTER.format(java.time.Instant.ofEpochMilli(entry.changedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(entry.workTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 时间线条目日期格式。 */
private val TIMELINE_DATE_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

@Preview(showBackground = true)
@Composable
private fun TimeMachineScreenPreview() {
    AcgCompassTheme {
        TimeMachineScreen(
            state = UiState.Success(
                TimeMachineUiState(
                    timeline = listOf(
                        TimelinePeriodUi(
                            label = "2024 年 3 月",
                            entries = listOf(
                                TimelineEntryUi("孤独摇滚！", "评分：7 → 9 分", 0L),
                                TimelineEntryUi("葬送的芙莉莲", "新增到收藏：想看", 0L),
                            ),
                        ),
                    ),
                    report = TimeMachineReportUi(
                        totalChanges = 2,
                        addedCount = 1,
                        statusChanges = 0,
                        ratingChanges = 1,
                        reviewChanges = 0,
                        progressChanges = 0,
                        avgNewRatingText = "9.0 分",
                        maxNewRatingText = "9 分",
                        ratingFlipflops = listOf("《孤独摇滚！》：7 → 9 分"),
                        pastThoughts = emptyList(),
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun TimeMachineScreenEmptyPreview() {
    AcgCompassTheme {
        TimeMachineScreen(
            state = UiState.Empty(Cta("拍一张本地快照，开始记录你的补番轨迹", "snapshot")),
        )
    }
}

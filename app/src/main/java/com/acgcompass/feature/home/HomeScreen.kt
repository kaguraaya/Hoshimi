package com.acgcompass.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.FlatDefaults
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.PrismMetrics
import com.acgcompass.core.designsystem.SectionHeader
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarBlurSource
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val HOME_MOD_TODAY = "today_decision"
private const val HOME_MOD_SEASONAL_NEW = "seasonal_new"
private const val HOME_MOD_SEASONAL = "seasonal_hot"
private const val HOME_MOD_BACKLOG = "backlog_overview"
private const val HOME_MOD_CONTINUE = "continue"
private const val HOME_MOD_SYNC = "sync_reminder"
private const val HOME_MOD_DRAW = "daily_draw"

/**
 * 首页路由入口。连接 [HomeViewModel] 并把状态与导航回调下发给无状态 [HomeScreen]。
 *
 * @param onOpenRecommender 进入「今晚看什么」推荐器。
 * @param onOpenSearch 进入搜索（首页顶部搜索入口）。
 * @param onOpenImport 进入批量导入（补番收纳箱）。
 * @param onOpenWork 进入指定作品详情。
 */
@Composable
@Suppress("LongParameterList")
fun HomeRoute(
    onOpenRecommender: (List<String>) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenHomeModules: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val needsConfig by viewModel.needsBangumiConfig.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(syncMessage) {
        syncMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearSyncMessage()
        }
    }
    androidx.compose.runtime.LaunchedEffect(needsConfig) {
        if (needsConfig) {
            onOpenSettings()
            viewModel.clearNeedsBangumiConfig()
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onHomeVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.onHomeVisible() }
    HomeScreen(
        state = state,
        onManualSync = viewModel::onManualSync,
        onOpenRecommender = onOpenRecommender,
        onOpenSearch = onOpenSearch,
        onOpenImport = onOpenImport,
        onOpenWork = onOpenWork,
        onOpenHomeModules = onOpenHomeModules,
        onOpenSchedule = onOpenSchedule,
        modifier = modifier,
    )
}

/**
 * 无状态首页（Material 3）。今日决策中心：今晚看什么大卡、今日状态、继续看/读/玩、
 * 待补池概览、搜索与导入入口。空态由 [StateScaffold] 渲染导入引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun HomeScreen(
    state: UiState<HomeUiState>,
    onManualSync: () -> Unit,
    onOpenRecommender: (List<String>) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHomeModules: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
) {
    val hazeState = remember { HazeState() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            AcgTopLevelTopBar(
                title = "首页",
                hazeState = hazeState,
                actions = {
                    IconButton(onClick = onOpenHomeModules) {
                        Icon(Icons.Filled.Tune, contentDescription = "首页模块设置")
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Outlined.Inbox, contentDescription = "批量导入")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().topBarBlurSource(hazeState)) {
            StateScaffold(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onCta = { onOpenImport() },
            ) { data ->
                HomeContent(
                    data = data,
                    onManualSync = onManualSync,
                    onOpenRecommender = onOpenRecommender,
                    onOpenWork = onOpenWork,
                    onOpenSchedule = onOpenSchedule,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun HomeContent(
    data: HomeUiState,
    onManualSync: () -> Unit,
    onOpenRecommender: (List<String>) -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenSchedule: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 父级已消费标题栏 innerPadding；顶部 16dp 呼吸 + 底部 96dp 保证末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.Top,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(PrismMetrics.CardGap),
    ) {
        // 今晚看什么大卡。
        if (HOME_MOD_TODAY in data.enabledModules) {
            item(key = "hero") {
                TonightHeroCard(onClick = { onOpenRecommender(emptyList()) })
            }

            // 今日状态——重定义为「今晚看什么」快捷入口：点某心情直接带预填标签跳推荐器。
            item(key = "moods") {
                FlatCard(
                    title = "今晚想看哪种？",
                    icon = Icons.Outlined.Mood,
                    supporting = "选一个状态，对应标签会带入推荐筛选",
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        data.moods.forEach { mood ->
                            FlatFilterChip(
                                selected = false,
                                onClick = { onOpenRecommender(mood.presetTags) },
                                label = { Text(mood.label) },
                            )
                        }
                    }
                }
            }
        }

        if (HOME_MOD_SEASONAL_NEW in data.enabledModules) {
            item(key = "seasonal_new_title") { SectionTitle("本季新番") }
            if (data.seasonalNew.isEmpty()) {
                item(key = "seasonal_new_empty") {
                    EmptyHintCard("本季新番正在准备中，也可以前往发现页查看本季榜单")
                }
            } else {
                items(data.seasonalNew, key = { "seasonal_new_${it.workId}" }) { card ->
                    WorkCard(model = card.card, onClick = { onOpenWork(card.workId) })
                }
            }
        }

        // 时间表：显示当前时刻之后的下一组放送。
        if (HOME_MOD_SEASONAL in data.enabledModules) {
            item(key = "schedule_title") {
                SectionHeader("时间表") {
                    IconButton(onClick = onOpenSchedule) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "查看完整时间表")
                    }
                }
            }
            item(key = "schedule_body") {
                ScheduleCard(data = data, onOpenWork = onOpenWork, onOpenSchedule = onOpenSchedule)
            }
        }

        if (HOME_MOD_CONTINUE in data.enabledModules) {
            item(key = "continue") {
                SectionTitle("继续看 / 读 / 玩")
            }
            if (data.continueItems.isEmpty()) {
                item(key = "continue_empty") {
                    EmptyHintCard("暂无进行中的作品，可从待补池选择一部开始")
                }
            } else {
                items(data.continueItems, key = { "continue_${it.workId}" }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkCard(model = item.card, onClick = { onOpenWork(item.workId) })
                        val progress = listOfNotNull(item.progressText, item.nextUpText)
                        if (progress.isNotEmpty()) {
                            HintText(progress.joinToString(separator = " · "))
                        }
                    }
                }
            }
        }

        // 待补池概览。
        if (HOME_MOD_BACKLOG in data.enabledModules) {
            item(key = "backlog_overview") {
                SectionTitle("待补池概览 · 共 ${data.backlogSummary.totalCount} 部")
            }
            data.backlogSummary.recentlyAdded?.let { card ->
                item(key = "recent_${card.workId}") {
                    LabeledWorkCard("最近加入", card.card) { onOpenWork(card.workId) }
                }
            }
            data.backlogSummary.longestDust?.let { card ->
                item(key = "dust_${card.workId}") {
                    LabeledWorkCard("吃灰最久", card.card) { onOpenWork(card.workId) }
                }
            }
            if (data.backlogSummary.shortPickable.isNotEmpty()) {
                item(key = "short_title") { SubSectionTitle("短篇可补") }
                items(data.backlogSummary.shortPickable, key = { "short_${it.workId}" }) { card ->
                    WorkCard(model = card.card, onClick = { onOpenWork(card.workId) })
                }
            }
        }

        // 同步提醒。仅在至少配置一个可同步源时展示。
        if (HOME_MOD_SYNC in data.enabledModules) {
            data.syncReminder?.let { reminder ->
                item(key = "sync_reminder") {
                    SyncReminderCard(reminder = reminder, onManualSync = onManualSync)
                }
            }
        }

        // 今日补番签。
        if (HOME_MOD_DRAW in data.enabledModules) {
            data.dailyDraw?.let { draw ->
                item(key = "daily_draw") {
                    DailyDrawCard(draw = draw)
                }
            }
        }
    }
}

/** 时间表：成组的行画在同一张卡片里，行间一条发丝线；空 / 加载 / 出错时卡片里给出说明。 */
@Composable
private fun ScheduleCard(data: HomeUiState, onOpenWork: (String) -> Unit, onOpenSchedule: () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        when {
            data.upcomingBroadcasts.isNotEmpty() -> data.upcomingBroadcasts.forEachIndexed { index, entry ->
                if (index > 0) FlatDivider(Modifier.padding(start = SCHEDULE_DIVIDER_INSET))
                BroadcastScheduleRow(entry, onClick = { onOpenWork(entry.workId) })
            }
            data.scheduleRefreshing -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            data.scheduleError != null -> Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { HintText("时间表暂时不可用") }
                TextButton(onClick = onOpenSchedule) { Text("重试") }
            }
            else -> Box(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) { HintText("时间表暂无即将放送内容") }
        }
    }
}

@Composable
private fun BroadcastScheduleRow(entry: BroadcastScheduleEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = PrismMetrics.RowMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = entry.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(SCHEDULE_COVER)
                .clip(PrismCorner.Thumb)
                .background(Surfaces.raisedColor),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.titleCn ?: entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = scheduleTimeText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SCHEDULE_COVER = 44.dp
private val SCHEDULE_DIVIDER_INSET = 72.dp

private fun scheduleTimeText(entry: BroadcastScheduleEntry): String {
    val time = entry.airingAtUtcMillis?.let {
        val dateTime = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        if (entry.timeToBeDetermined) {
            dateTime.format(DateTimeFormatter.ofPattern("M月d日 · E"))
        } else {
            dateTime.format(DateTimeFormatter.ofPattern("E HH:mm"))
        }
    } ?: entry.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.CHINA)
    return buildString {
        append(time)
        entry.episode?.let { append(" · 第").append(it).append("集") }
    }
}

@Composable
private fun SyncReminderCard(reminder: SyncReminder, onManualSync: () -> Unit) {
    FlatCard(
        title = if (reminder.hasFailure) "同步提醒 · 有源测试失败" else "同步提醒",
        icon = Icons.Outlined.Sync,
        // 出错的卡片整张换成错误容器色，不加描边。
        containerColor = if (reminder.hasFailure) MaterialTheme.colorScheme.errorContainer else Color.Unspecified,
        trailing = { TextButton(onClick = onManualSync) { Text("立即同步") } },
    ) {
        reminder.lines.forEach { line ->
            val prefix = if (line.failed) "⚠ " else ""
            HintText("$prefix${line.sourceLabel} · ${line.statusText}")
        }
    }
}

@Composable
private fun DailyDrawCard(draw: DailyDraw) {
    FlatCard(title = "今日补番签", icon = Icons.Outlined.AutoAwesome) {
        DrawLine("宜", draw.shouldText)
        DrawLine("忌", draw.shouldNotText)
    }
}

/** 色块里的一行：左边一个小标记，右边正文。 */
@Composable
private fun DrawLine(mark: String, text: String) {
    Surface(shape = PrismCorner.Block, color = Surfaces.raisedColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(mark, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 今晚看什么：中性卡片 + 这一屏唯一的主色动作（右侧的圆形按钮）。主色不铺满整张卡片。 */
@Composable
private fun TonightHeroCard(onClick: () -> Unit) {
    PrismCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = PrismCorner.SectionCard) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(FlatDefaults.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "今晚看什么？", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "按可用时间、偏好与待补池内容生成推荐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.size(HERO_ACTION).semantics { contentDescription = "开始决策" },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

private val HERO_ACTION = 56.dp

@Composable
private fun LabeledWorkCard(label: String, model: WorkCardUiModel, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SubSectionTitle(label)
        WorkCard(model = model, onClick = onClick)
    }
}

@Composable
private fun SectionTitle(text: String) {
    SectionHeader(text)
}

/** 空状态说明：和有内容时一样占一张卡片，页面结构不会因为没数据而塌成一行字。 */
@Composable
private fun EmptyHintCard(text: String) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) { HintText(text) }
    }
}

@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AcgCompassTheme {
        HomeScreen(
            state = UiState.Success(
                HomeUiState(
                    backlogSummary = BacklogSummary(totalCount = 3),
                    syncReminder = SyncReminder(
                        lines = listOf(
                            SyncReminderLine("Bangumi", "最近同步 · 刚刚", failed = false),
                            SyncReminderLine("AniList", "尚未同步", failed = false),
                        ),
                        hasFailure = false,
                    ),
                    dailyDraw = DailyDraw(
                        shouldText = "补一部短篇，今晚就能看完",
                        shouldNotText = "忌挑超长篇硬啃，今晚时间不够",
                    ),
                ),
            ),
            onManualSync = {},
            onOpenRecommender = {},
            onOpenSearch = {},
            onOpenImport = {},
            onOpenWork = {},
        )
    }
}

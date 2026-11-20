package com.acgcompass.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.copyOnLongPress

/**
 * 导航参数键：作品详情页的 `workId`，由路由 [detailRoute] 携带，
 * 经 [androidx.lifecycle.SavedStateHandle] 注入 [DetailViewModel]。
 */
const val DETAIL_ARG_WORK_ID: String = "workId"

/** 详情页路由模板（含参数占位符），在导航图中注册时使用。 */
const val DETAIL_ROUTE_PATTERN: String = "detail/{$DETAIL_ARG_WORK_ID}"

internal const val VNDB_ORIGINAL_PRELOAD_DELAY_MS = 800L

/** 构造跳转到指定作品详情页的具体路由（对 [workId] 做 URL 编码兜底）。 */
fun detailRoute(workId: String): String = "detail/${workId.trim()}"

/**
 * 作品详情页路由入口。
 *
 * 通过 [hiltViewModel] 取得 [DetailViewModel]（`workId` 已由 SavedStateHandle 注入），
 * 观察其 `StateFlow<UiState<DetailUiState>>`，交由 [StateScaffold] 统一渲染七态；
 * 数据就绪（成功 / 字段缺失）时渲染 [DetailScreen]。
 */
@Composable
fun DetailRoute(
    modifier: Modifier = Modifier,
    onOpenDoc: (String) -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    onOpenVndbProducer: (String, String) -> Unit = { _, _ -> },
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aiMatch by viewModel.aiMatch.collectAsStateWithLifecycle()
    val recordMessage by viewModel.recordMessage.collectAsStateWithLifecycle()
    val trashEntries by viewModel.trashEntries.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    StateScaffold(
        state = state,
        modifier = modifier.fillMaxSize(),
        onRetry = viewModel::retry,
        onOpenDoc = onOpenDoc,
    ) { detail ->
        // 详情页没有顶栏，指示器从状态栏下沿滑出。
        com.acgcompass.core.designsystem.AcgPullRefreshBox(
            refreshing = refreshing,
            onRefresh = viewModel::onPullRefresh,
            indicatorTopPadding = androidx.compose.foundation.layout.WindowInsets.statusBars
                .asPaddingValues().calculateTopPadding(),
            modifier = Modifier.fillMaxSize(),
        ) {
            DetailScreen(
                state = detail,
                aiMatch = aiMatch,
                recordMessage = recordMessage,
                trashNotice = trashNoticeOf(trashEntries),
                actions = DetailScreenActions(
                    onToggleBacklog = viewModel::onToggleBacklog,
                    onRestoreFromTrash = viewModel::onRestoreFromTrash,
                    onOpenWork = onOpenWork,
                    onOpenVndbProducer = onOpenVndbProducer,
                    onAddMainline = viewModel::onAddMainlineToBacklog,
                    onAddAllSeries = viewModel::onAddAllSeriesToBacklog,
                    onAnalyzeMatchWithAi = viewModel::onAnalyzeMatchWithAi,
                    onUpdateMyRecord = viewModel::onUpdateMyRecord,
                    onSyncVndbRecordToBangumi = viewModel::onSyncVndbRecordToBangumi,
                    onRetry = viewModel::retry,
                ),
            )
        }
    }
}

/**
 * 详情页内容（纯展示，无 ViewModel 依赖，便于预览与测试）。
 *
 * 当前覆盖：
 * - 顶部信息区：封面 + 标题 + 信息行。
 * - 评分区：四平台分别展示分数 / 人数，缺失平台显示「暂无数据」且不隐藏整区。
 * - 社区共识卡：稳定度 / 争议度 / 优先级；样本不足时低置信 / 暂无、不伪造。
 *
 * 个人区、决策区与详情 Tab 共用本无状态组件。
 */
@Suppress("LongParameterList") // 页面状态与两类一次性提示分开传，预览可以单独构造
@Composable
fun DetailScreen(
    state: DetailUiState,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    recordMessage: String? = null,
    trashNotice: String? = null,
    actions: DetailScreenActions = DetailScreenActions(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // 详情页没有顶栏：自己让出状态栏。
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            // 顶部额外 8dp 呼吸（共约 24dp），底部保留 24dp 避免末项贴边。
            .padding(16.dp)
            .padding(top = 8.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderSection(header = state.header)
        VndbExtensionSection(
            detail = state.vndbDetail,
            loading = state.vndbLoading,
            error = state.vndbError,
            onOpenWork = actions.onOpenWork,
            onOpenProducer = actions.onOpenVndbProducer,
        )
        RatingsSection(ratings = state.ratings)
        ConsensusSection(consensus = state.consensus)
        // 真实 Bangumi 社区标签区（取代原决策区「适合心情」的间接推断）。
        TagsSection(tags = state.tags)
        PersonalSection(
            personal = state.personal,
            actions = PersonalSectionActions(
                onToggleBacklog = actions.onToggleBacklog,
                onUpdateMyRecord = actions.onUpdateMyRecord,
                onSyncToBangumi = actions.onSyncVndbRecordToBangumi,
                onRestoreFromTrash = actions.onRestoreFromTrash,
            ),
            options = PersonalSectionOptions(
                recordMessage = recordMessage,
                trashNotice = trashNotice,
                localOnly = state.isVndb,
                canSyncToBangumi = state.vndbBangumiLinked,
            ),
        )
        DecisionSection(
            decision = state.decision,
            aiMatch = aiMatch,
            onAnalyzeMatchWithAi = actions.onAnalyzeMatchWithAi,
        )
        WatchRouteSection(
            entries = state.routeEntries,
            onOpenWork = actions.onOpenWork,
            onAddMainline = actions.onAddMainline,
            onAddAllSeries = actions.onAddAllSeries,
        )
        DetailTabsSection(tabs = state.tabs, onRetry = actions.onRetry)
        CompletionCostSection(completionCost = state.completionCost)
    }
}

/** 观看路线区：按主线、可选和可跳过分区，并支持批量加入待补池。 */
@Composable
internal fun WatchRouteSection(
    entries: List<RouteEntryUi>,
    onOpenWork: (String) -> Unit,
    onAddMainline: () -> Unit,
    onAddAllSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    SectionCard(title = "观看路线（智能选择主线）", modifier = modifier) {
        val sections = listOf(
            RouteSection.MAIN to "主线必看",
            RouteSection.OPTIONAL to "可选",
            RouteSection.SKIPPABLE to "可跳过",
            RouteSection.DERIVED to "衍生作 / 原作（非动画）",
        )
        sections.forEach { (section, label) ->
            val rows = entries.filter { it.section == section }
            if (rows.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                rows.forEach { row ->
                    Text(
                        text = "· ${row.title}（${row.relationLabel}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PrismCorner.Badge)
                            .clickable { onOpenWork(row.workId) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            "路线按关联作品的关系本地推断，仅供参考；不确定处保持「可选」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddMainline) { Text("加入主线必看", maxLines = 1) }
            FilledTonalButton(onClick = onAddAllSeries) { Text("加入全系列", maxLines = 1) }
        }
    }
}

// region 顶部信息区

@Composable
internal fun HeaderSection(header: DetailHeader, modifier: Modifier = Modifier) {
    // 点击封面查看大图 + 下载。
    var showCover by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var sensitiveRevealed by remember(header.coverUrl) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverImage(
            coverUrl = header.coverUrl,
            contentDescription = header.title,
            blurred = header.sensitiveMedia && !sensitiveRevealed,
            onClick = if (header.coverUrl != null) {
                {
                    if (header.sensitiveMedia && !sensitiveRevealed) {
                        sensitiveRevealed = true
                    } else {
                        showCover = true
                    }
                }
            } else {
                null
            },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = header.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                // 长按复制标题：方便拿去别处搜索。
                modifier = Modifier.copyOnLongPress(header.title),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                header.infoRows.forEach { row -> InfoRow(row) }
            }
        }
    }
    if (showCover && header.coverUrl != null) {
        CoverViewerDialog(
            media = ViewerMedia(
                originalUrl = header.originalCoverUrl ?: header.coverUrl,
                previewUrl = header.coverUrl,
                title = header.title,
            ),
            onDismiss = { showCover = false },
            onMessage = { msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
internal fun InfoRow(row: DetailInfoRow, modifier: Modifier = Modifier) {
    val valueColor = if (row.isMissing) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            // 别名、原名、制作方这些也常被拿去搜索：同样长按复制。
            modifier = Modifier.weight(1f).then(if (row.isMissing) Modifier else Modifier.copyOnLongPress(row.value)),
        )
    }
}

@Composable
internal fun CoverImage(
    coverUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    blurred: Boolean = false,
) {
    val shape = PrismCorner.Poster
    Box(
        modifier = modifier
            .size(width = 108.dp, height = 144.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().then(if (blurred) Modifier.blur(18.dp) else Modifier),
                // 裁切铺满，圆角才落在图片上（完整显示时上下会留空，像被切平）。
                contentScale = ContentScale.Crop,
            )
            if (blurred) {
                Text(
                    text = "敏感图片\n点按显示",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = NO_DATA,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// endregion

// region 评分区

@Composable
internal fun RatingsSection(ratings: List<PlatformRatingUiModel>, modifier: Modifier = Modifier) {
    SectionCard(title = "多平台评分", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ratings.forEach { rating -> PlatformRatingRow(rating) }
        }
    }
}

@Composable
internal fun PlatformRatingRow(rating: PlatformRatingUiModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rating.platform,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(120.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (rating.available) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = rating.scoreText.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val voteText = rating.voteText
                if (voteText != null) {
                    Text(
                        text = voteText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // 缺失平台仍渲染该行并显示「暂无数据」，不隐藏整个评分区。
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

// region 社区共识卡

@Composable
internal fun ConsensusSection(consensus: ConsensusUiModel, modifier: Modifier = Modifier) {
    SectionCard(title = "社区共识", modifier = modifier) {
        when (consensus) {
            is ConsensusUiModel.Available -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConsensusMetricRow(consensus.stability)
                    ConsensusMetricRow(consensus.controversy)
                    ConsensusMetricRow(consensus.priority)
                }
            }

            ConsensusUiModel.Insufficient -> {
                // 样本不足不下结论，低置信 / 暂无，绝不伪造客观结论。
                Text(
                    text = "样本不足，暂不给出共识结论（$NO_DATA）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ConsensusMetricRow(metric: ConsensusMetric, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${metric.percentText} · ${metric.qualitativeText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { metric.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 作品标签区——展示真实 Bangumi 社区标签（按热度序），取代原「适合心情」的间接推断。空则不渲染。 */
@Composable
internal fun TagsSection(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    SectionCard(title = "标签", modifier = modifier) {
        ChipFlowRow(items = tags)
    }
}

data class DetailScreenActions(
    val onToggleBacklog: () -> Unit = {},
    val onRestoreFromTrash: () -> Unit = {},
    val onOpenWork: (String) -> Unit = {},
    val onOpenVndbProducer: (String, String) -> Unit = { _, _ -> },
    val onAddMainline: () -> Unit = {},
    val onAddAllSeries: () -> Unit = {},
    val onAnalyzeMatchWithAi: () -> Unit = {},
    val onUpdateMyRecord: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit =
        { _, _, _, _, _, _ -> },
    val onSyncVndbRecordToBangumi: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

// endregion

// region 详情 Tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTabsSection(tabs: List<DetailTabUiModel>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (tabs.isEmpty()) return
    SectionCard(title = "详情", modifier = modifier) {
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
        // 分段药丸代替带下划线的标签栏（照 FlClash）。
        PillTabs(
            options = tabs.indices.toList(),
            selected = safeIndex,
            onSelect = { selectedIndex = it },
            label = { tabs[it].title },
            scrollable = true,
        )
        val selected = tabs[safeIndex]
        Text(
            text = selected.body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected.available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (selected.status == TabStatus.LOADING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (selected.status == TabStatus.FAILED) {
            TextButton(onClick = onRetry) { Text("重新加载") }
        }
    }
}

// endregion

// region 补完成本

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompletionCostSection(completionCost: CompletionCostUiModel, modifier: Modifier = Modifier) {
    SectionCard(title = "补完成本", modifier = modifier) {
        if (completionCost.available) {
            TagPill(text = completionCost.bucketLabel, tone = TagPillTone.Primary)
            Text(
                text = completionCost.detailText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 无足够单位信息计算时显示「暂无数据」（绝不伪造）。
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

/** 标签 / 心情 chip 流式布局。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlowRow(items: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FlatAssistChip(
                onClick = {},
                label = { Text(text = item) },
            )
        }
    }
}

/**
 * 统一的区块卡片：FlClash 式分区卡片（圆角 32、内边距 20×18、18dp 小图标 + 标题），
 * 取比页面高一级的底色、不描边。图标按标题挑（[sectionIconFor]）。
 */
@Composable
internal fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlatCard(modifier = modifier, title = title, icon = sectionIconFor(title)) { content() }
}

internal fun sectionIconFor(title: String): ImageVector =
    SECTION_ICONS.firstOrNull { (keywords, _) -> keywords.any { it in title } }?.second ?: Icons.Outlined.Info

/** 标题关键词 → 分区图标（按顺序匹配第一个）。 */
internal val SECTION_ICONS: List<Pair<List<String>, ImageVector>> = listOf(
    listOf("路线") to Icons.Outlined.Route,
    listOf("评分") to Icons.Outlined.StarOutline,
    listOf("共识") to Icons.Outlined.Groups,
    listOf("记录") to Icons.Outlined.EditNote,
    listOf("决策") to Icons.Outlined.Lightbulb,
    listOf("标签") to Icons.AutoMirrored.Outlined.Label,
    listOf("VNDB") to Icons.Outlined.AutoStories,
    listOf("截图") to Icons.Outlined.Image,
    listOf("开发商", "发行商") to Icons.Outlined.Business,
    listOf("关联") to Icons.Outlined.Link,
    listOf("补完") to Icons.Outlined.Schedule,
    listOf("剧透", "雷达") to Icons.Outlined.Radar,
)

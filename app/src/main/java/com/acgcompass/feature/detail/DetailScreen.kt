package com.acgcompass.feature.detail

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.copyOnLongPress
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelImage
import com.acgcompass.feature.visualnovel.ManagedImageSpec
import com.acgcompass.feature.visualnovel.ManagedVisualNovelImage
import com.acgcompass.feature.visualnovel.managedImageRequest
import kotlinx.coroutines.delay

/**
 * 导航参数键：作品详情页的 `workId`，由路由 [detailRoute] 携带，
 * 经 [androidx.lifecycle.SavedStateHandle] 注入 [DetailViewModel]。
 */
const val DETAIL_ARG_WORK_ID: String = "workId"

/** 详情页路由模板（含参数占位符），在导航图中注册时使用。 */
const val DETAIL_ROUTE_PATTERN: String = "detail/{$DETAIL_ARG_WORK_ID}"

private const val VNDB_ORIGINAL_PRELOAD_DELAY_MS = 800L

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
    StateScaffold(
        state = state,
        modifier = modifier.fillMaxSize(),
        onRetry = viewModel::retry,
        onOpenDoc = onOpenDoc,
    ) { detail ->
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
private fun WatchRouteSection(
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
private fun HeaderSection(header: DetailHeader, modifier: Modifier = Modifier) {
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
private fun InfoRow(row: DetailInfoRow, modifier: Modifier = Modifier) {
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
private fun CoverImage(
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
private fun RatingsSection(ratings: List<PlatformRatingUiModel>, modifier: Modifier = Modifier) {
    SectionCard(title = "多平台评分", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ratings.forEach { rating -> PlatformRatingRow(rating) }
        }
    }
}

@Composable
private fun PlatformRatingRow(rating: PlatformRatingUiModel, modifier: Modifier = Modifier) {
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
private fun ConsensusSection(consensus: ConsensusUiModel, modifier: Modifier = Modifier) {
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
private fun ConsensusMetricRow(metric: ConsensusMetric, modifier: Modifier = Modifier) {
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

// endregion

// region 个人区

/** 「在回收站中」：这部作品的待补池条目或收藏在回收站里，可以一键恢复成原来的样子。 */
@Composable
private fun TrashNotice(text: String, onRestore: () -> Unit) {
    Surface(shape = PrismCorner.Block, color = Surfaces.raisedColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text("恢复") }
        }
    }
}

/** 回收站记录 → 详情页提示文字；不在回收站时为 null。 */
internal fun trashNoticeOf(entries: List<com.acgcompass.domain.model.TrashEntry>): String? {
    if (entries.isEmpty()) return null
    val latest = entries.maxBy { it.removedAt }
    val date = java.time.Instant.ofEpochMilli(latest.removedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val what = entries.joinToString("和") {
        if (it.kind == com.acgcompass.domain.model.TrashKind.BACKLOG) "待补池记录" else "「${it.status ?: "收藏"}」记录"
    }
    return "在回收站中：$date 移除的$what，恢复后加入时间和状态保持原样"
}

@Composable
private fun PersonalSection(
    personal: PersonalUiModel,
    actions: PersonalSectionActions,
    options: PersonalSectionOptions,
    modifier: Modifier = Modifier,
) {
    var showEdit by remember { mutableStateOf(false) }
    SectionCard(title = "我的记录", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.trashNotice?.let { TrashNotice(it, actions.onRestoreFromTrash) }
            // 作品归属徽章——我的库状态 / 是否在待补池 / 是否在吃灰区，互不混淆。
            MembershipBadges(personal = personal)
            if (personal.recordEditable) {
                PersonalRow(label = "我的状态", value = personal.statusText)
                PersonalRow(label = "我的评分", value = personal.ratingText)
                // 仅动画展示「我的进度」（书籍/漫画/游戏隐藏）。
                if (personal.progressEditable) {
                    PersonalRow(label = "我的进度", value = personal.progressText)
                }
                PersonalRow(label = "我的短评", value = personal.reviewText)
                // 明确展示记录的私密或公开状态。
                PersonalRow(label = "可见性", value = if (personal.isPrivate) "仅自己可见（私密）" else "公开")
                // 编辑我的记录（状态/评分/进度/短评）并回写 Bangumi。
                FilledTonalButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "编辑我的记录")
                }
                if (options.localOnly) {
                    Text(
                        text = if (options.canSyncToBangumi) {
                            "这是一条 VNDB 本地记录；只有点击下方按钮时才会同步到已匹配的 Bangumi 词条。"
                        } else {
                            "这是一条 VNDB 本地记录，当前没有可可靠匹配的 Bangumi 词条。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (options.canSyncToBangumi && personal.inLibrary) {
                        FilledTonalButton(onClick = actions.onSyncToBangumi, modifier = Modifier.fillMaxWidth()) {
                            Text("同步这条记录到 Bangumi")
                        }
                    }
                }
            } else {
                // 无 Bangumi 词条——无法同步个人状态，仅提供加入待补池。
                Text(
                    text = "该作品在 Bangumi 无对应词条，无法记录 / 同步「我的状态·评分」；可先加入待补池。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (options.recordMessage != null) {
                Text(
                    text = options.recordMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // 根据是否已在待补池切换「加入 / 移出待补池」按钮形态。
            if (personal.inBacklog) {
                FilledTonalButton(
                    onClick = actions.onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "移出待补池")
                }
            } else {
                Button(
                    onClick = actions.onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "加入待补池")
                }
            }
        }
    }
    if (showEdit) {
        EditMyRecordDialog(
            personal = personal,
            localOnly = options.localOnly,
            onDismiss = { showEdit = false },
            onSave = { status, rating, progress, comment, tags, private ->
                actions.onUpdateMyRecord(status, rating, progress, comment, tags, private)
                showEdit = false
            },
        )
    }
}

/**
 * 编辑「我的记录」对话框——状态 / 评分 / 进度 / 短评，保存即回写 Bangumi。
 * 数据源统一 Bangumi（不涉及其他源）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditMyRecordDialog(
    personal: PersonalUiModel,
    localOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit,
) {
    val statuses = listOf("想看", "在看", "看过", "搁置", "抛弃")
    val initRating: String = personal.ratingText?.takeWhile { it.isDigit() } ?: ""
    val initProgress: String = personal.progressText?.filter { it.isDigit() } ?: ""
    var status: String? by remember { mutableStateOf(personal.statusText) }
    var ratingText: String by remember { mutableStateOf(initRating) }
    var progressText: String by remember { mutableStateOf(initProgress) }
    var comment: String by remember { mutableStateOf(personal.reviewText ?: "") }
    var tagsText: String by remember { mutableStateOf(personal.tags.joinToString(" ")) }
    // 回显当前可见性，避免保存时意外改变私密状态。
    var isPrivate: Boolean by remember { mutableStateOf(personal.isPrivate) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我的记录") },
        text = {
            // 编辑记录内容较多（+ 软键盘弹出）时加竖向滚动，避免底部「仅自己可见」说明 / 开关被挤出看不全。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "提示：不选状态 = 清空（无状态）；评分 / 进度留空 = 清空。有进度会自动判为在看，看满总集数判为看过。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("状态", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statuses.forEach { s ->
                        FlatFilterChip(
                            selected = status == s,
                            onClick = { status = if (status == s) null else s },
                            label = { Text(s) },
                        )
                    }
                }
                FlatTextField(
                    value = ratingText,
                    onValueChange = { v: String -> ratingText = v.filter { it.isDigit() }.take(2) },
                    label = { Text("评分 0-10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (personal.progressEditable) {
                    FlatTextField(
                        value = progressText,
                        onValueChange = { v: String ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            // 进度不得超过总集数上限。
                            val capped = digits.toIntOrNull()?.let { n ->
                                personal.totalEpisodes?.let { total -> n.coerceAtMost(total).toString() } ?: digits
                            } ?: digits
                            progressText = capped
                        },
                        label = {
                            Text(
                                personal.totalEpisodes?.let { "进度（话数，共 $it 话）" } ?: "进度（话数）",
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlatTextField(
                    value = comment,
                    onValueChange = { v: String -> comment = v },
                    label = { Text("短评") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlatTextField(
                    value = tagsText,
                    onValueChange = { v: String -> tagsText = v },
                    label = { Text("标签（空格分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 开启后，本条评分、短评和标签不会在 Bangumi 公开展示。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅自己可见（私密）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后，本条评分 / 短评 / 标签在 Bangumi 个人主页不公开展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlatSwitch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val rating: Int? = ratingText.toIntOrNull()?.coerceIn(0, 10)
                val progress: Int? = progressText.toIntOrNull()?.coerceAtLeast(0)
                val commentArg: String? = if (comment.isBlank()) null else comment
                val tagsArg: List<String>? = tagsText.split(Regex("[\\s,，、]+"))
                    .map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                onSave(status, rating, progress, commentArg, tagsArg, isPrivate)
            }) { Text(if (localOnly) "仅保存到本地" else "保存并同步") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 作品归属徽章。一行内用 chip 表达三处归属，互不混淆：
 * - 我的库：有任意个人收藏记录则显示状态（如「我的库 · 在看」），否则「未入库」。
 * - 待补池：在 / 不在。
 * - 吃灰区：在吃灰区时才高亮提示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembershipBadges(personal: PersonalUiModel, modifier: Modifier = Modifier) {
    val libraryText = when {
        personal.statusText != null -> "我的库 · ${personal.statusText}"
        personal.inLibrary -> "已入我的库"
        else -> "未入我的库"
    }
    val backlogText = if (personal.inBacklog) "待补池 · 已加入" else "待补池 · 未加入"
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlatAssistChip(onClick = {}, label = { Text(text = libraryText) })
        FlatAssistChip(onClick = {}, label = { Text(text = backlogText) })
        if (personal.inDustMuseum) {
            FlatAssistChip(onClick = {}, label = { Text(text = "吃灰区") })
        }
    }
}

@Composable
private fun PersonalRow(label: String, value: String?, modifier: Modifier = Modifier) {
    val isMissing = value == null
    val display = value ?: NO_DATA
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isMissing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// endregion

// region 决策区

@Composable
private fun DecisionSection(
    decision: DecisionUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    SectionCard(title = "决策助手", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TasteMatchBlock(
                tasteMatch = decision.tasteMatch,
                aiMatch = aiMatch,
                onAnalyzeMatchWithAi = onAnalyzeMatchWithAi,
            )
            // 补番优先级（可解释：综合口味/社区/补完成本/情绪风险）。
            if (decision.priorityLevel != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "补番优先级", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "优先级：${decision.priorityLevel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    decision.priorityReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ReasonsBlock(title = "推荐理由", reasons = decision.recommendReasons)
            ReasonsBlock(title = "需注意 / 不推荐", reasons = decision.notRecommendReasons)
            // 无剧透评价雷达：展示无剧透摘要 + 生成方式 / 剧透等级 / 来源标注 / 置信度；
            // 未生成时摘要为「暂无数据」，绝不伪造。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "无剧透评价雷达", style = MaterialTheme.typography.titleSmall)
                // 生成方式 + 剧透等级标注。
                val radarMeta = listOfNotNull(
                    decision.spoilerRadarGenerator,
                    decision.spoilerRadarLevel,
                    decision.spoilerRadarConfidenceText,
                ).joinToString(" · ")
                if (radarMeta.isNotEmpty()) {
                    Text(
                        text = radarMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = decision.spoilerRadarSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 摘要来源标注（短评 / Reviews / 标签 / AI），帮助用户判断摘要可信度。
                if (decision.spoilerRadarSources.isNotEmpty()) {
                    Text(
                        text = "来源：${decision.spoilerRadarSources.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 补完成本一句话摘要。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "补完成本", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = decision.completionCostText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 作品标签区——展示真实 Bangumi 社区标签（按热度序），取代原「适合心情」的间接推断。空则不渲染。 */
@Composable
private fun TagsSection(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    SectionCard(title = "标签", modifier = modifier) {
        ChipFlowRow(items = tags)
    }
}

@Composable
private fun TasteMatchBlock(
    tasteMatch: TasteMatchUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 标题行内嵌「AI 分析」按钮——口味匹配度默认本地算法，可点击 AI 给出数值+简评。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "口味匹配度", style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(
                onClick = onAnalyzeMatchWithAi,
                enabled = aiMatch !is AiMatchUi.Loading,
            ) {
                Text(text = if (aiMatch is AiMatchUi.Loading) "分析中…" else "AI 分析", maxLines = 1)
            }
        }
        // AI 分析完成后主数值换成 AI 的结果，内置算法的数值在下方保留一行；否则展示内置算法的结果。
        if (aiMatch is AiMatchUi.Result) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "AI 评估", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = aiMatch.matchPercentText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LinearProgressIndicator(progress = { aiMatch.fraction }, modifier = Modifier.fillMaxWidth())
            AiPredictionExtras(aiMatch)
            aiMatch.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
            aiMatch.riskReasons.forEach {
                Text(
                    text = "- $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${aiMatch.confidenceText} · 由 AI 分析得出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LocalEstimateLine(tasteMatch)
        } else {
            when (tasteMatch) {
                is TasteMatchUiModel.Available -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tasteMatch.qualitativeText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = tasteMatch.percentText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { tasteMatch.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 分维度贡献理由（+ 命中 / − 拉低），正负分色，逐条展示可解释性。
                    tasteMatch.reasons.forEach { r ->
                        Text(
                            text = (if (r.positive) "+ " else "− ") + r.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.positive) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (tasteMatch.reason.isNotBlank()) {
                        Text(
                            text = tasteMatch.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tasteMatch.prediction?.let { RatingPredictionBlock(it) }
                    // 置信度文案（独立展示，不塞进理由长句）。
                    tasteMatch.confidenceText?.let { conf ->
                        Text(
                            text = conf,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is TasteMatchUiModel.Unavailable -> {
                    // 未生成或样本不足时不显示推测分数，并给出具体原因。
                    Text(
                        text = tasteMatch.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Loading / NotConfigured / Error 提示（Result 已在上方替换主值）。
            AiMatchContent(aiMatch = aiMatch)
        }
    }
}

/**
 * AI 匹配度分析结果内容；仅渲染状态，不含标题和按钮（按钮在
 * [TasteMatchBlock] 标题行）。失败或未配置引导回退本地模型，页面不无响应。
 */
@Composable
private fun AiMatchContent(aiMatch: AiMatchUi, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (aiMatch) {
            is AiMatchUi.Idle -> Unit // 未触发：不额外显示。
            is AiMatchUi.Loading -> AiMatchLoadingContent()
            is AiMatchUi.Result -> AiMatchResultContent(aiMatch)
            is AiMatchUi.NotConfigured -> Text(
                text = aiMatch.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is AiMatchUi.Error -> Text(
                text = aiMatch.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private data class PersonalSectionActions(
    val onToggleBacklog: () -> Unit,
    val onUpdateMyRecord: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit,
    val onSyncToBangumi: () -> Unit,
    val onRestoreFromTrash: () -> Unit,
)

private data class PersonalSectionOptions(
    val recordMessage: String?,
    val trashNotice: String?,
    val localOnly: Boolean,
    val canSyncToBangumi: Boolean,
)

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

@Composable
private fun VndbExtensionSection(
    detail: VisualNovelDetail?,
    loading: Boolean,
    error: String?,
    onOpenWork: (String) -> Unit,
    onOpenProducer: (String, String) -> Unit,
) {
    if (detail == null) {
        if (loading || error != null) {
            SectionCard(title = "VNDB 扩展资料") {
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(error ?: "正在后台加载，页面其他内容可正常使用")
            }
        }
        return
    }

    var revealed by remember(detail.id) { mutableStateOf(emptySet<String>()) }
    var openedImage by remember(detail.id) { mutableStateOf<VisualNovelImage?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    VndbMediaPreload(detail)

    VndbScreenshotSection(
        detail = detail,
        revealed = revealed,
        onReveal = { revealed = revealed + it },
        onOpen = { openedImage = it },
    )
    VndbOverviewSections(detail)
    VndbProducerSection(detail, onOpenProducer, context)
    VndbCreditsSections(detail)
    VndbRelationsSection(detail, onOpenWork)

    openedImage?.let { image ->
        VndbScreenshotViewer(
            detail = detail,
            image = image,
            context = context,
            onDismiss = { openedImage = null },
        )
    }
}

@Composable
private fun VndbMediaPreload(detail: VisualNovelDetail) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val coverPreload = detail.cover?.takeUnless { it.sensitive }?.let { image ->
        managedImageRequest(
            url = image.url,
            workId = detail.id,
            kind = CacheAssetKind.COVER,
            adult = detail.adult,
        )
    }
    val screenshotPreload = detail.screenshots.firstOrNull()?.takeUnless { it.sensitive }?.let { image ->
        managedImageRequest(
            url = image.url,
            workId = detail.id,
            kind = CacheAssetKind.SCREENSHOT_ORIGINAL,
            adult = detail.adult,
        )
    }
    LaunchedEffect(detail.id, detail.cover?.url, detail.screenshots.firstOrNull()?.url) {
        delay(VNDB_ORIGINAL_PRELOAD_DELAY_MS)
        if (connectivityManager?.isActiveNetworkMetered != true) {
            coverPreload?.let { context.imageLoader.enqueue(it) }
            screenshotPreload?.let { context.imageLoader.enqueue(it) }
        }
    }
}

@Composable
private fun VndbScreenshotViewer(
    detail: VisualNovelDetail,
    image: VisualNovelImage,
    context: Context,
    onDismiss: () -> Unit,
) {
    val gallery = detail.screenshots.map { screenshot ->
        ViewerMedia(
            originalUrl = screenshot.url,
            previewUrl = screenshot.thumbnailUrl,
            title = "${detail.title} 截图",
            imageModel = managedImageRequest(
                url = screenshot.url,
                workId = detail.id,
                kind = CacheAssetKind.SCREENSHOT_ORIGINAL,
                adult = detail.adult || screenshot.sensitive,
            ),
        )
    }
    CoverViewerDialog(
        media = ViewerMedia(
            originalUrl = image.url,
            previewUrl = image.thumbnailUrl,
            title = "${detail.title} 截图",
            imageModel = gallery.firstOrNull { it.originalUrl == image.url }?.imageModel,
            gallery = gallery,
            initialIndex = detail.screenshots.indexOfFirst { it.url == image.url }.coerceAtLeast(0),
        ),
        onDismiss = onDismiss,
        onMessage = { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
private fun VndbScreenshotSection(
    detail: VisualNovelDetail,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    onOpen: (VisualNovelImage) -> Unit,
) {
    if (detail.screenshots.isEmpty()) return
    SectionCard(title = "截图") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(detail.screenshots, key = { it.url }) { image ->
                ManagedVisualNovelImage(
                    spec = ManagedImageSpec(
                        image = image,
                        workId = detail.id,
                        kind = CacheAssetKind.SCREENSHOT_THUMBNAIL,
                        adult = detail.adult,
                    ),
                    revealed = revealed,
                    onReveal = onReveal,
                    onOpen = onOpen,
                    modifier = Modifier.size(width = 220.dp, height = 132.dp),
                )
            }
        }
        Text(
            "列表只缓存缩略图；点开大图后才缓存原图。敏感图片需单独点按显示。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VndbOverviewSections(detail: VisualNovelDetail) {
    ExpandableVndbSection(
        title = "语言、平台与发行信息",
        body = buildString {
            append("发行：").append(detail.released ?: NO_DATA)
            append("\n语言：").append(detail.languages.ifEmpty { listOf(NO_DATA) }.joinToString(" / "))
            append("\n平台：").append(detail.platforms.ifEmpty { listOf(NO_DATA) }.joinToString(" / "))
        },
        initiallyExpanded = true,
    )
    detail.description?.takeIf(String::isNotBlank)?.let { description ->
        ExpandableVndbSection(title = "VNDB 原文简介", body = description, initiallyExpanded = false)
    }
    detail.translatedDescription?.takeIf(String::isNotBlank)?.let { translated ->
        ExpandableVndbSection(
            title = "中文简介（AI 翻译，可能不准确）",
            body = translated,
            initiallyExpanded = true,
        )
    }
}

@Composable
private fun VndbProducerSection(
    detail: VisualNovelDetail,
    onOpenProducer: (String, String) -> Unit,
    context: android.content.Context,
) {
    if (detail.developers.isEmpty()) return
    SectionCard(title = "开发商 / 发行商") {
        detail.developers.forEach { producer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenProducer(producer.id, producer.name) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(producer.name, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://vndb.org/${producer.id}"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                ) { Text("VNDB") }
            }
        }
    }
}

@Composable
private fun VndbCreditsSections(detail: VisualNovelDetail) {
    if (detail.staff.isNotEmpty()) {
        ExpandableVndbSection(
            title = "Staff（${detail.staff.size}）",
            body = detail.staff.joinToString("\n") { credit ->
                listOfNotNull(credit.name, credit.role, credit.note).joinToString(" · ")
            },
            initiallyExpanded = false,
        )
    }
    if (detail.voiceRoles.isNotEmpty()) {
        ExpandableVndbSection(
            title = "角色与声优（${detail.voiceRoles.size}）",
            body = detail.voiceRoles.joinToString("\n") { "${it.characterName} · ${it.actorName}" },
            initiallyExpanded = false,
        )
    }
}

@Composable
private fun VndbRelationsSection(detail: VisualNovelDetail, onOpenWork: (String) -> Unit) {
    if (detail.relations.isEmpty()) return
    SectionCard(title = "关联作品") {
        detail.relations.forEach { relation ->
            Text(
                text = "${relation.relation} · ${relation.title}",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWork(relation.id) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ExpandableVndbSection(title: String, body: String, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SectionCard(title = title) {
        Text(
            text = if (expanded) "收起" else "展开",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) Text(body.ifBlank { NO_DATA })
    }
}

@Composable
private fun AiMatchLoadingContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(
            text = "AI 分析中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** AI 结果下方保留一行内置算法的数值，方便对照。 */
@Composable
private fun LocalEstimateLine(tasteMatch: TasteMatchUiModel) {
    val local = tasteMatch as? TasteMatchUiModel.Available ?: return
    val predicted = local.prediction?.let { "，预计你会打 ${it.predictedText} 分" }.orEmpty()
    Text(
        text = "内置算法：口味匹配度 ${local.percentText}$predicted",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AiMatchResultContent(result: AiMatchUi.Result) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AI · ${result.confidenceText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = result.matchPercentText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    result.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
    result.riskReasons.forEach {
        Text(
            text = "- $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReasonsBlock(title: String, reasons: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (reasons.isEmpty()) {
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// endregion

// region 详情 Tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTabsSection(tabs: List<DetailTabUiModel>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
private fun CompletionCostSection(completionCost: CompletionCostUiModel, modifier: Modifier = Modifier) {
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
private fun ChipFlowRow(items: List<String>, modifier: Modifier = Modifier) {
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
private fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlatCard(modifier = modifier, title = title, icon = sectionIconFor(title)) { content() }
}

private fun sectionIconFor(title: String): ImageVector =
    SECTION_ICONS.firstOrNull { (keywords, _) -> keywords.any { it in title } }?.second ?: Icons.Outlined.Info

/** 标题关键词 → 分区图标（按顺序匹配第一个）。 */
private val SECTION_ICONS: List<Pair<List<String>, ImageVector>> = listOf(
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

// region 预览

@Preview(showBackground = true)
@Composable
private fun DetailScreenPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "葬送的芙莉莲",
                    infoRows = listOf(
                        DetailInfoRow("原名", "葬送のフリーレン", isMissing = false),
                        DetailInfoRow("别名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "动画", isMissing = false),
                        DetailInfoRow("年份", "2023", isMissing = false),
                        DetailInfoRow("状态", "已完结", isMissing = false),
                        DetailInfoRow("集数 · 卷数 · 游玩时长", "28 集 · 单集约 24 分钟", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", "8.5", "12,345 人评分"),
                    PlatformRatingUiModel("AniList", "92", "67,890 人评分"),
                    PlatformRatingUiModel("MAL·Tenrai", "9.1", "100,000 人评分"),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Available(
                    stability = ConsensusMetric("评分稳定度", "82%", "较稳定", 0.82f),
                    controversy = ConsensusMetric("争议程度", "18%", "争议较小", 0.18f),
                    priority = ConsensusMetric("补番优先级", "90%", "建议优先", 0.90f),
                ),
                personal = PersonalUiModel(
                    statusText = "在看",
                    ratingText = "9 / 10",
                    progressText = "已看 12 集",
                    reviewText = "节奏舒缓，画面很美。",
                    tags = listOf("奇幻", "治愈", "冒险"),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Available(
                        percentText = "78%",
                        qualitativeText = "可能会喜欢",
                        fraction = 0.78f,
                        reason = "命中 2 个你高分作品常见的标签，倾向于符合你的口味",
                    ),
                    recommendReasons = listOf("与口味画像高度匹配", "社区补番优先级较高"),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = listOf("治愈", "放松"),
                    completionCostText = "周末补完 · 预计约 11 小时",
                ),
                tabs = listOf(
                    DetailTabUiModel("简介", NO_DATA),
                    DetailTabUiModel("平台数据", "Bangumi：8.5（12,345 人评分）"),
                    DetailTabUiModel("我的记录", "状态：在看\n进度：已看 12 集"),
                ),
                completionCost = CompletionCostUiModel(
                    available = true,
                    bucketLabel = "周末补完",
                    detailText = "预计约 11 小时",
                ),
            ),
        )
    }
}

@Preview(showBackground = true, name = "Insufficient consensus")
@Composable
private fun DetailScreenInsufficientPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "某冷门作品",
                    infoRows = listOf(
                        DetailInfoRow("原名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "游戏", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", null, null),
                    PlatformRatingUiModel("AniList", null, null),
                    PlatformRatingUiModel("MAL·Tenrai", null, null),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Insufficient,
                personal = PersonalUiModel(
                    statusText = null,
                    ratingText = null,
                    progressText = null,
                    reviewText = null,
                    tags = emptyList(),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Unavailable("尚未生成口味画像"),
                    recommendReasons = emptyList(),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = emptyList(),
                    completionCostText = NO_DATA,
                ),
                tabs = emptyList(),
                completionCost = CompletionCostUiModel(
                    available = false,
                    bucketLabel = NO_DATA,
                    detailText = NO_DATA,
                ),
            ),
        )
    }
}

// endregion

/**
 * 评分预测块：预计评分 / 社区评分 / 你的均分三个数字，一句「对你来说算高还是低」，
 * 一句「和社区差在哪」，以及个人口味部分的归因。
 * 与上方「口味匹配度」回答的问题不同——匹配度是「像不像你的口味」，这里是「你会打几分」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RatingPredictionBlock(prediction: RatingPredictionUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Surfaces.raisedColor,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PredictionStat(label = "预计你会打", value = prediction.predictedText, emphasized = true)
                PredictionStat(label = "社区评分", value = prediction.communityText ?: "—")
                PredictionStat(label = "你的均分", value = prediction.userMeanText)
            }
            Text(
                text = prediction.selfSummary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = prediction.communitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prediction.factors.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    prediction.factors.forEach { factor ->
                        com.acgcompass.core.designsystem.TagPill(
                            text = factor.text,
                            tone = if (factor.positive) {
                                com.acgcompass.core.designsystem.TagPillTone.Primary
                            } else {
                                com.acgcompass.core.designsystem.TagPillTone.Neutral
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictionStat(label: String, value: String, emphasized: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            // 数值一律正文色：颜色只表示状态（选中 / 开启 / 出错），不用来强调数字。
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** AI 给出的预计评分、分歧原因与评分角度（旧缓存 / 不支持的模型不返回时不展示）。 */
@Composable
private fun AiPredictionExtras(aiMatch: AiMatchUi.Result) {
    aiMatch.predictedRatingText?.let { predicted ->
        Text(
            text = "AI 预计你会打 $predicted 分" + aiMatch.divergenceReason?.let { "：$it" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
    if (aiMatch.ratingAngles.isNotEmpty()) {
        Text(
            text = "你打分最看重：" + aiMatch.ratingAngles.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

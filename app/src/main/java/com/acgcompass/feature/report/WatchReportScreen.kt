package com.acgcompass.feature.report

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TonalFilterChip
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReport

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WatchReportRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: WatchReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AcgScreenScaffold(
        title = "番剧回顾",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    ) { innerPadding ->
        WatchReportContent(
            state = state,
            onSelectPeriod = viewModel::onSelectPeriod,
            onSelectTemplate = viewModel::onSelectTemplate,
            onSelectSize = viewModel::onSelectSize,
            onToggleHideRatings = viewModel::onToggleHideRatings,
            onToggleHidden = viewModel::onToggleHidden,
            onOpenWork = onOpenWork,
            contentPadding = ScreenContentPadding.lazyList(innerPadding),
        )
    }
}

/** 回顾页：时间范围、统计、看完的作品、分享图版式和保存 / 分享。 */
@Suppress("LongParameterList")
@Composable
fun WatchReportContent(
    state: WatchReportUiState,
    onSelectPeriod: (com.acgcompass.domain.report.ReportPeriod) -> Unit,
    onSelectTemplate: (ReportTemplate) -> Unit,
    onSelectSize: (ReportSize) -> Unit,
    onToggleHideRatings: () -> Unit,
    onToggleHidden: (String) -> Unit,
    onOpenWork: (String) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "periods") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.periods.forEach { period ->
                    TonalFilterChip(period.label, period == state.selected, { onSelectPeriod(period) })
                }
            }
        }
        val report = state.report
        when {
            state.loading -> item(key = "loading") {
                Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            report == null || report.finished.isEmpty() -> item(key = "empty") {
                Note("这段时间还没有看完的作品。在 Bangumi 上把看完的作品标成「看过」并同步后，这里会自动统计。")
            }
            else -> {
                item(key = "stats") { StatsCard(report, state.style.hideRatings) }
                item(key = "works") { FinishedGrid(report.finished, state.style, onOpenWork, onToggleHidden) }
                item(key = "share") { ShareCard(state, onSelectTemplate, onSelectSize, onToggleHideRatings) }
                item(key = "note") {
                    Note("看完时间优先按时光机记录的状态变化，其次按 Bangumi 收藏的更新时间推断，可能有偏差。")
                }
            }
        }
    }
}

@Composable
private fun StatsCard(report: WatchReport, hideRatings: Boolean) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("我的 ${report.period.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigNumber("${report.finished.size}", "部看完")
                BigNumber("${report.episodes}", "集")
                BigNumber("${report.hours}", "小时")
            }
            reportLines(report, hideRatings).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 统计卡片里的文字结论。 */
internal fun reportLines(report: WatchReport, hideRatings: Boolean): List<String> = listOfNotNull(
    report.averageRating?.takeIf { !hideRatings }?.let { "平均打分 %.1f".format(it) },
    report.favorite?.let { if (hideRatings) "最喜欢《${it.title}》" else "最喜欢《${it.title}》（${it.rating} 分）" },
    report.topGenres.takeIf { it.isNotEmpty() }?.let { "最常看的题材：" + it.joinToString("、") },
    "当${if (report.period.isYear) "年" else "季"}放送的作品占 ${(report.newShare * PERCENT).toInt()}%",
    report.aboveCrowd?.takeIf {
        !hideRatings
    }?.let { "比大众更偏爱《${it.title}》：你 ${it.rating} 分，Bangumi %.1f".format(it.community) },
    report.belowCrowd?.takeIf {
        !hideRatings
    }?.let { "比大众更挑剔《${it.title}》：你 ${it.rating} 分，Bangumi %.1f".format(it.community) },
    report.dropped.takeIf { it > 0 }?.let { "弃了 $it 部" },
)

@Composable
private fun BigNumber(value: String, unit: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 看完的作品：4 列封面，评分高的在前；点开详情，长按切换放不放进分享图。 */
@Composable
private fun FinishedGrid(
    works: List<ReportWork>,
    style: ReportStyle,
    onOpenWork: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("看完的 ${works.size} 部", style = MaterialTheme.typography.titleMedium)
            works.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { work ->
                        FinishedCell(
                            work = work,
                            hideRatings = style.hideRatings,
                            hidden = work.workId in style.hidden,
                            onClick = { onOpenWork(work.workId) },
                            onLongClick = { onToggleHidden(work.workId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Suppress("LongParameterList") // 一格的数据、两个状态和两种点击
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FinishedCell(
    work: ReportWork,
    hideRatings: Boolean,
    hidden: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(PrismCorner.Thumb)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .clip(PrismCorner.Thumb)
                .background(Surfaces.raisedColor),
            contentAlignment = Alignment.Center,
        ) {
            if (work.coverUrl != null) {
                AsyncImage(
                    model = work.coverUrl,
                    contentDescription = work.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 不放进图里：封面压暗，中间一个「隐藏」图标，不在封面上写字。
            if (hidden) {
                Box(Modifier.fillMaxSize().background(HIDDEN_SCRIM))
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = "不放进分享图",
                    tint = Color.White,
                    modifier = Modifier
                        .size(HIDDEN_BADGE)
                        .clip(CircleShape)
                        .background(HIDDEN_BADGE_FILL)
                        .padding(7.dp),
                )
            }
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            work.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (hidden) muted else Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        when {
            hidden -> Text("不放进图里", style = MaterialTheme.typography.labelSmall, color = muted)
            !hideRatings && work.rating != null -> Text(
                "${work.rating} 分",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private const val GRID_COLUMNS = 4
private const val COVER_ASPECT = 0.75f
private const val PERCENT = 100

@Suppress("MagicNumber") // 半透明黑：压暗封面
private val HIDDEN_SCRIM = Color(0x99000000)

@Suppress("MagicNumber") // 半透明黑：图标底
private val HIDDEN_BADGE_FILL = Color(0x66000000)
private val HIDDEN_BADGE = 36.dp

package com.acgcompass.feature.discover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.UiState

// region 榜单 / 评分差异 / 高级筛选界面

/** 各来源榜单分区：顶部为 Bangumi 总榜、年度和季度排行，下方为本地多源缓存榜单。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RankingSection(
    boards: List<RankingBoard>,
    poolLoading: Boolean,
    poolError: String?,
    rankingScope: RankingScope,
    scopedRanking: UiState<List<RankedWork>>,
    rankingLoadingMore: Boolean,
    rankingCanLoadMore: Boolean,
    onSelectRankingScope: (RankingScope) -> Unit,
    onRetryRanking: () -> Unit,
    onLoadMoreRanking: () -> Unit,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    // 触底加载更多——共享 LazyListState，最后可见项接近末尾且仍可加载时触发下一页。
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, rankingCanLoadMore) {
        if (shouldLoadMore && rankingCanLoadMore) onLoadMoreRanking()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bangumi 真实排行子分类切换。
        item(key = "scope_tabs") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Bangumi 排行榜",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RankingScope.entries.forEach { scope ->
                        FlatFilterChip(
                            selected = scope == rankingScope,
                            onClick = { onSelectRankingScope(scope) },
                            label = { Text(scope.label) },
                        )
                    }
                }
            }
        }
        // 当前范围真实排行（七态）。
        when (val s = scopedRanking) {
            is UiState.Loading -> item(key = "rank_loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在加载排行榜…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UiState.Success -> {
                itemsIndexed(s.data, key = { _, rw -> "rank_${rw.workId}" }) { index, rw ->
                    RankedWorkCard(rank = index + 1, card = rw.card, onClick = { onOpenWork(rw.workId) })
                }
                // 分页页脚——加载中显示指示；到底显示提示。
                if (rankingLoadingMore) {
                    item(key = "rank_loading_more") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("  正在加载更多…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (!rankingCanLoadMore && s.data.isNotEmpty()) {
                    item(key = "rank_end") {
                        Text(
                            text = "已到榜单底部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            is UiState.Empty -> item(key = "rank_empty") {
                EmptyDataPlaceholder("该范围暂无排行数据，换个范围或稍后重试")
            }
            else -> item(key = "rank_error") {
                PrismCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("排行榜加载失败", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = onRetryRanking) { Text("重试") }
                    }
                }
            }
        }
        // 榜单仅保留 Bangumi 真实排行（其他源榜单后续再加）。
        item(key = "rank_note") {
            Text(
                text = "按 Bangumi 的排名排序，尚未获得排名的条目不显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 评分差异页「补充其它平台评分」的进行状态。 */
data class ScoreDiffBackfillUi(val running: Boolean = false)

/** 评分差异榜分区：中性说明 + 差距榜；无数据时显示「暂无数据」。 */
@Suppress("LongParameterList", "LongMethod") // 一个分区的完整列表
@Composable
internal fun ScoreDiffSection(
    items: List<ScoreDiffItem>,
    poolLoading: Boolean,
    poolError: String?,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
    backfill: ScoreDiffBackfillUi = ScoreDiffBackfillUi(),
) {
    val backfilling = backfill.running
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留白保证末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pool_status") {
            PublicPoolStatusRow(poolLoading = poolLoading, poolError = poolError, onLoadPool = onLoadPool)
        }
        item {
            PrismCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Surfaces.raisedColor,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = SCORE_DIFF_NEUTRAL_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (backfilling) {
            item(key = "backfilling") {
                Text(
                    text = "正在为热门作品补充其它平台的评分，补到的作品会陆续出现…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (items.isEmpty()) {
            item { EmptyDataPlaceholder(if (backfilling) "暂时还没有评分差距较大的作品" else "暂无评分差距较大的作品") }
        } else {
            items(items, key = { it.workId }) { diff ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkCard(model = diff.card, onClick = { onOpenWork(diff.workId) })
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = diff.spreadLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DivergenceBadge(level = diff.divergenceLevel)
                    }
                    ScoreSpreadBar(sources = diff.sourceScores)
                    Text(
                        text = diff.perSourceLabels.joinToString("  /  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 分歧程度徽标：显著=错误色，明显=中性底正文色，轻微=中性底次要色。 */
@Composable
internal fun DivergenceBadge(level: DivergenceLevel) {
    val (bg, fg) = when (level) {
        DivergenceLevel.STRONG ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        // 颜色只用来表示状态：显著分歧用错误色，其余用中性底。
        DivergenceLevel.NOTABLE ->
            Surfaces.raisedColor to MaterialTheme.colorScheme.onSurface
        DivergenceLevel.SLIGHT ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = level.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 评分分布条（丰富化）：在 0–10 轨道上以高亮带表示各源最低→最高区间，并为每个来源画圆点标记，
 * 使「评分差距」客观可视。仅呈现归一化数值，不下结论。少于 2 源时不绘制。
 */
@Composable
internal fun ScoreSpreadBar(sources: List<SourceScore>, modifier: Modifier = Modifier) {
    if (sources.size < 2) return
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val rangeColor = MaterialTheme.colorScheme.primary
    val dotFill = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        val cy = size.height / 2f
        val trackH = 6.dp.toPx()
        val radius = 5.dp.toPx()
        // 两端各留出圆点半径，避免端点圆点被裁切。
        val left = radius
        val usable = (size.width - radius * 2f).coerceAtLeast(1f)
        fun xOf(score: Float): Float = left + (score / 10f).coerceIn(0f, 1f) * usable
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f),
        )
        val xs = sources.map { xOf(it.score) }
        val minX = xs.minOrNull() ?: left
        val maxX = xs.maxOrNull() ?: left
        drawRoundRect(
            color = rangeColor,
            topLeft = Offset(minX, cy - trackH / 2f),
            size = Size((maxX - minX).coerceAtLeast(trackH), trackH),
            cornerRadius = CornerRadius(trackH / 2f),
        )
        xs.forEach { x ->
            drawCircle(color = dotFill, radius = radius, center = Offset(x, cy))
            drawCircle(
                color = rangeColor,
                radius = radius,
                center = Offset(x, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun SourceHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** 榜单条目：名次放在卡片封面的角标里，卡片之间不插单独的名次行。 */
@Composable
internal fun RankedWorkCard(rank: Int, card: WorkCardUiModel, onClick: () -> Unit) {
    WorkCard(model = card, rank = rank, onClick = onClick)
}

/** 公共发现池加载状态行（加载中 / 错误+重试 / 提示）。 */
@Composable
internal fun PublicPoolStatusRow(poolLoading: Boolean, poolError: String?, onLoadPool: () -> Unit) {
    when {
        poolLoading -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text("正在加载本季 / 公共榜单…", style = MaterialTheme.typography.bodyMedium)
        }
        poolError != null -> PrismCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加载失败：$poolError", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onLoadPool) { Text("重试") }
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "公共榜单（Jikan / MAL）无需登录即可浏览",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onLoadPool,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                ),
            ) {
                Text("刷新", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
internal fun EmptyDataPlaceholder(message: String) {
    PrismCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Surfaces.raisedColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

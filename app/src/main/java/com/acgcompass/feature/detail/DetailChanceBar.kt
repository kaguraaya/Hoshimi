package com.acgcompass.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.PrismCorner

/**
 * 大字下面的三行：同一个分布的平均分和区间、在你评分里的位置、最可能的分数和打 9 分以上的可能（过半时加粗）。
 */
@Composable
internal fun PredictionSummaryLines(prediction: RatingPredictionUi) {
    listOfNotNull(prediction.meanText, prediction.intervalText).takeIf { it.isNotEmpty() }?.let {
        Text(
            text = it.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = prediction.selfSummary,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    listOfNotNull(prediction.likelyText, prediction.highChanceText).takeIf { it.isNotEmpty() }?.let {
        val strong = prediction.highChanceStrong
        Text(
            text = it.joinToString(" · "),
            style = if (strong) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「可能打几分」：一根分段横条，从左到右是 10 / 9 / 8 / 7 / ≤6 分，每段宽度和可能成正比；同一色相，分数越高颜色越深。
 * 段够宽时段内写「9 分 43%」（不够就只写「43%」），太窄的段不写字，名称和百分比留给下面的图例；读屏读出全部五档。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChanceDistribution(buckets: List<ChanceBucketUi>) {
    // 领域给的顺序就是 10 → ≤6，横条从高分画到低分；颜色深浅按分数（step 0 = ≤6 最浅）。
    val parts = buckets.mapIndexed { i, bucket -> ChancePart(bucket, step = buckets.lastIndex - i) }
    val total = parts.sumOf { it.bucket.share }.takeIf { it > 0.0 } ?: return
    val description = parts.joinToString("，", prefix = "可能打几分：") { "${it.bucket.label} ${percentText(it.bucket)}" }
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    // 深色主题单独一组深浅：最浅一档在深色卡片上也要看得出来。
    val alphas = if (surface.luminance() < DARK_SURFACE) DARK_FILL_ALPHAS else FILL_ALPHAS
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "可能打几分",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val visible = parts.filter { it.bucket.share > 0.0 }
            val available = maxWidth - BAR_GAP * (visible.size - 1)
            val widths = visible.map { available * (it.bucket.share / total).toFloat() }
            val labels = visible.mapIndexed { i, part ->
                blockLabelCandidates(part.bucket).firstOrNull { text ->
                    val textWidth = with(density) { measurer.measure(text, style).size.width.toDp() }
                    textWidth + LABEL_PADDING * 2 <= widths[i]
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChanceBar(
                    blocks = visible.mapIndexed { i, part ->
                        ChanceBlock(
                            primary.copy(alpha = alphas[part.step]).compositeOver(surface),
                            widths[i],
                            labels[i],
                        )
                    },
                    description = description,
                    style = style,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    parts.forEach { part ->
                        val printed = visible.indexOf(part).let { it >= 0 && labels[it] != null }
                        LegendItem(
                            fill = primary.copy(alpha = alphas[part.step]).compositeOver(surface),
                            text = shortLabel(part.bucket) + if (printed) " 分" else " 分 ${percentText(part.bucket)}",
                        )
                    }
                }
            }
        }
    }
}

/** 横条本身：每段一块，块间留 2dp 间隙；段内文字按底色深浅选白字或近黑色字。 */
@Composable
private fun ChanceBar(blocks: List<ChanceBlock>, description: String, style: TextStyle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
    ) {
        blocks.forEach { block ->
            Box(
                modifier = Modifier
                    .width(block.width)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(BLOCK_CORNER))
                    .background(block.fill),
                contentAlignment = Alignment.CenterStart,
            ) {
                block.label?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = LABEL_PADDING),
                        style = style,
                        color = if (block.fill.luminance() > INK_SWITCH) NEAR_BLACK else Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 横条里画出来的一块：底色、宽度、段内文字（太窄时为 null）。 */
private data class ChanceBlock(val fill: Color, val width: Dp, val label: String?)

@Composable
private fun LegendItem(fill: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(LEGEND_SWATCH).clip(RoundedCornerShape(2.dp)).background(fill))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 「这几个数怎么看」：默认收起，点开后用这部作品自己的数说明大字（中位数）、平均、最可能的分数和 9 分以上各是什么。
 */
@Composable
internal fun PredictionReadingGuide(lines: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (expanded) "收起说明" else "这几个数怎么看",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(PrismCorner.Badge).clickable { expanded = !expanded },
        )
        if (expanded) {
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 横条里的一段：[step] 是从低分数起的第几档（0 = ≤6），决定颜色深浅。 */
private data class ChancePart(val bucket: ChanceBucketUi, val step: Int)

private fun percentText(bucket: ChanceBucketUi): String = if (bucket.percent < 1) "<1%" else "${bucket.percent}%"

/** 「9 分」→「9」，「6 分及以下」→「≤6」。 */
private fun shortLabel(bucket: ChanceBucketUi): String =
    if (bucket.label.endsWith("及以下")) "≤" + bucket.label.substringBefore(" ") else bucket.label.substringBefore(" ")

private fun blockLabelCandidates(bucket: ChanceBucketUi): List<String> =
    listOf("${shortLabel(bucket)} 分 ${percentText(bucket)}", percentText(bucket))

private val BAR_HEIGHT = 28.dp
private val BAR_GAP = 2.dp
private val BLOCK_CORNER = 4.dp
private val LABEL_PADDING = 6.dp
private val LEGEND_SWATCH = 10.dp

@Suppress("MagicNumber")
private val NEAR_BLACK = Color(0xFF1C1B1F)

/** 五档的颜色深浅（≤6 → 10）：同一色相由浅到深；深色主题另用一组，最浅一档在深色卡片上也看得出来。 */
@Suppress("MagicNumber")
private val FILL_ALPHAS = floatArrayOf(0.16f, 0.30f, 0.46f, 0.66f, 0.88f)

@Suppress("MagicNumber")
private val DARK_FILL_ALPHAS = floatArrayOf(0.32f, 0.44f, 0.58f, 0.74f, 0.92f)

/** 表面亮度低于这个值算深色主题。 */
private const val DARK_SURFACE = 0.5f

/** 底色亮度高于这个值时段内用近黑色字，否则用白字。 */
private const val INK_SWITCH = 0.4f

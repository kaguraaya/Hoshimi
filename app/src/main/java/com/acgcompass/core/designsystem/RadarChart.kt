@file:Suppress("MatchingDeclarationName") // 文件以组件 RadarChart 命名，数据类 RadarSeries 是它的参数

package com.acgcompass.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 雷达图的一组数据：[values] 与轴一一对应，取值 0–1。 */
data class RadarSeries(val label: String, val values: List<Float>, val color: Color)

/**
 * 雷达图（多维对比）：同心多边形网格 + 轴线 + 每组数据一块半透明多边形，轴名画在外圈。
 * 至少 3 根轴；多组数据时下面带图例。
 */
@Composable
fun RadarChart(axes: List<String>, series: List<RadarSeries>, modifier: Modifier = Modifier) {
    require(axes.size >= MIN_AXES) { "radar chart needs at least $MIN_AXES axes" }
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val description = series.joinToString("；") { s ->
        s.label + "：" + axes.zip(s.values).joinToString("，") { (axis, v) -> "$axis ${(v * PERCENT).toInt()}" }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = description },
        ) {
            val radius = min(size.width, size.height) / 2 * RADIUS_FRACTION
            val center = Offset(size.width / 2, size.height / 2)
            drawGrid(axes.size, center, radius, gridColor)
            series.forEachIndexed { index, s -> drawSeries(s, center, radius, dashed = index > 0) }
            drawLabels(axes, center, radius, measurer, labelStyle)
        }
        if (series.size > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                series.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                        Text(s.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun point(center: Offset, radius: Float, index: Int, count: Int, value: Float): Offset {
    val angle = -PI / 2 + 2 * PI * index / count
    return Offset(
        center.x + (radius * value * cos(angle)).toFloat(),
        center.y + (radius * value * sin(angle)).toFloat(),
    )
}

private fun polygon(center: Offset, radius: Float, values: List<Float>): Path = Path().apply {
    values.forEachIndexed { i, v ->
        val p = point(center, radius, i, values.size, v.coerceIn(0f, 1f))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.drawGrid(count: Int, center: Offset, radius: Float, color: Color) {
    val stroke = Stroke(width = 1.dp.toPx())
    for (ring in 1..RINGS) {
        drawPath(polygon(center, radius, List(count) { ring / RINGS.toFloat() }), color, style = stroke)
    }
    repeat(count) { i -> drawLine(color, center, point(center, radius, i, count, 1f), strokeWidth = stroke.width) }
}

/** 第二组起描虚线、空心点：两组完全重合时也能看出两条线都在。 */
private fun DrawScope.drawSeries(series: RadarSeries, center: Offset, radius: Float, dashed: Boolean) {
    val path = polygon(center, radius, series.values)
    drawPath(path, series.color.copy(alpha = FILL_ALPHA))
    val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), GAP.toPx())) else null
    drawPath(path, series.color, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
    series.values.forEachIndexed { i, v ->
        val p = point(center, radius, i, series.values.size, v.coerceIn(0f, 1f))
        if (dashed) {
            drawCircle(series.color, 4.dp.toPx(), p, style = Stroke(width = 1.5.dp.toPx()))
        } else {
            drawCircle(series.color, 3.dp.toPx(), p)
        }
    }
}

private fun DrawScope.drawLabels(
    axes: List<String>,
    center: Offset,
    radius: Float,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    axes.forEachIndexed { i, axis ->
        val layout = measurer.measure(axis, style.copy(textAlign = TextAlign.Center))
        val anchor = point(center, radius + LABEL_GAP.toPx(), i, axes.size, 1f)
        // 轴名以锚点为中心，靠左右两侧的再往外推半个宽度，避免压到图形。
        val dx = when {
            anchor.x < center.x - 1f -> -layout.size.width.toFloat()
            anchor.x > center.x + 1f -> 0f
            else -> -layout.size.width / 2f
        }
        val dy = when {
            anchor.y < center.y - radius * EDGE -> -layout.size.height.toFloat()
            anchor.y > center.y + radius * EDGE -> 0f
            else -> -layout.size.height / 2f
        }
        // 不超出画布：靠边的轴名贴着边缘放。
        val x = (anchor.x + dx).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        val y = (anchor.y + dy).coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(x, y))
    }
}

private const val MIN_AXES = 3
private const val RINGS = 4
private const val RADIUS_FRACTION = 0.62f
private const val FILL_ALPHA = 0.18f
private const val PERCENT = 100
private const val EDGE = 0.9f
private val LABEL_GAP = 8.dp
private val DASH = 6.dp
private val GAP = 4.dp

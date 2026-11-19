package com.acgcompass.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * 下拉刷新容器：在列表顶部继续下拉时，从顶栏下方滑出一枚浮起的圆形指示器，里面是应用图标的八芒星
 * （见 [HoshimiStar]）。拉过阈值轻震一下、星星弹一下，松手开始刷新后星星一格一格地转。外观按设计规范里「浮在内容上的东西」：
 * 浅色取卡片级、深色取色块级，带投影；内容本身不跟着下移。
 *
 * @param indicatorTopPadding 内容铺在顶栏下面的页面传顶栏高度，指示器从顶栏下沿滑出。
 * @param enabled 关闭时不响应下拉（例如列表里正在拖动别的东西）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun AcgPullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTopPadding: Dp = 0.dp,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = state,
            enabled = enabled,
            threshold = PULL_THRESHOLD,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        // 只在顶栏下沿以下显示：从边缘滑出时不会叠在顶栏上。
        Box(Modifier.matchParentSize().padding(top = indicatorTopPadding).clipToBounds()) {
            PullRefreshIndicator(
                state = state,
                refreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val armed by remember(state) { derivedStateOf { state.distanceFraction >= 1f } }
    // 拉过阈值时轻震一下，星星弹一下。
    val pop = remember { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed && !refreshing) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            pop.animateTo(POP_SCALE, tween(POP_IN_MILLIS))
            pop.animateTo(1f, spring(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val density = LocalDensity.current
    val travelPx = with(density) { (PULL_THRESHOLD + INDICATOR_SIZE).toPx() }
    val sizePx = with(density) { INDICATOR_SIZE.toPx() }
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE
    Box(
        modifier = modifier
            .size(INDICATOR_SIZE)
            .graphicsLayer {
                val fraction = state.distanceFraction
                // 从顶部边缘外滑入，到阈值时停在阈值距离处；超过阈值后由状态本身提供阻尼。
                translationY = fraction * travelPx * TRAVEL_RATIO - sizePx
                alpha = min(1f, fraction * FADE_IN_SPEED)
                val scale = SCALE_FROM + (1f - SCALE_FROM) * min(1f, fraction)
                scaleX = scale
                scaleY = scale
            }
            .semantics { contentDescription = if (refreshing) "正在刷新" else "下拉刷新" },
    ) {
        Surface(
            shape = CircleShape,
            color = Surfaces.colorOf(if (dark) SurfaceLevel.Block else SurfaceLevel.Card),
            shadowElevation = 6.dp,
            modifier = Modifier.size(INDICATOR_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center) {
                HoshimiStar(progress = { state.distanceFraction }, refreshing = refreshing, pop = { pop.value })
            }
        }
    }
}

/**
 * 应用图标的八芒星：一颗细长的大四芒星，下面垫一颗转了 45° 的小四芒星。
 * 下拉时大星跟着手指从小长大、转过四分之一圈，小星在后半段淡入并反向转；
 * 刷新时大星每次转四分之一圈、停顿一下再转（像指南针定位），小星缓慢反向旋转。
 */
@Composable
private fun HoshimiStar(progress: () -> Float, refreshing: Boolean, pop: () -> Float) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = primary.copy(alpha = ACCENT_ALPHA)
    val transition = rememberInfiniteTransition(label = "pull-refresh")
    val ticks by transition.animateFloat(
        initialValue = 0f,
        targetValue = TICKS_PER_LOOP,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "ticks",
    )
    val bigPaint = remember { starPaint() }
    val smallPaint = remember { starPaint() }
    Canvas(Modifier.size(STAR_SIZE)) {
        val pose = if (refreshing) spinningPose(ticks) else pullingPose(progress().coerceIn(0f, 1f))
        val radius = size.minDimension / 2f
        val corner = STAR_CORNER.toPx()
        bigPaint.color = primary
        bigPaint.pathEffect = PathEffect.cornerPathEffect(corner)
        smallPaint.color = accent.copy(alpha = accent.alpha * pose.smallAlpha)
        smallPaint.pathEffect = PathEffect.cornerPathEffect(corner)
        scale(pop()) {
            rotate(pose.smallAngle) {
                drawIntoCanvas {
                    it.drawPath(
                        starPath(center, radius * SMALL_OUTER, radius * SMALL_INNER, QUARTER_TURN / 2f),
                        smallPaint,
                    )
                }
            }
            rotate(pose.bigAngle) {
                scale(pose.bigScale) {
                    drawIntoCanvas { it.drawPath(starPath(center, radius, radius * BIG_INNER, 0f), bigPaint) }
                }
            }
        }
    }
}

private data class StarPose(val bigAngle: Float, val bigScale: Float, val smallAngle: Float, val smallAlpha: Float)

private fun pullingPose(p: Float) = StarPose(
    bigAngle = p * QUARTER_TURN,
    bigScale = BIG_SCALE_FROM + (1f - BIG_SCALE_FROM) * p,
    smallAngle = -p * QUARTER_TURN / 2f,
    smallAlpha = ((p - SMALL_FADE_START) / (1f - SMALL_FADE_START)).coerceIn(0f, 1f),
)

private fun spinningPose(ticks: Float): StarPose {
    val step = floor(ticks)
    return StarPose(
        bigAngle = (step + TICK_EASING.transform(ticks - step)) * QUARTER_TURN,
        bigScale = 1f,
        smallAngle = -ticks * QUARTER_TURN / 2f,
        smallAlpha = 1f,
    )
}

private fun starPaint() = Paint().apply {
    isAntiAlias = true
    style = PaintingStyle.Fill
}

/** 四芒星：外顶点在 [startAngle] 起每 90° 一个，内顶点在两者之间。 */
private fun starPath(center: Offset, outer: Float, inner: Float, startAngle: Float): Path = Path().apply {
    repeat(STAR_POINTS * 2) { index ->
        val radius = if (index % 2 == 0) outer else inner
        val angle = Math.toRadians((startAngle - QUARTER_TURN + index * QUARTER_TURN / 2f).toDouble())
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val INDICATOR_SIZE: Dp = 40.dp
private val STAR_SIZE: Dp = 24.dp
private val STAR_CORNER: Dp = 0.8.dp
private val PULL_THRESHOLD: Dp = 72.dp
private const val TRAVEL_RATIO = 0.62f
private const val FADE_IN_SPEED = 2.5f
private const val SCALE_FROM = 0.7f
private const val DARK_LUMINANCE = 0.5f
private const val STAR_POINTS = 4
private const val QUARTER_TURN = 90f

/** 图标里的比例：大星内顶点 6/30*√2，小星外顶点 14.3*√2/30、内顶点 7.9/30。 */
private const val BIG_INNER = 0.283f
private const val SMALL_OUTER = 0.674f
private const val SMALL_INNER = 0.263f
private const val ACCENT_ALPHA = 0.45f
private const val BIG_SCALE_FROM = 0.35f
private const val SMALL_FADE_START = 0.3f
private const val POP_SCALE = 1.14f
private const val POP_IN_MILLIS = 90
private const val POP_DAMPING = 0.45f
private const val TICKS_PER_LOOP = 4f
private const val LOOP_MILLIS = 3_200

/** 每格转动先慢后快再慢，停顿感来自两端的缓动。 */
@Suppress("MagicNumber")
private val TICK_EASING = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

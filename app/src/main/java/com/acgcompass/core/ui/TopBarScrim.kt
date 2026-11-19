package com.acgcompass.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.SurfaceLevel
import com.acgcompass.core.designsystem.Surfaces
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/**
 * 顶栏遮罩（照 FlClash 的 FloatingHeader）：内容一直铺到屏幕顶端，从顶栏下面滚过；顶栏本身不画底色，
 * 而是一层页面底色的渐变——状态栏区域保持 90% 不透明，往下按 easeInOut 分段淡出，到顶栏底边再往下
 * [OVERHANG] 处完全透明。没有模糊、没有分界线，标题下面的内容隐约可见，滚动时只是一层颜色，不额外取样重绘。
 *
 * 用法：页面内容不加顶部内边距，列表用 `innerPadding.calculateTopPadding()` 让出顶栏；顶栏加 [topBarScrim]，
 * 颜色用 [transparentTopBarColors]。带分区药丸的顶栏把整个 Column 一起加遮罩。
 */
@Composable
fun Modifier.topBarScrim(): Modifier {
    val page = Surfaces.colorOf(SurfaceLevel.Page)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return drawBehind {
        val overhang = OVERHANG.toPx()
        val total = size.height + overhang
        val start = (statusTop.toPx() / total).coerceIn(0f, 1f)
        val stops = buildList {
            add(0f to page.copy(alpha = SCRIM_ALPHA))
            for (i in 0..FADE_STEPS) {
                val t = i.toFloat() / FADE_STEPS
                add(start + (1f - start) * t to page.copy(alpha = SCRIM_ALPHA * (1f - EASE_IN_OUT.transform(t))))
            }
        }
        drawRect(
            brush = Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = 0f, endY = total),
            size = Size(size.width, total),
        )
    }
}

/** 顶栏用的透明配色：底色交给 [topBarScrim]，滚动时也不切换成别的颜色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun transparentTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)

/** 悬浮底栏毛玻璃的取样来源：底栏下面滚过的页面内容。 */
fun Modifier.dockBlurSource(state: HazeState): Modifier = haze(state)

/** Scaffold 内容的内边距去掉顶部：内容铺到顶栏下面，顶部空间改由列表留白提供。 */
@Composable
@ReadOnlyComposable
fun PaddingValues.withoutTop(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}

/** 遮罩从顶栏底边再往下延伸的距离，让第一项内容渐渐露出来，而不是被一条边切开。 */
private val OVERHANG: Dp = 16.dp
private const val SCRIM_ALPHA = 0.9f
private const val FADE_STEPS = 8

/** Flutter `Curves.easeInOut`（即 CSS ease-in-out）的控制点。 */
@Suppress("MagicNumber")
private val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

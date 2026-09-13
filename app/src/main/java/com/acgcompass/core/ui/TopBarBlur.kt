package com.acgcompass.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.SurfaceLevel
import com.acgcompass.core.designsystem.Surfaces
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * 模糊顶栏（照 FlClash：内容从顶栏下面滚过，顶栏是一层带页面底色的毛玻璃，不画分界线）。
 *
 * 用法：页面 `remember { HazeState() }`，顶栏传 `hazeState`，铺在顶栏下面的内容容器加 [topBarBlurSource]。
 * Android 12 以下没有实时模糊，退回更不透明的底色。
 */

/** 顶栏下面的内容：标记为模糊的取样来源。 */
fun Modifier.topBarBlurSource(state: HazeState): Modifier = haze(state)

/** 顶栏本身：取样下方内容做模糊，叠一层页面底色。 */
@Composable
fun Modifier.topBarBlur(state: HazeState): Modifier {
    val page = Surfaces.colorOf(SurfaceLevel.Page)
    return hazeChild(
        state = state,
        style = HazeStyle(
            backgroundColor = page,
            tint = HazeTint(page.copy(alpha = TINT_ALPHA)),
            blurRadius = BLUR_RADIUS,
            noiseFactor = 0f,
            fallbackTint = HazeTint(page.copy(alpha = FALLBACK_ALPHA)),
        ),
    ) {
        // 底部一段逐渐淡出（照 FlClash）：顶栏和下面内容之间没有一条硬边，而是模糊慢慢过渡到清晰。
        mask = Brush.verticalGradient(
            0f to Color.Black,
            FADE_START to Color.Black,
            1f to Color.Transparent,
        )
    }
}

/** 模糊顶栏用的透明配色：底色交给 [topBarBlur] 画，滚动时也不切换成别的颜色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun blurredTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)

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

private val BLUR_RADIUS: Dp = 24.dp
private const val TINT_ALPHA = 0.72f
private const val FALLBACK_ALPHA = 0.94f

/** 从顶栏高度的这个位置开始淡出，到底边完全透明。 */
private const val FADE_START = 0.72f

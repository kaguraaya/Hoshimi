@file:Suppress("TooManyFunctions") // 底栏的布局、手势、镜片几何与绘制放在一起，便于对照 FlClash 的同名实现

package com.acgcompass.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.SurfaceLevel
import com.acgcompass.core.designsystem.SurfaceScope
import com.acgcompass.core.designsystem.Surfaces
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 悬浮的药丸形底栏（照 FlClash 的 `FloatingNavigationBar`，`prism_ui/lib/navigation_dock.dart`）。
 *
 * - 浮在内容之上（不占布局），浅色取卡片级、深色取色块级，带投影；
 * - 选中项是比底栏深一级的中性色块（「镜片」），图标和文字换成主色；
 * - **按住**：镜片跳到手指下方并放大；**左右拖动**：镜片跟着手指走，经过哪一项哪一项就变主色并轻震一下，
 *   拖得快时镜片沿运动方向被拉长（果冻感），拖出两端有阻尼；**松手**：选中最近的一项，镜片带一点回弹落位并缩回。
 * - 从别处切换页面（返回键等）时，镜片平滑移过去。
 *
 * 弹簧参数由 FlClash 源码换算（时长 d、回弹 b → 刚度 (2π/d)²、阻尼比 1 − b）。
 */
@Composable
fun AcgBottomNavigation(
    currentDestination: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE
    val dockLevel = if (dark) SurfaceLevel.Block else SurfaceLevel.Card
    val entries = TopLevelDestination.ENTRIES
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = DOCK_MARGIN, vertical = 8.dp),
    ) {
        // 底栏背景单独一层（只画底色和投影，不放内容）：镜片和图标画在它上面、不受它的圆角裁剪，
        // 按住时镜片才能像 FlClash 那样长出底栏之外。
        Surface(
            modifier = Modifier.fillMaxWidth().height(DOCK_HEIGHT),
            shape = CircleShape,
            color = Surfaces.colorOf(dockLevel),
            shadowElevation = 12.dp,
        ) {}
        SurfaceScope(dockLevel) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(DOCK_HEIGHT).padding(DOCK_INSET)) {
                val slotWidthPx = constraints.maxWidth.toFloat() / entries.size
                val selectedIndex = entries.indexOf(currentDestination).takeIf { it >= 0 }
                DockBody(
                    entries = entries,
                    selectedIndex = selectedIndex,
                    slotWidthPx = slotWidthPx,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun DockBody(
    entries: List<TopLevelDestination>,
    selectedIndex: Int?,
    slotWidthPx: Float,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lens = remember { DockLens() }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // 外部切换（返回键、代码导航）：镜片平滑移过去；首次出现直接就位。
    LaunchedEffect(selectedIndex, slotWidthPx) {
        val target = selectedIndex ?: return@LaunchedEffect
        val x = slotCenter(target, slotWidthPx)
        if (lens.placed) lens.x.animateTo(x, TravelSpring) else lens.place(x)
    }

    Box(
        Modifier
            .fillMaxSize()
            .dockGestures(
                count = entries.size,
                slotWidthPx = slotWidthPx,
                onHover = { index ->
                    if (index != hoverIndex && index >= 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    hoverIndex = index
                },
                onPress = { x -> scope.pressLens(lens, x) },
                onDrag = { x -> scope.launch { lens.x.animateTo(x, FollowSpring) } },
                onRelease = { chosen ->
                    scope.releaseLens(lens, slotCenter(chosen, slotWidthPx))
                    if (chosen != currentSelected) currentOnSelect(entries[chosen])
                },
            ),
    ) {
        val showLens = lens.placed && (selectedIndex != null || hoverIndex >= 0)
        val growthPx = with(LocalDensity.current) { LENS_GROWTH.toPx() } * 2f
        if (showLens) Lens(lens = lens, slotWidthPx = slotWidthPx)
        // 底层：普通颜色的图标（读屏用这一层），挖掉镜片区域——镜片里只显示上层放大的主色图标，不会重影。
        DockRow(
            entries = entries,
            selectedIndex = selectedIndex,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onSelect = onSelect,
            modifier = Modifier
                .selectableGroup()
                .drawWithContent {
                    if (!showLens) {
                        drawContent()
                    } else {
                        clipPath(lensPath(lensRect(lens, slotWidthPx, size.height, growthPx)), ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    }
                },
        )
        // 上层：主色图标，只在镜片的圆角轮廓里显示，并随镜片放大（见 [LensedIcons]）。
        if (showLens) LensedIcons(entries, selectedIndex, lens, slotWidthPx, growthPx, onSelect)
    }
}

private fun lensPath(rect: Rect): Path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(rect.height / 2f))) }

/**
 * 镜片里的主色图标：只在镜片的圆角轮廓里显示，并随镜片放大——镜片盖住图标的哪一部分，哪一部分就变色
 * （照 FlClash 的 _LensPart.inside / _LensClip），拖动时颜色跟着镜片边缘扫过图标。
 */
@Suppress("LongParameterList") // 镜片几何所需的全部输入
@Composable
private fun LensedIcons(
    entries: List<TopLevelDestination>,
    selectedIndex: Int?,
    lens: DockLens,
    slotWidthPx: Float,
    growthPx: Float,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawWithContent {
                clipPath(lensPath(lensRect(lens, slotWidthPx, size.height, growthPx))) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        DockRow(
            entries = entries,
            selectedIndex = selectedIndex,
            tint = MaterialTheme.colorScheme.primary,
            onSelect = onSelect,
            modifier = Modifier.graphicsLayer {
                val m = 1f + LENS_MAGNIFY * lens.lift.value
                scaleX = m
                scaleY = m
                transformOrigin = TransformOrigin((lens.x.value / size.width).coerceIn(0f, 1f), HALF)
            },
        )
    }
}

/** 镜片在底栏内容坐标里的矩形（与 [Lens] 的绘制一致：抬起时变大、拖得快时拉长压扁）。 */
private fun lensRect(lens: DockLens, slotWidthPx: Float, heightPx: Float, growthPx: Float): Rect {
    val lift = lens.lift.value
    val stretch = (abs(lens.x.velocity) / JELLY_VELOCITY).coerceAtMost(JELLY_MAX)
    val width = (slotWidthPx + growthPx * lift) * (1f + stretch)
    val height = (heightPx + growthPx * lift) * (1f - stretch * JELLY_ASPECT)
    return Rect(
        left = lens.x.value - width / 2f,
        top = (heightPx - height) / 2f,
        right = lens.x.value + width / 2f,
        bottom = (heightPx + height) / 2f,
    )
}

@Composable
private fun DockRow(
    entries: List<TopLevelDestination>,
    selectedIndex: Int?,
    tint: Color,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        entries.forEachIndexed { index, tab ->
            DockItem(
                tab = tab,
                selected = index == selectedIndex,
                tint = tint,
                onClick = { if (index != selectedIndex) onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 底栏手势：按下 → [onPress]；拖动 → [onDrag]（已加两端阻尼）与 [onHover]（经过的格子）；
 * 松手 → [onHover] 传 -1 并 [onRelease] 最近的格子。
 */
@Suppress("LongParameterList") // 一组手势回调，拆开反而难读
private fun Modifier.dockGestures(
    count: Int,
    slotWidthPx: Float,
    onHover: (Int) -> Unit,
    onPress: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (Int) -> Unit,
): Modifier = pointerInput(count, slotWidthPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val maxX = slotWidthPx * count
        onHover(indexAt(down.position.x, slotWidthPx, count))
        onPress(down.position.x)
        var pointerX = down.position.x
        var pressed = true
        while (pressed) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            pressed = change != null && change.pressed
            if (change != null && pressed) {
                pointerX += change.positionChange().x
                change.consume()
                onDrag(overdrag(pointerX, slotWidthPx, maxX))
                onHover(indexAt(pointerX, slotWidthPx, count))
            }
        }
        onHover(-1)
        onRelease(indexAt(pointerX, slotWidthPx, count))
    }
}

/** 镜片的位置与缩放。 */
private class DockLens {
    val x = Animatable(0f)

    /** 0 = 静止，1 = 按住抬起：镜片每边长出 [LENS_GROWTH]，里面的图标放大 [LENS_MAGNIFY]。 */
    val lift = Animatable(0f)
    var placed by androidx.compose.runtime.mutableStateOf(false)

    suspend fun place(target: Float) {
        x.snapTo(target)
        placed = true
    }
}

private fun CoroutineScope.pressLens(lens: DockLens, pointerX: Float) {
    launch {
        if (!lens.placed) lens.place(pointerX) else lens.x.animateTo(pointerX, FollowSpring)
    }
    launch { lens.lift.animateTo(1f, LiftSpring) }
}

private fun CoroutineScope.releaseLens(lens: DockLens, target: Float) {
    launch { lens.x.animateTo(target, SettleSpring) }
    launch { lens.lift.animateTo(0f, PressSpring) }
}

@Composable
private fun Lens(lens: DockLens, slotWidthPx: Float) {
    val growthPx = with(LocalDensity.current) { LENS_GROWTH.toPx() } * 2f
    val rest = Surfaces.raisedColor
    val press = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // 照 FlClash 的 _lensRect：抬起时宽高各长 2 × 8dp；拖得快时沿运动方向拉长、竖向压扁（比例 1.4）。
                val rect = lensRect(lens, slotWidthPx, size.height, growthPx)
                // 抬起时叠一层 8% 的前景色，比静止时略深一点。
                val tint = press.copy(alpha = PRESS_TINT * lens.lift.value).compositeOver(rest)
                drawRoundRect(
                    color = tint,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(rect.height / 2f),
                )
            },
    )
}

@Composable
private fun DockItem(
    tab: TopLevelDestination,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = if (selected) "${tab.label}（当前页）" else tab.label
                onClick {
                    onClick()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun slotCenter(index: Int, slotWidthPx: Float): Float = slotWidthPx * (index + HALF)

private fun indexAt(x: Float, slotWidthPx: Float, count: Int): Int = (x / slotWidthPx).toInt().coerceIn(0, count - 1)

/** 拖出两端时只跟随一部分位移（阻尼），且不超过半个格子。 */
private fun overdrag(x: Float, slotWidthPx: Float, maxX: Float): Float {
    val minCenter = slotWidthPx / 2f
    val maxCenter = maxX - slotWidthPx / 2f
    val limit = slotWidthPx * PULL_LIMIT
    return when {
        x < minCenter -> minCenter - ((minCenter - x) * OVERDRAG).coerceAtMost(limit)
        x > maxCenter -> maxCenter + ((x - maxCenter) * OVERDRAG).coerceAtMost(limit)
        else -> x
    }
}

private val DOCK_HEIGHT = 64.dp
private val DOCK_MARGIN = 20.dp
private val DOCK_INSET = 4.dp
private const val DARK_LUMINANCE = 0.5f
private const val HALF = 0.5f
private val LENS_GROWTH = 8.dp
private const val LENS_MAGNIFY = 0.16f
private const val PRESS_TINT = 0.08f
private const val JELLY_ASPECT = 1.4f
private const val OVERDRAG = 0.35f
private const val PULL_LIMIT = 7f / 32f
private const val JELLY_VELOCITY = 12_000f
private const val JELLY_MAX = 0.18f

// FlClash 的弹簧：press 刚度 311 / 阻尼比 0.593；follow 1024 / 0.9；lift 250ms 无回弹；
// travel 400ms 回弹 0.15；settle 500ms 回弹 0.32。
private val PressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)
private val FollowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)
private val LiftSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 632f)
private val TravelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 247f)
private val SettleSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 158f)

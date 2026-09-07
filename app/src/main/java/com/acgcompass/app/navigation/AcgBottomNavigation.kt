package com.acgcompass.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
        Surface(
            modifier = Modifier.fillMaxWidth().height(DOCK_HEIGHT),
            shape = CircleShape,
            color = Surfaces.colorOf(dockLevel),
            shadowElevation = 12.dp,
        ) {
            SurfaceScope(dockLevel) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(DOCK_INSET)) {
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

    val litIndex = if (hoverIndex >= 0) hoverIndex else selectedIndex ?: -1
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
        if (lens.placed && (selectedIndex != null || hoverIndex >= 0)) {
            Lens(lens = lens, slotWidthPx = slotWidthPx)
        }
        Row(Modifier.fillMaxSize().selectableGroup()) {
            entries.forEachIndexed { index, tab ->
                DockItem(
                    tab = tab,
                    lit = index == litIndex,
                    selected = index == selectedIndex,
                    onClick = { if (index != selectedIndex) onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
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
    val scale = Animatable(1f)
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
    launch { lens.scale.animateTo(PRESSED_SCALE, LiftSpring) }
}

private fun CoroutineScope.releaseLens(lens: DockLens, target: Float) {
    launch { lens.x.animateTo(target, SettleSpring) }
    launch { lens.scale.animateTo(1f, PressSpring) }
}

@Composable
private fun Lens(lens: DockLens, slotWidthPx: Float) {
    val density = LocalDensity.current
    val widthDp = with(density) { slotWidthPx.toDp() }
    val color = Surfaces.raisedColor
    Box(
        Modifier
            .fillMaxHeight()
            .width(widthDp)
            .graphicsLayer {
                translationX = lens.x.value - slotWidthPx / 2f
                // 果冻感：沿运动方向按速度拉长，同时略压扁，面积大致不变。
                val stretch = (abs(lens.x.velocity) / JELLY_VELOCITY).coerceAtMost(JELLY_MAX)
                scaleX = lens.scale.value * (1f + stretch)
                scaleY = lens.scale.value * (1f - stretch * JELLY_SQUASH)
            }
            .background(color, CircleShape),
    )
}

@Composable
private fun DockItem(
    tab: TopLevelDestination,
    lit: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
            imageVector = if (lit) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
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
private val DOCK_INSET = 6.dp
private const val DARK_LUMINANCE = 0.5f
private const val HALF = 0.5f
private const val PRESSED_SCALE = 1.12f
private const val OVERDRAG = 0.35f
private const val PULL_LIMIT = 7f / 32f
private const val JELLY_VELOCITY = 12_000f
private const val JELLY_MAX = 0.18f
private const val JELLY_SQUASH = 0.5f

// FlClash 的弹簧：press 刚度 311 / 阻尼比 0.593；follow 1024 / 0.9；lift 250ms 无回弹；
// travel 400ms 回弹 0.15；settle 500ms 回弹 0.32。
private val PressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)
private val FollowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)
private val LiftSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 632f)
private val TravelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 247f)
private val SettleSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 158f)

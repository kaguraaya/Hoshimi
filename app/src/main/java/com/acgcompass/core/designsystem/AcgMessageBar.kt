package com.acgcompass.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 撤销提示条：移除操作后浮在页面底部（底栏上方），右侧是「撤销」，底边一条细线从满到空表示剩余时间，
 * 到时自动消失。外观按设计规范里「浮在内容上的东西」：浅色取卡片级、深色取色块级，带投影，圆角与列表卡片一致。
 *
 * @param message 为 null 时隐藏。同一条消息再次出现（新的 [key]）会重新计时。
 * @param key 每次新的撤销请求换一个值（如批次 id），用来重置倒计时。
 */
@Composable
fun AcgUndoBar(message: String?, key: Any?, onUndo: () -> Unit, onTimeout: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        val remaining = remember(key) { Animatable(1f) }
        LaunchedEffect(key) {
            remaining.snapTo(1f)
            remaining.animateTo(0f, tween(UNDO_DURATION_MILLIS, easing = LinearEasing))
            onTimeout()
        }
        FloatingMessageSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Column {
                MessageRow(message = message.orEmpty(), actionLabel = "撤销", onAction = onUndo)
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(remaining.value)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = PROGRESS_ALPHA)),
                    )
                }
            }
        }
    }
}

/**
 * 应用里所有从底部浮起的提示都用这个宿主：外观与 [AcgUndoBar] 相同（浮起的卡片级 / 色块级底色、24dp 圆角、投影），
 * 从下往上滑入；带动作时右侧是文字按钮。
 */
@Composable
fun AcgSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> AcgSnackbar(data) }
}

@Composable
private fun AcgSnackbar(data: SnackbarData) {
    val offset = remember(data) { Animatable(1f) }
    LaunchedEffect(data) {
        offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SLIDE_STIFFNESS))
    }
    FloatingMessageSurface(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { translationY = offset.value * size.height }
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        MessageRow(
            message = data.visuals.message,
            actionLabel = data.visuals.actionLabel,
            onAction = data::performAction,
        )
    }
}

/** 浮在内容上的提示条底色：浅色取卡片级、深色取色块级（深色下投影看不出来，要比下面的卡片亮一级）。 */
@Composable
private fun FloatingMessageSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE
    Surface(
        shape = MESSAGE_SHAPE,
        color = Surfaces.colorOf(if (dark) SurfaceLevel.Block else SurfaceLevel.Card),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun MessageRow(message: String, actionLabel: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = if (actionLabel != null) 8.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 撤销提示停留的时间。 */
const val UNDO_DURATION_MILLIS: Int = 6_000

private val MESSAGE_SHAPE = RoundedCornerShape(24.dp)
private const val SLIDE_STIFFNESS = 600f
private const val PROGRESS_ALPHA = 0.6f
private const val DARK_LUMINANCE = 0.5f

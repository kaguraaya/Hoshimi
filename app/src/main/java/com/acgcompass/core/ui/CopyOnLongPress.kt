package com.acgcompass.core.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/**
 * 长按把 [text] 复制到剪贴板（标题、别名、原名这类用户常想拿去搜索的文字）。
 *
 * 有触感反馈，并弹出「已复制」提示（不少系统在 Android 13 以上也不显示剪贴板浮层）。
 * 读屏下作为「长按：复制」的自定义操作暴露。
 */
fun Modifier.copyOnLongPress(text: String): Modifier = composed {
    if (text.isBlank()) return@composed this
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val showMessage = LocalAppMessenger.current
    val copy = {
        clipboard.setText(AnnotatedString(text))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // 部分系统（含不少国产 ROM）在 Android 13+ 也不显示剪贴板提示，统一显示一条底部提示。
        showMessage("已复制「${text.take(TOAST_TITLE_CHARS)}」")
    }
    this
        .pointerInput(text) { detectTapGestures(onLongPress = { copy() }) }
        .semantics {
            onLongClick(label = "复制") {
                copy()
                true
            }
        }
}

private const val TOAST_TITLE_CHARS = 20

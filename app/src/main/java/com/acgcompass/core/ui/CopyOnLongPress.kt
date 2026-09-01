package com.acgcompass.core.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/**
 * 长按把 [text] 复制到剪贴板（标题、别名、原名这类用户常想拿去搜索的文字）。
 *
 * 有触感反馈；Android 13 起系统自己会弹出「已复制」的浮层，更早的版本补一个 Toast。
 * 读屏下作为「长按：复制」的自定义操作暴露。
 */
fun Modifier.copyOnLongPress(text: String): Modifier = composed {
    if (text.isBlank()) return@composed this
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val copy = {
        clipboard.setText(AnnotatedString(text))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
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

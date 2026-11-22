package com.acgcompass.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acgcompass.core.common.CrashLog
import com.acgcompass.core.designsystem.PrismCard

/**
 * 后台任务页底部的「异常记录」：闪退、被拦下的后台异常、系统记下的退出原因（只在本机）。
 * 列最近几条的时间和一句话说明；「复制全部」把完整堆栈放进剪贴板，方便发给开发者排查。
 */
@Composable
internal fun CrashLogCard(entries: List<CrashLog.Entry>, onCopied: () -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("异常记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (entries.isEmpty()) {
                Text(
                    "最近没有记录到闪退或后台异常。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "闪退、被拦下的后台异常和系统记下的退出原因，只保存在本机，最多 10 条。遇到闪退时复制发给开发者，能直接看到出错的位置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.asReversed().take(SHOWN).forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.header, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(entries.asReversed().joinToString("\n") { it.text }))
                        onCopied()
                    },
                ) { Text("复制全部") }
                TextButton(onClick = onClear) { Text("清空") }
            }
        }
    }
}

private const val SHOWN = 5

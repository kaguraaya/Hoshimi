package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 升级后核心表的记录明显变少时的提示：可以用升级前最近的自动备份补回（按导入规则合并，不删除现有记录），
 * 或确认没问题后关闭。升级前的数据库备份可以在「数据安全」页导出或还原。
 */
@Composable
internal fun DataMayBeMissingDialog(
    details: String,
    hasAutoBackup: Boolean,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("升级后部分记录可能不见了") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这次升级后，以下数据比升级前明显变少：")
                Text(details, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (hasAutoBackup) {
                        "可以用升级前的自动备份补回，现有记录不会被删除。升级前的数据库备份也保留着，在 设置 → 备份与导出 → 数据安全 里。"
                    } else {
                        "没有升级前的自动备份可用。升级前的数据库备份保留着，可以在 设置 → 备份与导出 → 数据安全 里导出或还原。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (hasAutoBackup) Button(onClick = onRestore) { Text("用自动备份补回") }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(if (hasAutoBackup) "没问题，关闭" else "知道了") }
        },
    )
}

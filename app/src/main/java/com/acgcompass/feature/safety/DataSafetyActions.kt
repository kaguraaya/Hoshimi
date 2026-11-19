package com.acgcompass.feature.safety

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.acgcompass.data.backup.AutoBackupFile
import com.acgcompass.data.backup.UpgradeBackupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 页面上的操作。 */
data class DataSafetyActions(
    val onBack: () -> Unit,
    val onToggleAutoBackup: (Boolean) -> Unit,
    val onBackupNow: () -> Unit,
    val onRestoreAutoBackup: (AutoBackupFile) -> Unit,
    val onExportAutoBackup: (AutoBackupFile) -> Unit,
    val onExportUpgradeBackup: (UpgradeBackupInfo) -> Unit,
    val onRestoreUpgradeBackup: (UpgradeBackupInfo) -> Unit,
)

@Suppress("LongParameterList")
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm) }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun autoBackupStatusText(state: DataSafetyUiState): String = when {
    !state.autoBackupEnabled -> "自动备份已关闭"
    state.lastSuccessAt <= 0L -> "还没有成功备份过，打开应用后会自动进行"
    else -> "上次成功：${formatTime(state.lastSuccessAt)}"
}

internal fun formatTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

internal fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.ROOT, bytes / BYTES_PER_MB.toDouble())
    else -> "%.1f KB".format(Locale.ROOT, bytes / BYTES_PER_KB.toDouble())
}

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L

package com.acgcompass.feature.safety

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.data.backup.AutoBackupFile
import com.acgcompass.data.backup.UpgradeBackupInfo

/**
 * 安全模式：数据库升级失败、打不开时代替整个应用界面显示（其它页面都依赖数据库，进不去）。
 *
 * 数据没有被改动：迁移在事务里进行，失败时数据库文件还是升级前的样子，另外还有一份升级前备份。
 * 用户可以：
 * - 什么都不做，等修复后的版本覆盖安装，会重新升级；
 * - 导出升级前备份（zip），交给开发者排查或自己留存；
 * - 用最近的自动备份重新开始（换一个新数据库，再导入自动备份）；没有自动备份时以空白数据重新开始。
 *   两种方式都保留升级前备份，之后还能在「数据安全」页导出或还原。
 */
@Composable
fun SafeModeScreen(
    errorMessage: String,
    upgradeBackup: UpgradeBackupInfo?,
    latestAutoBackup: AutoBackupFile?,
    actions: SafeModeCallbacks,
    modifier: Modifier = Modifier,
) {
    var confirmFresh by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) actions.onExportUpgradeBackup(uri)
        }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("数据库升级没有完成", style = MaterialTheme.typography.headlineSmall)
            Text(
                "这个版本没能升级你的本地数据，应用暂时进入安全模式。你的数据没有被改动：数据库还是升级前的样子，" +
                    "另外留有一份升级前备份。",
                style = MaterialTheme.typography.bodyMedium,
            )
            SafeModeOption(
                title = "等待修复版本",
                body = "直接退出即可。之后覆盖安装修复后的版本，会重新升级，数据照常保留。",
            ) { FilledTonalButton(onClick = actions.onExit) { Text("退出应用") } }
            if (upgradeBackup != null) {
                SafeModeOption(
                    title = "导出升级前备份",
                    body = "把原始数据库文件打包成 zip 保存到别处，可以交给开发者排查，也可以自己留存。",
                ) {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch(upgradeBackup.suggestedFileName) },
                    ) { Text("导出") }
                }
            }
            SafeModeOption(
                title = if (latestAutoBackup != null) "用自动备份重新开始" else "以空白数据重新开始",
                body = freshStartBody(latestAutoBackup),
            ) { Button(onClick = { confirmFresh = true }) { Text("重新开始") } }
            ErrorDetails(errorMessage)
        }
    }
    if (confirmFresh) {
        FreshStartDialog(
            hasAutoBackup = latestAutoBackup != null,
            onConfirm = { actions.onFreshStart(latestAutoBackup != null) },
            onDismiss = { confirmFresh = false },
        )
    }
}

@Composable
private fun SafeModeOption(title: String, body: String, action: @Composable () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

@Composable
private fun ErrorDetails(errorMessage: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("错误信息", style = MaterialTheme.typography.labelLarge)
        Text(
            errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(errorMessage)) }) { Text("复制错误信息") }
    }
}

@Composable
private fun FreshStartDialog(hasAutoBackup: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新开始") },
        text = {
            Text(
                if (hasAutoBackup) {
                    "会换一个新的数据库并导入最近的自动备份，自动备份之后的改动不会恢复。升级前备份会保留。"
                } else {
                    "会换一个空白的数据库，本机记录需要重新同步或导入。升级前备份会保留，之后可以在「数据安全」页导出或还原。"
                },
            )
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("重新开始") }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun freshStartBody(latest: AutoBackupFile?): String = if (latest != null) {
    "换一个新的数据库，再导入 ${formatTime(latest.createdAt)} 的自动备份，马上就能继续用。升级前备份仍然保留。"
} else {
    "没有可用的自动备份。换一个空白数据库后，可以重新同步 Bangumi 或导入备份文件。升级前备份仍然保留。"
}

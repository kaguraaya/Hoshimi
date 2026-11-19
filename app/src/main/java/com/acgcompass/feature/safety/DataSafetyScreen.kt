package com.acgcompass.feature.safety

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.data.backup.AutoBackupFile
import com.acgcompass.data.backup.UpgradeBackupInfo

@Composable
fun DataSafetyRoute(onBack: () -> Unit, viewModel: DataSafetyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    var exportingAuto by remember { mutableStateOf<AutoBackupFile?>(null) }
    var exportingUpgrade by remember { mutableStateOf<UpgradeBackupInfo?>(null) }
    val autoLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val backup = exportingAuto
            if (uri != null && backup != null) viewModel.onExportAutoBackup(backup, uri)
            exportingAuto = null
        }
    val upgradeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val backup = exportingUpgrade
            if (uri != null && backup != null) viewModel.onExportUpgradeBackup(backup, uri)
            exportingUpgrade = null
        }
    DataSafetyScreen(
        state = state,
        snackbarHostState = snackbar,
        actions = DataSafetyActions(
            onBack = onBack,
            onToggleAutoBackup = viewModel::onToggleAutoBackup,
            onBackupNow = viewModel::onBackupNow,
            onRestoreAutoBackup = viewModel::onRestoreAutoBackup,
            onExportAutoBackup = { backup ->
                exportingAuto = backup
                autoLauncher.launch(backup.file.name)
            },
            onExportUpgradeBackup = { backup ->
                exportingUpgrade = backup
                upgradeLauncher.launch(backup.suggestedFileName)
            },
            onRestoreUpgradeBackup = viewModel::onRestoreUpgradeBackup,
        ),
    )
}

/**
 * 数据安全（设置 → 备份与导出 → 数据安全）：
 * - 自动备份：每周一次 + 启动时补做，写入后读回校验；可以立即备份、恢复（按导入规则合并）、导出到别处；
 * - 升级前备份：数据库结构升级前自动复制的原始数据库文件，可以导出，或在升级出问题时还原后重新迁移。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DataSafetyScreen(
    state: DataSafetyUiState,
    snackbarHostState: SnackbarHostState,
    actions: DataSafetyActions,
    modifier: Modifier = Modifier,
) {
    var confirmRestoreAuto by remember { mutableStateOf<AutoBackupFile?>(null) }
    var confirmRestoreUpgrade by remember { mutableStateOf<UpgradeBackupInfo?>(null) }
    AcgScreenScaffold(
        title = "数据安全",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        snackbarHost = { com.acgcompass.core.designsystem.AcgSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenContentPadding.lazyList(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "auto") { AutoBackupCard(state, actions) }
            item(key = "auto-files") {
                AutoBackupFilesCard(state.autoBackups, actions.onExportAutoBackup) { confirmRestoreAuto = it }
            }
            item(key = "upgrade") {
                UpgradeBackupsCard(state.upgradeBackups, actions.onExportUpgradeBackup) { confirmRestoreUpgrade = it }
            }
        }
    }
    confirmRestoreAuto?.let { backup ->
        ConfirmDialog(
            title = "从这份自动备份恢复",
            text = "备份于 ${formatTime(backup.createdAt)}。恢复和「导入备份」一样按记录合并：备份里有、本机没有的会加回来，" +
                "两边都有的保留更新的一方，本机新增的不会被删除。",
            confirm = "恢复",
            onConfirm = { actions.onRestoreAutoBackup(backup) },
            onDismiss = { confirmRestoreAuto = null },
        )
    }
    confirmRestoreUpgrade?.let { backup ->
        ConfirmDialog(
            title = "还原到升级前的数据",
            text = "当前数据会被这份升级前备份（${formatTime(backup.createdAt)}）整体替换，之后的改动都会丢失，" +
                "应用会重新启动并重新升级数据库。只建议在升级后数据出问题时使用；不确定的话先导出当前数据。",
            confirm = "替换并重启",
            onConfirm = { actions.onRestoreUpgradeBackup(backup) },
            onDismiss = { confirmRestoreUpgrade = null },
        )
    }
}

@Composable
private fun AutoBackupCard(state: DataSafetyUiState, actions: DataSafetyActions) {
    SafetyCard(title = "自动备份") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("每周自动备份", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "每周一次，打开应用时发现超过一周没备份成功也会立即补做。备份写完会读回来检查，" +
                        "只保留最近 3 份，不含任何账号凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlatSwitch(checked = state.autoBackupEnabled, onCheckedChange = actions.onToggleAutoBackup)
        }
        FlatDivider()
        Text(autoBackupStatusText(state), style = MaterialTheme.typography.bodyMedium)
        state.lastError?.let {
            Text(
                "最近一次失败：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "备份存放在应用自己的目录里，卸载应用会一起删除；换机或重装前请把备份导出到别处。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = actions.onBackupNow, enabled = !state.backingUp) {
                Text(if (state.backingUp) "备份中…" else "立即备份")
            }
        }
    }
}

@Composable
private fun AutoBackupFilesCard(
    backups: List<AutoBackupFile>,
    onExport: (AutoBackupFile) -> Unit,
    onRestore: (AutoBackupFile) -> Unit,
) {
    SafetyCard(title = "自动备份文件") {
        if (backups.isEmpty()) {
            EmptyLine("还没有自动备份。")
        }
        backups.forEachIndexed { index, backup ->
            if (index > 0) FlatDivider()
            BackupRow(
                title = formatTime(backup.createdAt),
                subtitle = formatBytes(backup.bytes),
                onExport = { onExport(backup) },
                restoreLabel = "恢复",
                onRestore = { onRestore(backup) },
            )
        }
    }
}

@Composable
private fun UpgradeBackupsCard(
    backups: List<UpgradeBackupInfo>,
    onExport: (UpgradeBackupInfo) -> Unit,
    onRestore: (UpgradeBackupInfo) -> Unit,
) {
    SafetyCard(title = "升级前备份") {
        Text(
            "数据库结构升级前，应用会把原来的数据库文件原样复制一份（最多 2 份）。升级出问题时可以导出交给开发者排查，" +
                "或还原后重新升级。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (backups.isEmpty()) {
            EmptyLine("还没有升级前备份（只在数据库结构升级时生成）。")
        }
        backups.forEachIndexed { index, backup ->
            if (index > 0) FlatDivider()
            BackupRow(
                title = formatTime(backup.createdAt),
                subtitle = "数据库版本 ${backup.fromVersion} → ${backup.toVersion} · ${formatBytes(
                    backup.bytes,
                )}",
                onExport = { onExport(backup) },
                restoreLabel = "还原",
                onRestore = { onRestore(backup) },
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun BackupRow(
    title: String,
    subtitle: String,
    onExport: () -> Unit,
    restoreLabel: String,
    onRestore: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onExport) { Text("导出") }
        TextButton(onClick = onRestore) { Text(restoreLabel) }
    }
}

@Composable
private fun SafetyCard(title: String, content: @Composable () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

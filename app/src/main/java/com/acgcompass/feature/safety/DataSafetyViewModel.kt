package com.acgcompass.feature.safety

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.backup.AutoBackupFile
import com.acgcompass.data.backup.AutoBackupManager
import com.acgcompass.data.backup.DataSafety
import com.acgcompass.data.backup.UpgradeBackupInfo
import com.acgcompass.data.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 数据安全页的状态。 */
data class DataSafetyUiState(
    val autoBackupEnabled: Boolean = true,
    val lastSuccessAt: Long = 0L,
    val lastError: String? = null,
    val backingUp: Boolean = false,
    val autoBackups: List<AutoBackupFile> = emptyList(),
    val upgradeBackups: List<UpgradeBackupInfo> = emptyList(),
)

/** 数据安全页：自动备份开关与状态、立即备份、从自动备份恢复、导出；升级前备份的导出与还原。 */
@HiltViewModel
class DataSafetyViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val autoBackupManager: AutoBackupManager,
    private val dataSafety: DataSafety,
) : ViewModel() {

    private val files = MutableStateFlow(BackupFiles())
    private val backingUp = MutableStateFlow(false)

    val uiState: StateFlow<DataSafetyUiState> = combine(
        settingsDataStore.autoBackupEnabled,
        settingsDataStore.autoBackupStatus,
        files,
        backingUp,
    ) { enabled, (lastSuccess, lastError), currentFiles, running ->
        DataSafetyUiState(
            autoBackupEnabled = enabled,
            lastSuccessAt = lastSuccess,
            lastError = lastError,
            backingUp = running,
            autoBackups = currentFiles.auto,
            upgradeBackups = currentFiles.upgrade,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataSafetyUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshFiles()
    }

    fun onToggleAutoBackup(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoBackupEnabled(enabled) }
    }

    fun onBackupNow() {
        if (backingUp.value) return
        backingUp.value = true
        viewModelScope.launch {
            val result = autoBackupManager.backupNow()
            backingUp.value = false
            refreshFiles()
            _message.value = when (result) {
                is AppResult.Success -> "备份完成"
                is AppResult.Failure -> result.error.cause
            }
        }
    }

    fun onRestoreAutoBackup(backup: AutoBackupFile) {
        viewModelScope.launch {
            _message.value = when (val result = autoBackupManager.restore(backup)) {
                is AppResult.Success -> "已恢复：新增 ${result.data.added} 条，更新 ${result.data.updated} 条"
                is AppResult.Failure -> "恢复失败：${result.error.cause}"
            }
        }
    }

    fun onExportAutoBackup(backup: AutoBackupFile, target: Uri) {
        viewModelScope.launch {
            _message.value = if (dataSafety.exportAutoBackup(backup, target)) "已导出" else "导出失败"
        }
    }

    fun onExportUpgradeBackup(backup: UpgradeBackupInfo, target: Uri) {
        viewModelScope.launch {
            _message.value = if (dataSafety.exportUpgradeBackup(backup.id, target)) "已导出" else "导出失败"
        }
    }

    /** 用升级前备份替换当前数据并重启应用。 */
    fun onRestoreUpgradeBackup(backup: UpgradeBackupInfo) {
        viewModelScope.launch { dataSafety.restoreUpgradeBackupAndRestart(backup.id) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            files.value = BackupFiles(autoBackupManager.list(), dataSafety.upgradeBackups())
        }
    }

    private data class BackupFiles(
        val auto: List<AutoBackupFile> = emptyList(),
        val upgrade: List<UpgradeBackupInfo> = emptyList(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

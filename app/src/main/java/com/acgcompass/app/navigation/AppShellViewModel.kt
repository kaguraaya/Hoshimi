package com.acgcompass.app.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.backup.AutoBackupFile
import com.acgcompass.data.backup.DataSafety
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.migration.DatabaseHealth
import com.acgcompass.data.local.migration.DatabaseHealthMonitor
import com.acgcompass.data.sync.AccountSwitchChoice
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.domain.repository.TrashRepository
import com.acgcompass.domain.undo.UndoController
import com.acgcompass.domain.undo.UndoRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用根部的状态：撤销提示条（[undo]）、等待选择的 Bangumi 账号切换（[accountSwitch]）和数据库状态（[health]）。
 * 它们与具体页面无关，任何页面发起的移除都在这里显示撤销，任何入口触发的同步发现换账号都在这里询问；
 * 数据库打不开时整个界面换成安全模式，升级后疑似少了数据时在这里提示。
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class AppShellViewModel @Inject constructor(
    undoController: UndoController,
    private val trashRepository: TrashRepository,
    settingsDataStore: SettingsDataStore,
    private val syncManager: BangumiSyncManager,
    private val healthMonitor: DatabaseHealthMonitor,
    private val dataSafety: DataSafety,
) : ViewModel() {

    /** 数据库状态：检查中时不显示页面，打不开时显示安全模式。 */
    val health: StateFlow<DatabaseHealth> = healthMonitor.state

    private val _autoBackupBeforeUpgrade = MutableStateFlow<AutoBackupFile?>(null)

    /**
     * 安全模式和「可能少了数据」提示用的自动备份：升级之前的最近一份（升级后生成的可能已经是缺数据的版本）。
     */
    val autoBackupBeforeUpgrade: StateFlow<AutoBackupFile?> = _autoBackupBeforeUpgrade.asStateFlow()

    private val _undo = MutableStateFlow<UndoRequest?>(null)

    /** 正在显示的撤销提示；为 null 时隐藏。 */
    val undo: StateFlow<UndoRequest?> = _undo.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示（撤销结果、账号处理结果）。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 等待选择的账号切换：旧账号 to 新账号。 */
    val accountSwitch: StateFlow<Pair<String, String>?> = settingsDataStore.pendingAccountSwitch
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { undoController.requests.collect { _undo.value = it } }
        viewModelScope.launch {
            dataSafety.lastRestoreMessage.collect { message ->
                if (message != null) {
                    _notice.value = message
                    dataSafety.consumeRestoreMessage()
                }
            }
        }
        viewModelScope.launch {
            health.collect { state ->
                val upgradeAt = when (state) {
                    is DatabaseHealth.Failed -> state.backup?.meta?.createdAt ?: Long.MAX_VALUE
                    is DatabaseHealth.DataMayBeMissing -> state.backup.meta.createdAt
                    else -> return@collect
                }
                _autoBackupBeforeUpgrade.value = dataSafety.autoBackupBefore(upgradeAt)
            }
        }
    }

    /** 安全模式：导出升级前备份。 */
    fun onExportUpgradeBackup(target: Uri) {
        val backup = (health.value as? DatabaseHealth.Failed)?.backup ?: return
        viewModelScope.launch { dataSafety.exportUpgradeBackup(backup.dir.name, target) }
    }

    /** 安全模式：换一个新数据库重新开始（可选导入升级前最近的自动备份），然后重启。 */
    fun onFreshStart(restoreAutoBackup: Boolean) {
        val name = _autoBackupBeforeUpgrade.value?.file?.name?.takeIf { restoreAutoBackup }
        viewModelScope.launch { dataSafety.freshStartAndRestart(name) }
    }

    /** 升级后疑似少了数据：用升级前最近的自动备份补回（按导入规则合并，不会删除现有记录）。 */
    fun onRestoreMissingData() {
        val backup = _autoBackupBeforeUpgrade.value ?: return
        viewModelScope.launch {
            _notice.value = when (val result = dataSafety.restoreAutoBackup(backup)) {
                is AppResult.Success -> {
                    healthMonitor.dismissWarning()
                    "已补回：新增 ${result.data.added} 条，更新 ${result.data.updated} 条"
                }
                is AppResult.Failure -> "补回失败：${result.error.cause}"
            }
        }
    }

    fun onDismissDataWarning() {
        healthMonitor.dismissWarning()
    }

    fun onUndo() {
        val request = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            val restored = trashRepository.restoreBatch(request.batchId)
            _notice.value = if (restored > 0) "已撤销" else "作品已经重新加入，没有需要恢复的记录"
        }
    }

    fun onUndoTimeout(request: UndoRequest) {
        if (_undo.value == request) _undo.value = null
    }

    fun onResolveAccountSwitch(choice: AccountSwitchChoice) {
        viewModelScope.launch {
            val result = syncManager.resolveAccountSwitch(choice)
            _notice.value = when {
                choice == AccountSwitchChoice.CANCEL -> "已暂停同步，下次同步时会再询问"
                result is AppResult.Success -> "已切换账号并完成同步"
                else -> "已处理旧账号的收藏，同步暂未完成，可以稍后再试"
            }
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }
}

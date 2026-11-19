package com.acgcompass.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.datastore.SettingsDataStore
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
 * 应用根部的状态：撤销提示条（[undo]）和等待选择的 Bangumi 账号切换（[accountSwitch]）。
 * 它们与具体页面无关，任何页面发起的移除都在这里显示撤销，任何入口触发的同步发现换账号都在这里询问。
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    undoController: UndoController,
    private val trashRepository: TrashRepository,
    settingsDataStore: SettingsDataStore,
    private val syncManager: BangumiSyncManager,
) : ViewModel() {

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

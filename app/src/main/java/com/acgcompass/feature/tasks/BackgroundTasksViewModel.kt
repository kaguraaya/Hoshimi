package com.acgcompass.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.CrashLog
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import com.acgcompass.domain.repository.BackgroundTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置 → 后台任务：各任务的运行状况与手动重试。 */
@HiltViewModel
class BackgroundTasksViewModel @Inject constructor(
    private val repository: BackgroundTaskRepository,
) : ViewModel() {
    val statuses: StateFlow<List<BackgroundTaskStatus>?> = repository.observeStatuses()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _crashEntries = MutableStateFlow<List<CrashLog.Entry>>(emptyList())

    /** 本机的异常记录（闪退、被拦下的后台异常、系统记下的退出原因），旧的在前。 */
    val crashEntries: StateFlow<List<CrashLog.Entry>> = _crashEntries.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _crashEntries.value = CrashLog.entries() }
    }

    fun onCrashLogCopied() {
        _message.value = "已复制异常记录"
    }

    fun onClearCrashLog() {
        viewModelScope.launch(Dispatchers.IO) {
            CrashLog.clear()
            _crashEntries.value = emptyList()
        }
    }

    fun onRetry(kind: BackgroundTaskKind) {
        viewModelScope.launch {
            val queued = runCatchingCancellable { repository.retry(kind) }.getOrDefault(false)
            _message.value = if (queued) "已安排「${kind.label()}」马上再跑一次" else "这个任务现在不能手动重试"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

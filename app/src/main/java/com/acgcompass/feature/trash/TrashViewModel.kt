package com.acgcompass.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.domain.model.TrashEntry
import com.acgcompass.domain.repository.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 回收站页：列出记录，恢复 / 彻底删除 / 清空。 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
) : ViewModel() {

    /** null 表示还在读取。 */
    val entries: StateFlow<List<TrashEntry>?> = trashRepository.observeEntries()
        .map<List<TrashEntry>, List<TrashEntry>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onRestore(entry: TrashEntry) {
        viewModelScope.launch {
            _message.value = if (trashRepository.restore(entry.id)) {
                "已恢复「${entry.title}」"
            } else {
                "「${entry.title}」已经重新加入，回收站里的旧记录已丢弃"
            }
        }
    }

    fun onDeleteForever(entry: TrashEntry) {
        viewModelScope.launch { trashRepository.deleteForever(entry.id) }
    }

    fun onEmptyTrash() {
        viewModelScope.launch {
            trashRepository.emptyTrash()
            _message.value = "回收站已清空"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

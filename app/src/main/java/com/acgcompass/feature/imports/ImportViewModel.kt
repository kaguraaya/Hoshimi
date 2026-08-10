package com.acgcompass.feature.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportSource
import com.acgcompass.domain.repository.ImportRepository
import com.acgcompass.domain.usecase.ImportTextParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 批量导入页 UI 状态。 */
data class ImportUiState(
    val pastedText: String = "",
    val previewTitles: List<String> = emptyList(),
    val processing: Boolean = false,
    val message: String? = null,
)

/**
 * 批量导入 ViewModel（.x）。
 * 粘贴文本 → 纯函数 [ImportTextParser] 拆分预览 → [ImportRepository.createBatch] 多源匹配 →
 * 展示识别结果 → [ImportRepository.addBatchToBacklog] 一键加入（去重 + 被安利计数）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importRepository: ImportRepository,
) : ViewModel() {

    private val parser = ImportTextParser()

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val _currentBatchId = MutableStateFlow<String?>(null)

    /** 当前批次的识别明细。 */
    val items: StateFlow<List<ImportItem>> =
        _currentBatchId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else importRepository.observeItems(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun onTextChange(text: String) {
        val titles = parser.parsePastedText(text).map { it.title }
        _state.value = _state.value.copy(pastedText = text, previewTitles = titles, message = null)
    }

    /** 解析并匹配。 */
    fun onParseAndMatch() {
        val text = _state.value.pastedText
        val candidates = parser.parsePastedText(text)
        if (candidates.isEmpty()) {
            _state.value = _state.value.copy(message = "没有可识别的作品名")
            return
        }
        _state.value = _state.value.copy(processing = true, message = "正在多源匹配…")
        viewModelScope.launch {
            val name = "粘贴导入 ${candidates.size} 项"
            when (val result = importRepository.createBatch(name, ImportSource.PASTE, candidates)) {
                is AppResult.Success -> {
                    _currentBatchId.value = result.data.id
                    _state.value = _state.value.copy(
                        processing = false,
                        message = "识别完成：成功 ${result.data.successCount} / 失败 ${result.data.failureCount}",
                    )
                }
                is AppResult.Failure -> {
                    _state.value = _state.value.copy(processing = false, message = "匹配失败，请检查网络或数据源后重试")
                }
            }
        }
    }

    /** 一键加入待补池（去重 + 被安利计数）。 */
    fun onAddToBacklog() {
        val batchId = _currentBatchId.value ?: return
        _state.value = _state.value.copy(processing = true)
        viewModelScope.launch {
            when (val result = importRepository.addBatchToBacklog(batchId)) {
                is AppResult.Success -> {
                    val added = result.data.addedCount
                    val dup = result.data.duplicateCount
                    _state.value = _state.value.copy(
                        processing = false,
                        message = "已加入待补池：新增 $added，重复 $dup（重复仅增加被安利次数）",
                    )
                }
                is AppResult.Failure -> {
                    _state.value = _state.value.copy(processing = false, message = "加入失败，请重试")
                }
            }
        }
    }
}

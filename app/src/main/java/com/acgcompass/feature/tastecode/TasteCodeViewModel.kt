package com.acgcompass.feature.tastecode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.domain.tastecode.RepresentativeReason
import com.acgcompass.domain.tastecode.RepresentativeWork
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCodeCodec
import com.acgcompass.domain.tastecode.TasteCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 生成页上的一部代表作。 */
data class RepresentativeItem(
    val work: RepresentativeWork,
    val title: String?,
    val coverUrl: String?,
    val included: Boolean = true,
)

data class TasteCodeUiState(
    val loading: Boolean = true,
    /** 还没有口味画像（没有评分数据），生成不了。 */
    val unavailable: Boolean = false,
    val base: TasteCode? = null,
    val items: List<RepresentativeItem> = emptyList(),
    val nickname: String = "",
    /** 生成好的口味码文本；为 null 时还在编辑。 */
    val generated: String? = null,
) {
    val includedCount: Int get() = items.count { it.included }
}

/**
 * 生成我的口味码：代表作默认全部带上，用户可以逐部去掉、全部去掉或恢复默认；昵称可选。
 * 生成时才把用户的选择写进口味码，编辑过程中只改界面状态。
 */
@HiltViewModel
class TasteCodeViewModel @Inject constructor(
    private val repository: TasteCodeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TasteCodeUiState())
    val state: StateFlow<TasteCodeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val draft = repository.draft()
            if (draft == null) {
                _state.value = TasteCodeUiState(loading = false, unavailable = true)
                return@launch
            }
            val info = repository.workInfo(draft.candidates.map { it.subjectId })
            _state.value = TasteCodeUiState(
                loading = false,
                base = draft.base,
                items = draft.candidates.map { work ->
                    RepresentativeItem(work, info[work.subjectId]?.title, info[work.subjectId]?.coverUrl)
                },
            )
        }
    }

    fun onToggleWork(subjectId: Int) = _state.update { state ->
        state.copy(
            items = state.items.map { if (it.work.subjectId == subjectId) it.copy(included = !it.included) else it },
            generated = null,
        )
    }

    fun onRemoveAllWorks() = _state.update { state ->
        state.copy(items = state.items.map { it.copy(included = false) }, generated = null)
    }

    fun onRestoreDefaultWorks() = _state.update { state ->
        state.copy(items = state.items.map { it.copy(included = true) }, generated = null)
    }

    fun onNicknameChange(value: String) = _state.update {
        it.copy(nickname = value.take(TasteCode.MAX_NICKNAME), generated = null)
    }

    fun onGenerate() = _state.update { state ->
        val base = state.base ?: return@update state
        val code = base.copy(
            nickname = state.nickname.trim().ifEmpty { null },
            works = state.items.filter { it.included }.map { it.work },
        )
        state.copy(generated = TasteCodeCodec.encode(code))
    }

    fun onEdit() = _state.update { it.copy(generated = null) }
}

/** 生成页的操作。 */
data class TasteCodeActions(
    val onBack: () -> Unit,
    val onToggleWork: (Int) -> Unit,
    val onRemoveAll: () -> Unit,
    val onRestoreDefault: () -> Unit,
    val onNicknameChange: (String) -> Unit,
    val onGenerate: () -> Unit,
    val onEdit: () -> Unit,
)

internal fun styleLine(base: TasteCode): String {
    val style = base.style
    val leniency = when {
        style.leniency >= LENIENT -> "比大众手松 %.1f 分".format(style.leniency)
        style.leniency <= -LENIENT -> "比大众手紧 %.1f 分".format(-style.leniency)
        else -> "和大众差不多"
    }
    return "评分 ${style.ratedCount} 部 · 均分 %.1f · $leniency".format(style.mean)
}

internal fun reasonLabel(reason: RepresentativeReason): String = when (reason) {
    RepresentativeReason.TOP_RATED -> "评分最高"
    RepresentativeReason.MOST_DIVERGENT -> "和大众分歧最大"
    RepresentativeReason.RECENT -> "最近评分"
}

private const val LENIENT = 0.2f

package com.acgcompass.feature.tastecode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.domain.tastecode.CompatibilityResult
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCodeCodec
import com.acgcompass.domain.tastecode.TasteCodeRepository
import com.acgcompass.domain.tastecode.TasteCodeWorkInfo
import com.acgcompass.domain.tastecode.TasteCompatibility
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CompatibilityUiState {
    data object Loading : CompatibilityUiState

    data object InvalidCode : CompatibilityUiState

    /** 自己还没有口味画像，算不了对比。 */
    data object NoOwnProfile : CompatibilityUiState

    data class Ready(
        val otherName: String,
        val mine: TasteCode,
        val other: TasteCode,
        val result: CompatibilityResult,
        /** 「你们俩」卡片里的结论。 */
        val insights: List<Insight>,
        /** 对方推荐里去掉我已收藏的。 */
        val picks: List<RepresentativeItem>,
        val works: Map<Int, TasteCodeWorkInfo>,
    ) : CompatibilityUiState
}

/**
 * 口味相性：我的口味码（默认代表作）和对方的口味码对比。契合度只由两份口味码计算，双方看到的一样；
 * 推荐列表再按我的收藏去掉已经看过 / 收藏过的。
 */
@HiltViewModel
class TasteCompatibilityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TasteCodeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<CompatibilityUiState>(CompatibilityUiState.Loading)
    val state: StateFlow<CompatibilityUiState> = _state.asStateFlow()

    init {
        val other = savedStateHandle.get<String>(TASTE_CODE_ARG)?.let(TasteCodeCodec::decode)
        viewModelScope.launch {
            _state.value = when {
                other == null -> CompatibilityUiState.InvalidCode
                else -> {
                    val mine = repository.draft()?.base
                    if (mine == null) {
                        CompatibilityUiState.NoOwnProfile
                    } else {
                        val result = TasteCompatibility.compare(mine, other)
                        val collected = repository.collectedSubjectIds()
                        val ids = result.commonWorks.map { it.first } + result.picksForA.map { it.subjectId }
                        val info = repository.workInfo(ids)
                        val otherName = other.nickname ?: "Ta"
                        CompatibilityUiState.Ready(
                            otherName = otherName,
                            mine = mine,
                            other = other,
                            result = result,
                            insights = CompatibilityInsights.of(mine, other, result, otherName),
                            picks = result.picksForA.filter { it.subjectId !in collected }.map {
                                RepresentativeItem(it, info[it.subjectId]?.title, info[it.subjectId]?.coverUrl)
                            },
                            works = info,
                        )
                    }
                }
            }
        }
    }
}

/** 口味相性页路由里口味码参数的名字。 */
const val TASTE_CODE_ARG = "code"

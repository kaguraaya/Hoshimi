package com.acgcompass.feature.tasteranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.taste.LocalPoolStatus
import com.acgcompass.domain.taste.PoolExpansion
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.TasteRankingRepository
import com.acgcompass.domain.taste.WorkFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 年代筛选。 */
@Suppress("MagicNumber") // 年代分界
enum class EraFilter(val label: String, val years: IntRange?) {
    ALL("全部年代", null),
    RECENT("2020 年起", 2020..Int.MAX_VALUE),
    TEENS("2010 年代", 2010..2019),
    NOUGHTS("2000 年代", 2000..2009),
    OLDER("更早", Int.MIN_VALUE..1999),
}

/** 口味排行的筛选条件。[formats] 为空表示不限形式。 */
data class TasteRankingFilter(
    val formats: Set<WorkFormat> = emptySet(),
    val era: EraFilter = EraFilter.ALL,
    val finishedOnly: Boolean = false,
    val hideOwned: Boolean = true,
) {
    /** 和默认值不同的筛选项数，显示在「筛选」按钮上。 */
    val activeCount: Int
        get() = listOf(formats.isNotEmpty(), era != EraFilter.ALL, finishedOnly, !hideOwned).count { it }

    fun accepts(item: TasteRankItem): Boolean = (formats.isEmpty() || item.format in formats) &&
        (era.years == null || (item.year != null && item.year in era.years)) &&
        (!finishedOnly || item.finished == true) &&
        (!hideOwned || item.ownership == null)
}

data class TasteRankingUiState(
    val loading: Boolean = true,
    /** 还没有可用的口味画像。 */
    val unavailable: Boolean = false,
    val refreshing: Boolean = false,
    /** 正在显示上次的结果，后台重新计算中。 */
    val updating: Boolean = false,
    val ranking: TasteRanking? = null,
    val filter: TasteRankingFilter = TasteRankingFilter(),
    /** 「补充作品库」的进度（应用级任务，离开页面也继续）。 */
    val expansion: PoolExpansion? = null,
    /** 作品库现状：能参与排行的部数、还缺资料的部数。 */
    val pool: LocalPoolStatus? = null,
    /** 筛选面板是否展开（默认收起）。 */
    val filtersExpanded: Boolean = false,
    /** 作品库说明卡是否已被关掉。 */
    val hintDismissed: Boolean = false,
    /** 一次性提示；显示后调用 onMessageShown。 */
    val message: String? = null,
) {
    /** 按筛选条件留下的前 [MAX_VISIBLE] 部。 */
    val visible: List<TasteRankItem>
        get() = ranking?.items.orEmpty().filter(filter::accepts).take(MAX_VISIBLE)

    companion object {
        const val MAX_VISIBLE = 100

        /** 「扩充作品库」一次取 Bangumi 排行前多少部。 */
        const val EXPAND_TARGET = 1000
    }
}

/**
 * 口味排行：打开时先显示上次的结果，后台按需重算（画像和作品库都没变就不重算），下拉重新计算；
 * 筛选只在已算好的结果上进行，不重算。
 */
@HiltViewModel
@Suppress("TooManyFunctions") // 每个筛选项、补充作品库各一个操作
class TasteRankingViewModel @Inject constructor(
    private val repository: TasteRankingRepository,
    private val settings: SettingsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(TasteRankingUiState())
    private val refreshGate = com.acgcompass.core.common.RefreshGate(minIntervalMillis = 0L)
    val state: StateFlow<TasteRankingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 先显示上次的结果，再在后台重算；算完有变化就整列换掉。
            runCatchingCancellable { repository.lastResult() }.getOrNull()?.let { last ->
                _state.update { it.copy(loading = false, ranking = last, updating = true) }
            }
            load(force = false)
            refreshPoolStatus()
        }
        viewModelScope.launch {
            settings.tasteRankHintDismissed.collect { dismissed ->
                _state.update {
                    it.copy(
                        hintDismissed = dismissed,
                    )
                }
            }
        }
        viewModelScope.launch {
            var wasRunning = false
            repository.expansion.collect { expansion ->
                _state.update { it.copy(expansion = expansion) }
                val finished = wasRunning && expansion?.running == false
                wasRunning = expansion?.running == true
                if (finished) onExpansionFinished(expansion)
            }
        }
    }

    /**
     * 下拉重新计算（只用本地作品库，不联网）。指示器最多转 3 秒；算得久时头部显示「正在更新」，
     * 算完提示一次，用户知道有没有重新算过。
     */
    fun onRefresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, updating = true) }
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val job = viewModelScope.async { runCatchingCancellable { repository.load(force = true) }.getOrNull() }
            refreshGate.awaitIndicator(job, startedAt)
            _state.update { it.copy(refreshing = false) }
            val ranking = job.await()
            _state.update {
                it.copy(
                    loading = false,
                    updating = false,
                    unavailable = ranking == null && it.ranking == null,
                    ranking = ranking ?: it.ranking,
                    message = if (ranking != null) {
                        "排行已重新计算（本地作品库 ${ranking.poolSize} 部，不联网）"
                    } else {
                        "这次没算出来，稍后再试"
                    },
                )
            }
            refreshPoolStatus()
        }
    }

    fun onToggleFormat(format: WorkFormat) = _state.update { state ->
        val formats = state.filter.formats.let { if (format in it) it - format else it + format }
        state.copy(filter = state.filter.copy(formats = formats))
    }

    fun onEraChange(era: EraFilter) = _state.update { it.copy(filter = it.filter.copy(era = era)) }

    fun onToggleFinishedOnly() = _state.update {
        it.copy(
            filter = it.filter.copy(finishedOnly = !it.filter.finishedOnly),
        )
    }

    fun onToggleHideOwned() = _state.update { it.copy(filter = it.filter.copy(hideOwned = !it.filter.hideOwned)) }

    /** 按 Bangumi 排行从上次补到的位置往后取 [TasteRankingUiState.EXPAND_TARGET] 部存进作品库。 */
    fun onExpandPool() = repository.startExpansion(TasteRankingUiState.EXPAND_TARGET)

    fun onToggleFilters() = _state.update { it.copy(filtersExpanded = !it.filtersExpanded) }

    fun onDismissHint() {
        viewModelScope.launch { settings.setTasteRankHintDismissed(true) }
    }

    private suspend fun onExpansionFinished(expansion: PoolExpansion) {
        load(force = false)
        refreshPoolStatus()
        val added = expansion.added ?: 0
        val message = when {
            expansion.failed -> "补充没完成，可能是网络问题，稍后再试"
            added > 0 -> "作品库新增 $added 部，排行已重新计算"
            else -> "排行第 ${expansion.startRank} 名起的作品已经都在作品库里了"
        }
        _state.update { it.copy(message = message) }
    }

    private suspend fun refreshPoolStatus() {
        val status = runCatchingCancellable { repository.poolStatus() }.getOrNull()
        _state.update { it.copy(pool = status) }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    private suspend fun load(force: Boolean) {
        val ranking = runCatchingCancellable { repository.load(force) }.getOrNull()
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                updating = false,
                unavailable = ranking == null && it.ranking == null,
                ranking = ranking ?: it.ranking,
            )
        }
    }
}

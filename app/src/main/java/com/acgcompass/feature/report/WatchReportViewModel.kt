package com.acgcompass.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.WatchReport
import com.acgcompass.domain.report.WatchReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchReportUiState(
    val periods: List<ReportPeriod> = ReportPeriod.choices(LocalDate.now()),
    val selected: ReportPeriod = periods.first(),
    val loading: Boolean = true,
    val report: WatchReport? = null,
    val template: ReportTemplate = ReportTemplate.WALL,
    val hideRatings: Boolean = false,
)

/** 年度 / 季度回顾：切换时间范围时重新统计（只读本地数据）。 */
@HiltViewModel
class WatchReportViewModel @Inject constructor(
    private val repository: WatchReportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WatchReportUiState())
    val state: StateFlow<WatchReportUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load(_state.value.selected)
    }

    fun onSelectPeriod(period: ReportPeriod) {
        if (period == _state.value.selected) return
        _state.update { it.copy(selected = period) }
        load(period)
    }

    fun onSelectTemplate(template: ReportTemplate) = _state.update { it.copy(template = template) }

    fun onToggleHideRatings() = _state.update { it.copy(hideRatings = !it.hideRatings) }

    private fun load(period: ReportPeriod) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val report = runCatching { repository.load(period) }.getOrNull()
            _state.update { it.copy(loading = false, report = report) }
        }
    }
}

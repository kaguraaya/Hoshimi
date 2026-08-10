package com.acgcompass.feature.visualnovel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.repository.VisualNovelRepository
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import com.acgcompass.domain.visualnovel.VisualNovelPage
import com.acgcompass.domain.visualnovel.VisualNovelSort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisualNovelUiState(
    val items: List<VisualNovelListItem> = emptyList(),
    val query: String = "",
    val sort: VisualNovelSort = VisualNovelSort.RATING,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val stale: Boolean = false,
    val refreshFailed: Boolean = false,
    val refreshedAt: Long? = null,
    val error: String? = null,
    val loadMoreError: String? = null,
)

@HiltViewModel
class VisualNovelViewModel @Inject constructor(
    private val repository: VisualNovelRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VisualNovelUiState())
    val uiState: StateFlow<VisualNovelUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var randomGeneration = 0L

    init {
        load(reset = true)
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        val normalized = value.trim()
        when {
            normalized.isEmpty() -> load(reset = true)
            normalized.length >= MIN_SEARCH_LENGTH -> {
                searchJob = viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    load(reset = true)
                }
            }
        }
    }

    fun submitSearch() {
        searchJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || query.length >= MIN_SEARCH_LENGTH) load(reset = true)
    }

    fun setSort(sort: VisualNovelSort) {
        if (_uiState.value.sort == sort && _uiState.value.query.isBlank()) return
        searchJob?.cancel()
        _uiState.update { it.copy(sort = sort, query = "") }
        load(reset = true)
    }

    fun retry() = load(reset = true, forceRefresh = true)

    fun refresh() {
        if (_uiState.value.sort == VisualNovelSort.RANDOM && _uiState.value.query.isBlank()) randomGeneration++
        load(reset = true, forceRefresh = true, silent = _uiState.value.items.isNotEmpty())
    }

    fun loadMore() {
        if (_uiState.value.hasMore && !_uiState.value.loadingMore) load(reset = false)
    }

    fun retryLoadMore() = load(reset = false, forceRefresh = true)

    private fun load(reset: Boolean, forceRefresh: Boolean = false, silent: Boolean = false) {
        if (reset) {
            loadJob?.cancel()
        } else if (loadJob?.isActive == true || _uiState.value.loadingMore) {
            return
        }
        loadJob = viewModelScope.launch {
            val targetPage = if (reset) 1 else _uiState.value.page + 1
            _uiState.update { it.beginLoad(reset, silent) }
            val refreshStaleCache = _uiState.applyResult(fetchPage(targetPage, forceRefresh), reset, forceRefresh)
            loadJob = null
            if (refreshStaleCache) load(reset = true, forceRefresh = true, silent = true)
        }
    }

    private suspend fun fetchPage(page: Int, forceRefresh: Boolean): AppResult<VisualNovelPage> {
        val state = _uiState.value
        val query = state.query.trim()
        return if (query.isEmpty()) {
            repository.featured(state.sort, page, PAGE_SIZE, forceRefresh, randomSeed())
        } else {
            repository.search(query, page, PAGE_SIZE, forceRefresh)
        }
    }

    private fun randomSeed(): Long = stableVisualNovelRandomSeed(LocalDate.now().toEpochDay(), randomGeneration)

    private companion object {
        const val PAGE_SIZE = 20
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DEBOUNCE_MILLIS = 500L
    }
}

private fun MutableStateFlow<VisualNovelUiState>.applyResult(
    result: AppResult<VisualNovelPage>,
    reset: Boolean,
    forceRefresh: Boolean,
): Boolean = when (result) {
    is AppResult.Success -> {
        update { state -> state.withPage(result.data, reset) }
        reset && result.data.fromCache && result.data.stale && !forceRefresh
    }
    is AppResult.Failure -> {
        update { state -> state.withFailure(result.error.nextStep, reset) }
        false
    }
}

private fun VisualNovelUiState.beginLoad(reset: Boolean, silent: Boolean): VisualNovelUiState = when {
    silent -> copy(refreshing = true, error = null)
    reset -> copy(loading = true, error = null)
    else -> copy(loadingMore = true, loadMoreError = null)
}

private fun VisualNovelUiState.withPage(page: VisualNovelPage, reset: Boolean) = copy(
    items = if (reset) page.items else (items + page.items).distinctBy { it.work.id },
    page = page.page,
    hasMore = page.hasMore,
    loading = false,
    refreshing = false,
    loadingMore = false,
    stale = page.stale,
    refreshFailed = page.refreshFailed,
    refreshedAt = page.refreshedAt,
    error = null,
    loadMoreError = null,
)

private fun VisualNovelUiState.withFailure(message: String?, reset: Boolean): VisualNovelUiState {
    val displayMessage = message ?: "加载失败，请稍后重试"
    return copy(
        loading = false,
        refreshing = false,
        loadingMore = false,
        error = displayMessage.takeIf { reset },
        loadMoreError = displayMessage.takeUnless { reset },
    )
}

internal fun stableVisualNovelRandomSeed(epochDay: Long, generation: Long): Long =
    epochDay * RANDOM_DAY_MULTIPLIER + generation

private const val RANDOM_DAY_MULTIPLIER = 1_000_000L

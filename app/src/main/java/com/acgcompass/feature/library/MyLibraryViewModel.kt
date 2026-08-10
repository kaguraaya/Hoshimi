package com.acgcompass.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的库 / 我的收藏」页 ViewModel，使用 MVVM、Hilt 和 StateFlow。
 *
 * 数据来源（单一可信源 = Room）：
 * - [UserCollectionDao.observeAll]：同步入库的用户收藏（我的库的主体）。
 * - [WorkRepository.observeWorks]：本地规范化作品，按 `localWorkId == work.id` 关联出封面 / 标题 / 类型 / 年份。
 * - [BacklogRepository.observeBacklog]：判定条目是否已在待补池（驱动「加入待补池」按钮）。
 *
 * 三流合并为 [MyLibraryData]（全部条目 + 各 Tab 计数），由 UI 按选中 Tab 过滤展示。
 * 初始 Tab 由导航参数（[LIBRARY_ARG_TAB]）注入。
 */
@HiltViewModel
class MyLibraryViewModel @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(
        LibraryStatusTab.fromKey(savedStateHandle.get<String>(LIBRARY_ARG_TAB)),
    )

    /** 当前选中的状态分组。 */
    val selectedTab: StateFlow<LibraryStatusTab> = _selectedTab.asStateFlow()

    val uiState: StateFlow<UiState<MyLibraryData>> =
        combine(
            userCollectionDao.observeAll(),
            workRepository.observeWorks(),
            backlogRepository.observeBacklog(),
        ) { collections, works, backlog ->
            val workById = works.associateBy { it.id }
            val backlogIds = backlog.map { it.workId }.toSet()
            val items = collections
                .sortedByDescending { it.syncedAt }
                .map { c ->
                    buildLibraryItem(
                        workId = c.localWorkId,
                        status = c.status,
                        rating = c.rating,
                        progress = c.progress,
                        source = c.source,
                        work = workById[c.localWorkId],
                        inBacklog = c.localWorkId in backlogIds,
                    )
                }
            MyLibraryData(items = items, counts = countByTab(items))
        }
            .map<MyLibraryData, UiState<MyLibraryData>> { UiState.Success(it) }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading,
            )

    /** 切换状态 Tab。 */
    fun onSelectTab(tab: LibraryStatusTab) {
        _selectedTab.value = tab
    }

    /** 把「想看」或「在看」条目加入待补池；要求对应本地作品已就绪。 */
    fun onAddToBacklog(workId: String) {
        viewModelScope.launch {
            val work = workRepository.observeWorks()
                .first()
                .firstOrNull { it.id == workId }
            if (work != null) {
                backlogRepository.addAll(listOf(work))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 计算各 Tab 的条目数（含「全部」）。 */
        fun countByTab(items: List<LibraryItem>): Map<LibraryStatusTab, Int> =
            LibraryStatusTab.entries.associateWith { tab ->
                if (tab.status == null) items.size else items.count { it.status == tab.status }
            }
    }
}

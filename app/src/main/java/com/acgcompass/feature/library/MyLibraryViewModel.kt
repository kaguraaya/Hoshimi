package com.acgcompass.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.PersonalCollectionRepository
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
 * - [WorkRepository.observeWorks]：本地规范化作品，按 `workId == work.id` 关联出封面 / 标题 / 类型 / 年份。
 * - [BacklogRepository.observeBacklog]：判定条目是否已在待补池（驱动「加入待补池」按钮）。
 *
 * 三流合并为 [MyLibraryData]（全部条目 + 各 Tab 计数），由 UI 按选中 Tab 过滤展示。
 * 初始 Tab 由导航参数（[LIBRARY_ARG_TAB]）注入。
 */
@HiltViewModel
class MyLibraryViewModel @Inject constructor(
    private val personalCollections: PersonalCollectionRepository,
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    private val syncManager: com.acgcompass.data.sync.BangumiSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)

    /** 下拉同步进行中。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 同步结果提示，显示一次后清掉。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 下拉同步 Bangumi 收藏：我的库就是 Bangumi 收藏的本地镜像，下拉期待的就是「和网页对齐」。
     * 双向按较新的一方合并（与「我的」页同步按钮相同），未登录时只提示。
     */
    fun onPullRefresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            // 同步可能要翻好几页；指示器最多转约 3 秒，同步在后台继续，结果用底部提示告知，列表随数据库更新。
            refreshGate.run(viewModelScope) {
                _message.value = if (!syncManager.isConfigured()) {
                    "还没有登录 Bangumi，登录后下拉即可同步收藏"
                } else {
                    when (val result = syncManager.syncCollections()) {
                        is com.acgcompass.core.common.AppResult.Success ->
                            "同步完成：新增 ${result.data.added} · 更新 ${result.data.updated}" +
                                (if (result.data.removed > 0) " · 移入回收站 ${result.data.removed}" else "")
                        is com.acgcompass.core.common.AppResult.Failure -> "同步失败：${result.error.cause}"
                    }
                }
            }
            _refreshing.value = false
        }
    }

    private val refreshGate = com.acgcompass.core.common.RefreshGate(minIntervalMillis = SYNC_PULL_INTERVAL_MS)

    fun consumeMessage() {
        _message.value = null
    }

    private val _selectedTab = MutableStateFlow(
        LibraryStatusTab.fromKey(savedStateHandle.get<String>(LIBRARY_ARG_TAB)),
    )

    /** 当前选中的状态分组。 */
    val selectedTab: StateFlow<LibraryStatusTab> = _selectedTab.asStateFlow()

    val uiState: StateFlow<UiState<MyLibraryData>> =
        combine(
            personalCollections.observeAll(),
            workRepository.observeWorks(),
            backlogRepository.observeBacklog(),
        ) { collections, works, backlog ->
            val workById = works.associateBy { it.id }
            val backlogIds = backlog.map { it.workId }.toSet()
            val items = collections
                .sortedByDescending { it.syncedAt }
                .map { c ->
                    buildLibraryItem(
                        workId = c.workId,
                        status = c.status,
                        rating = c.rating,
                        progress = c.progress,
                        source = c.source,
                        work = workById[c.workId],
                        inBacklog = c.workId in backlogIds,
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
        const val SYNC_PULL_INTERVAL_MS = 5_000L
        const val STOP_TIMEOUT_MS = 5_000L

        /** 计算各 Tab 的条目数（含「全部」）。 */
        fun countByTab(items: List<LibraryItem>): Map<LibraryStatusTab, Int> =
            LibraryStatusTab.entries.associateWith { tab ->
                if (tab.status == null) items.size else items.count { it.status == tab.status }
            }
    }
}

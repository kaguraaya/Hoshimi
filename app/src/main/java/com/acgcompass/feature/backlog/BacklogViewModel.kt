package com.acgcompass.feature.backlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DrawCriteria
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 待补池 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 职责：
 * - 持有筛选 [BacklogFilter] 与排序 [BacklogSort] 的可变状态；二者变化时通过 [flatMapLatest]
 *   重新订阅 [BacklogRepository.observeBacklog]，并与 [WorkRepository.observeWorks] 联结为
 *   可直接渲染的 [BacklogCardItem] 列表（统一作品卡片 ）。
 * - 暴露 `StateFlow<UiState<List<BacklogCardItem>>>`（七态 Page_State）。
 * - 优先级 / 备注编辑：委托仓库挂起方法，结果经 Room Flow 自动回流到 UI。
 *
 * 韧性：列表流的异常兜底为 [UiState.Error]，绝不让页面崩溃。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BacklogViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val dustStatusSync: com.acgcompass.domain.repository.DustStatusSync,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    /** 待补池展示形态（网格/列表）持久化偏好。 */
    val gridMode: StateFlow<Boolean> =
        settingsDataStore.backlogGridMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** 切换并持久化网格/列表形态。 */
    fun onToggleGridMode() {
        viewModelScope.launch { settingsDataStore.setBacklogGridMode(!gridMode.value) }
    }

    /** 把作品移出吃灰馆，并还原归档前的 Bangumi 状态。 */
    fun onRestoreFromDust(workId: String) {
        viewModelScope.launch { dustStatusSync.restore(workId) }
    }

    /** 吃灰馆专用——仅吃灰区条目，按加入时间倒序的卡片流（独立页面消费）。 */
    val dustMuseumCards: StateFlow<UiState<List<BacklogCardItem>>> =
        combine(
            backlogRepository.observeBacklog(
                filter = BacklogFilter(inDustMuseum = true),
                sort = BacklogSort.DUST_DAYS_DESC,
            ),
            workRepository.observeWorks(),
        ) { items, works ->
            val byId = works.associateBy { it.id }
            val cards = items.map { BacklogCardItem(it, byId[it.workId]) }
            if (cards.isEmpty()) UiState.Empty(DUST_EMPTY_CTA) else UiState.Success(cards)
        }.catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(UiState.Error(AppError.Server()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    // 待补池主列表默认排除吃灰馆条目（inDustMuseum=false），使「归档到吃灰馆」是真正的移动
    // 而非复制——吃灰条目只在独立吃灰馆页（dustMuseumCards）展示，不再同时滞留主列表。用户仍可用
    // 「吃灰状态」筛选切到「全部」(null) 或「仅吃灰」(true) 查看。
    private val _filter = MutableStateFlow(BacklogFilter(inDustMuseum = false))
    val filter: StateFlow<BacklogFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(BacklogSort.ADDED_DESC)
    val sort: StateFlow<BacklogSort> = _sort.asStateFlow()

    /** 多选模式开关。 */
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    /** 已选中的作品 id 集合。 */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /** 随机抽取结果；`null` 表示当前不显示结果弹窗。 */
    private val _drawResult = MutableStateFlow<DrawUiResult?>(null)
    val drawResult: StateFlow<DrawUiResult?> = _drawResult.asStateFlow()

    val uiState: StateFlow<UiState<List<BacklogCardItem>>> =
        combine(_filter, _sort) { f, s -> f to s }
            .flatMapLatest { (f, s) ->
                combine(
                    backlogRepository.observeBacklog(f, s),
                    workRepository.observeWorks(),
                ) { items, works ->
                    val byId = works.associateBy { it.id }
                    val cards = items.map { BacklogCardItem(it, byId[it.workId]) }
                    if (cards.isEmpty()) {
                        UiState.Empty(EMPTY_CTA)
                    } else {
                        UiState.Success(cards)
                    }
                }
            }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading,
            )

    // region 排序 / 筛选

    /** 设置排序方式。 */
    fun onSortSelected(sort: BacklogSort) {
        _sort.value = sort
    }

    /** 切换某优先级过滤（再次点击取消）。 */
    fun onTogglePriorityFilter(priority: Priority) {
        _filter.update { current ->
            val next = if (priority in current.priorities) {
                current.priorities - priority
            } else {
                current.priorities + priority
            }
            current.copy(priorities = next)
        }
    }

    /** 切换某媒介类型过滤（再次点击取消）。 */
    fun onToggleMediaTypeFilter(mediaType: MediaType) {
        _filter.update { current ->
            val next = if (mediaType in current.mediaTypes) {
                current.mediaTypes - mediaType
            } else {
                current.mediaTypes + mediaType
            }
            current.copy(mediaTypes = next)
        }
    }

    /** 在「全部 / 仅吃灰 / 仅非吃灰」之间切换吃灰博物馆过滤。 */
    fun onToggleDustMuseumFilter(value: Boolean?) {
        _filter.update { it.copy(inDustMuseum = value) }
    }

    /** 清除全部过滤条件，保留待补池默认排除吃灰馆条目的规则。 */
    fun onClearFilters() {
        _filter.value = BacklogFilter(inDustMuseum = false)
    }

    // endregion

    // region 编辑

    /** 设置某条目优先级。 */
    fun onSetPriority(workId: String, priority: Priority) {
        viewModelScope.launch { backlogRepository.setPriority(workId, priority) }
    }

    /** 设置 / 清除某条目自定义备注。 */
    fun onSetNote(workId: String, note: String?) {
        viewModelScope.launch { backlogRepository.setNote(workId, note) }
    }

    // endregion

    // region 多选与批量操作

    /** 进入 / 退出多选模式；退出时清空已选。 */
    fun onToggleSelectionMode() {
        val next = !_selectionMode.value
        _selectionMode.value = next
        if (!next) _selectedIds.value = emptySet()
    }

    /** 切换某条目的选中状态（再次点击取消）。 */
    fun onToggleSelect(workId: String) {
        _selectedIds.update { current ->
            if (workId in current) current - workId else current + workId
        }
    }

    /** 清空已选条目。 */
    fun onClearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * 对当前已选条目执行批量操作。操作完成后清空选择并退出多选模式；
     * 结果经 Room Flow 自动回流到 UI。空选择时为无操作。
     */
    fun onBulk(op: BulkOp) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            when (op) {
                // 吃灰归档与移出需联动 Bangumi 状态，并通过专用路径保存或还原 prevStatus。
                BulkOp.ARCHIVE_TO_DUST_MUSEUM -> ids.forEach { dustStatusSync.archive(it) }
                BulkOp.RESTORE_FROM_DUST_MUSEUM -> ids.forEach { dustStatusSync.restore(it) }
                else -> backlogRepository.bulk(op, ids)
            }
            _selectedIds.value = emptySet()
            _selectionMode.value = false
        }
    }

    // endregion

    // region 一键抽番

    /**
     * 一键抽番：从待补池中按硬过滤约束抽取一部作品并给出可解释理由（非纯随机）。
     * 结果写入 [drawResult] 供 UI 弹窗展示。
     */
    fun onDraw() {
        viewModelScope.launch {
            val result = backlogRepository.draw(DrawCriteria())
            // 用 workId 反查本地作品名，弹窗展示作品标题而非裸 id。
            val pickId = result.pick?.workId
            val title = pickId?.let { id ->
                runCatching {
                    workRepository.observeWorks().first().firstOrNull { it.id == id }?.titles?.canonical
                }.getOrNull()
            }
            _drawResult.value = DrawUiResult(workId = pickId, title = title, reason = result.reason)
        }
    }

    /** 关闭抽番结果弹窗。 */
    fun onDismissDraw() {
        _drawResult.value = null
    }

    // endregion

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val EMPTY_CTA = Cta(label = "去导入 / 搜索添加", action = "import")
        val DUST_EMPTY_CTA = Cta(label = "吃灰馆还是空的", action = "backlog")
    }
}

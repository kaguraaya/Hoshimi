package com.acgcompass.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页——今日决策中心 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 把待补条目（[BacklogRepository.observeBacklog]）与本地作品（[WorkRepository.observeWorks]）
 * 联结为 [HomeBacklogEntry]，再经纯函数 [buildBacklogSummary] 折叠为待补池概览，对外暴露
 * `StateFlow<UiState<HomeUiState>>`（七态 Page_State）。
 *
 * 待补池为空时发射 [UiState.Empty]（携带导入引导 CTA）。
 * 同步提醒由 [CredentialStore.observeStatus] 折叠（仅元数据，无明文）；今日补番签
 * 由纯函数 [buildDailyDraw] 依当日日期 + 待补池规模确定性生成。
 *
 * 韧性：列表流异常兜底为 [UiState.Error]，绝不崩溃。
 */
@HiltViewModel
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val credentialStore: CredentialStore,
    private val bangumiSyncManager: BangumiSyncManager,
    private val syncStatusRepository: SyncStatusRepository,
    private val settingsDataStore: com.acgcompass.data.datastore.SettingsDataStore,
    private val userCollectionDao: com.acgcompass.data.local.dao.UserCollectionDao,
    private val scheduleRepository: BroadcastScheduleRepository,
) : ViewModel() {

    private val scheduleRefreshState = MutableStateFlow(HomeScheduleRefreshState(refreshing = true))
    private var lastScheduleRefreshAttemptAt = 0L

    init {
        // 首页公共模块进入时按需加载一次；失败时保留本地缓存并显示中性提示。
        viewModelScope.launch { runCatching { workRepository.loadPublicDiscovery(background = true) } }
        onHomeVisible()
    }

    private val _syncMessage = MutableStateFlow<String?>(null)

    /** 同步结果一次性提示；`需配置` 时提示去设置。消费后调用 [clearSyncMessage]。 */
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _needsBangumiConfig = MutableStateFlow(false)

    /** 触发后置 true，提示界面跳转设置页 Bangumi 配置；消费后置回 false。 */
    val needsBangumiConfig: StateFlow<Boolean> = _needsBangumiConfig.asStateFlow()

    private val scheduleFlow = combine(
        scheduleRepository.observeUpcoming(limit = 6),
        scheduleRefreshState,
    ) { upcoming, refresh -> upcoming to refresh }

    val uiState: StateFlow<UiState<HomeUiState>> =
        combine(
            backlogRepository.observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC),
            workRepository.observeWorks(),
            credentialStore.observeStatus(),
            combine(
                syncStatusRepository.status,
                settingsDataStore.homeModules,
                userCollectionDao.observeAll(),
            ) { s, m, collections -> Triple(s, m, collections) },
            scheduleFlow,
        ) { items, works, statuses, syncModulesCollections, schedule ->
            val upcoming = schedule.first
            val scheduleState = schedule.second
            val syncStatus = syncModulesCollections.first
            val enabledModules = syncModulesCollections.second
            val collections = syncModulesCollections.third
            val worksById = works.associateBy { it.id }
            val entries = items.map { HomeBacklogEntry(it, worksById[it.workId]) }
            UiState.Success<HomeUiState>(
                HomeUiState(
                    continueItems = buildContinueItems(collections, worksById),
                    backlogSummary = buildBacklogSummary(entries),
                    syncReminder = buildSyncReminder(statuses, System.currentTimeMillis(), syncStatus),
                    dailyDraw = buildDailyDraw(backlogSize = entries.size),
                    enabledModules = enabledModules,
                    seasonalHot = buildSeasonalHot(works),
                    seasonalNew = buildSeasonalNew(works),
                    upcomingBroadcasts = upcoming,
                    scheduleRefreshing = scheduleState.refreshing,
                    scheduleError = scheduleState.errorMessage,
                ),
            ) as UiState<HomeUiState>
        }.catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(UiState.Error(AppError.Server()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    /**
     * 手动同步入口。已配置 Bangumi 时调用 [BangumiSyncManager] 拉取入库，
     * 成功后刷新同步提醒（自增触发器）并提示统计；未配置 → 提示去设置；失败 → 显示原因。
     */
    fun onManualSync() {
        viewModelScope.launch {
            try {
                if (!bangumiSyncManager.isConfigured()) {
                    _needsBangumiConfig.value = true
                    _syncMessage.value = "未配置 Bangumi 账号，请到设置登录后再同步"
                    return@launch
                }
                _syncMessage.value = "正在从 Bangumi 同步…"
                when (val r = bangumiSyncManager.syncCollections()) {
                    is AppResult.Success -> {
                        val rep = r.data
                        _syncMessage.value =
                            "同步完成：新增 ${rep.added} · 更新 ${rep.updated} · 跳过 ${rep.skipped} · 失败 ${rep.failed}"
                        // 同步状态由 SyncStatusRepository 写入，uiState 的 combine 会自动刷新提醒。
                    }
                    is AppResult.Failure ->
                        _syncMessage.value = "同步失败：${r.error.cause}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _syncMessage.value = "同步失败，请稍后再试"
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun clearNeedsBangumiConfig() {
        _needsBangumiConfig.value = false
    }

    /** Refreshes on first display and after returning home; repository TTL prevents unnecessary requests. */
    fun onHomeVisible() {
        val now = System.currentTimeMillis()
        if (now - lastScheduleRefreshAttemptAt < SCHEDULE_RETRY_GUARD_MS) return
        lastScheduleRefreshAttemptAt = now
        scheduleRefreshState.value = HomeScheduleRefreshState(refreshing = true)
        viewModelScope.launch {
            val result = runCatching { scheduleRepository.refreshUpcoming() }
                .getOrElse { Result.failure(it) }
            scheduleRefreshState.value = HomeScheduleRefreshState(
                refreshing = false,
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SCHEDULE_RETRY_GUARD_MS = 15_000L
    }
}

private data class HomeScheduleRefreshState(
    val refreshing: Boolean,
    val errorMessage: String? = null,
)

package com.acgcompass.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.NetworkJson
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.DailyDrawOutput
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.repository.AiRepository
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * 由纯函数 [buildDailyDraw] 依当日日期 + 待补池规模确定性生成。打开「今日补番签由 AI 生成」且配置了 AI 时，
 * 每天用 AI 结合待补池生成一次（按日期缓存），失败时仍显示内置的签。
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
    private val aiEngine: AiEngine,
    private val aiRepository: AiRepository,
) : ViewModel() {

    /** AI 生成的今日补番签；为 null 时用内置的签。 */
    private val aiDraw = MutableStateFlow<DailyDraw?>(null)

    private val scheduleRefreshState = MutableStateFlow(HomeScheduleRefreshState(refreshing = true))

    /** 「今晚想看什么类型」的随机种子：同一次浏览里不跳动，重新进入首页会换一批。 */
    private val moodSeed = kotlin.random.Random.nextLong()
    private var lastScheduleRefreshAttemptAt = 0L

    init {
        // 首页公共模块进入时按需加载一次；失败时保留本地缓存并显示中性提示。
        viewModelScope.launch { runCatching { workRepository.loadPublicDiscovery(background = true) } }
        onHomeVisible()
        viewModelScope.launch {
            combine(
                settingsDataStore.aiDailyDraw,
                credentialStore.observeStatus().map { it[CredentialSourceId.AI_PROVIDER]?.configured == true },
            ) { enabled, configured -> enabled && configured }
                .distinctUntilChanged()
                .collectLatest { on -> aiDraw.value = if (on) runCatching { loadAiDraw() }.getOrNull() else null }
        }
    }

    /** 读当天的缓存；没有就用 AI 生成一次并缓存。待补池为空或失败时返回 null。 */
    private suspend fun loadAiDraw(): DailyDraw? {
        val today = LocalDate.now()
        val fingerprint = aiDailyDrawFingerprint(today)
        val cached = (aiRepository.getCached(AiTask.GLOBAL_WORK_ID, AiTaskType.DAILY_DRAW) as? AppResult.Success)?.data
        val reusable = cached?.takeIf {
            it.inputFingerprint == fingerprint && it.promptVersion == AI_DAILY_DRAW_PROMPT_VERSION
        }
        return if (reusable != null) parseAiDraw(reusable.payloadJson) else generateAiDraw(today, fingerprint)
    }

    private suspend fun generateAiDraw(today: LocalDate, fingerprint: String): DailyDraw? {
        val items = backlogRepository.observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC).first()
        val worksById = workRepository.observeWorks().first().associateBy { it.id }
        val entries = items.map { HomeBacklogEntry(it, worksById[it.workId]) }
        val result = if (entries.any { it.work != null }) {
            aiEngine.run(
                AiTask.DailyDraw(buildAiDailyDrawInput(today, entries, System.currentTimeMillis())),
                AiRunOptions(confirmed = true, temperature = AI_DRAW_TEMPERATURE),
            )
        } else {
            null
        }
        val success = result as? AiRunResult.Success
        val draw = success?.payload?.toDailyDraw()
        if (success != null && draw != null) {
            aiRepository.put(
                success.result.copy(inputFingerprint = fingerprint, promptVersion = AI_DAILY_DRAW_PROMPT_VERSION),
            )
        }
        return draw
    }

    private fun parseAiDraw(payloadJson: String): DailyDraw? = runCatching {
        NetworkJson.instance.decodeFromString(DailyDrawOutput.serializer(), payloadJson)
    }.getOrNull()?.toDailyDraw()

    private fun DailyDrawOutput.toDailyDraw(): DailyDraw? {
        val yes = should.trim().removePrefix("宜").trim(' ', '：', ':')
        val no = shouldNot.trim().removePrefix("忌").trim(' ', '：', ':')
        return if (yes.isBlank() || no.isBlank()) null else DailyDraw(yes, no, byAi = true)
    }

    private val _syncMessage = MutableStateFlow<String?>(null)

    /** 同步结果一次性提示；`需配置` 时提示去设置。消费后调用 [clearSyncMessage]。 */
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    /** 下拉刷新进行中。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val refreshGate = com.acgcompass.core.common.RefreshGate()

    private val _needsBangumiConfig = MutableStateFlow(false)

    /** 触发后置 true，提示界面跳转设置页 Bangumi 配置；消费后置回 false。 */
    val needsBangumiConfig: StateFlow<Boolean> = _needsBangumiConfig.asStateFlow()

    private val scheduleFlow = combine(
        scheduleRepository.observeUpcoming(limit = 6),
        scheduleRefreshState,
        aiDraw,
    ) { upcoming, refresh, draw -> Triple(upcoming, refresh, draw) }

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
            val aiDailyDraw = schedule.third
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
                    dailyDraw = aiDailyDraw ?: buildDailyDraw(backlogSize = entries.size),
                    moods = buildMoodSuggestions(
                        collections.map { MoodSignal(it.localWorkId, it.status, it.rating, it.updatedAt) },
                        worksById,
                        kotlin.random.Random(moodSeed),
                    ),
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

    /**
     * 下拉刷新：重新拉取近期放送和本季公共资料（忽略缓存时效）。不同步 Bangumi 收藏——那是「我的」里的同步按钮
     * 和「我的库」下拉做的事，首页下拉只刷新首页看得到的公共内容。
     */
    fun onPullRefresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            refreshGate.run(viewModelScope) {
                // 本季公共资料可能排在后台缓存补充后面，不让指示器等它。
                viewModelScope.launch { runCatching { workRepository.loadPublicDiscovery(background = false) } }
                val result = runCatching { scheduleRepository.refreshUpcoming(force = true) }
                    .getOrElse { Result.failure(it) }
                scheduleRefreshState.value = HomeScheduleRefreshState(
                    refreshing = false,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
            _refreshing.value = false
        }
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

        /** AI 补番签的采样温度：比结构化任务高一点，让每天的签有些变化。 */
        const val AI_DRAW_TEMPERATURE = 0.8
        const val SCHEDULE_RETRY_GUARD_MS = 15_000L
    }
}

private data class HomeScheduleRefreshState(
    val refreshing: Boolean,
    val errorMessage: String? = null,
)

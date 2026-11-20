package com.acgcompass.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.logging.RedactingLogger
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.common.throttleLatest
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
import com.acgcompass.domain.repository.HomeSeasonal
import com.acgcompass.domain.repository.HomeSeasonalLists
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
@Suppress("LongParameterList", "TooManyFunctions")
class HomeViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val credentialStore: CredentialStore,
    private val bangumiSyncManager: BangumiSyncManager,
    private val syncStatusRepository: SyncStatusRepository,
    private val settingsDataStore: com.acgcompass.data.datastore.SettingsDataStore,
    private val personalCollections: com.acgcompass.domain.repository.PersonalCollectionRepository,
    private val scheduleRepository: BroadcastScheduleRepository,
    private val aiEngine: AiEngine,
    private val aiRepository: AiRepository,
    private val tasteEngine: com.acgcompass.data.taste.TasteEngine,
) : ViewModel() {

    /** AI 生成的今日补番签；为 null 时用内置的签。 */
    private val aiDraw = MutableStateFlow<DailyDraw?>(null)

    private val scheduleRefreshState = MutableStateFlow(HomeScheduleRefreshState(refreshing = true))

    /** 首页本季新番 / 热门（按存下的顺序）；null 表示还没读过缓存。 */
    private val seasonalLists = MutableStateFlow<HomeSeasonal?>(null)

    /** 本季新番这次显示的几部（从热门和可能喜欢的里随机挑）。 */
    private val seasonalPick = MutableStateFlow<SeasonalPick?>(null)

    /** 最近显示过的本季新番，下拉时先换成没显示过的。 */
    private val shownSeasonal = mutableSetOf<String>()

    /** 本季作品的口味匹配度（作品 id → 分数），后台算好后存在这里，挑作品时直接用。 */
    private var seasonalMatches: Map<String, Int> = emptyMap()

    /** 「今晚想看什么类型」的随机种子：同一次浏览里不跳动，重新进入首页会换一批。 */
    private val moodSeed = kotlin.random.Random.nextLong()
    private var lastScheduleRefreshAttemptAt = 0L
    private val logger = RedactingLogger()

    init {
        // 首页公共模块进入时按需加载一次；失败时保留本地缓存并显示中性提示。
        viewModelScope.launch { runCatchingCancellable { workRepository.loadPublicDiscovery(background = true) } }
        viewModelScope.launch {
            // 先显示上次存下的列表，再在后台取一次（一小时内走 HTTP 缓存）。
            loadSeasonalLists()
            repickSeasonal()
            refreshSeasonal(fresh = false)
        }
        onHomeVisible()
        viewModelScope.launch {
            combine(
                settingsDataStore.aiDailyDraw,
                credentialStore.observeStatus().map { it[CredentialSourceId.AI_PROVIDER]?.configured == true },
            ) { enabled, configured -> enabled && configured }
                .distinctUntilChanged()
                .collectLatest { on ->
                    aiDraw.value = if (on) runCatchingCancellable { loadAiDraw() }.getOrNull() else null
                }
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

    private fun parseAiDraw(payloadJson: String): DailyDraw? = runCatchingCancellable {
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
        seasonalLists,
        seasonalPick,
    ) { upcoming, refresh, draw, seasonal, pick -> HomeExtras(upcoming, refresh, draw, seasonal, pick) }

    /** 自增后重新订阅首页数据：读取出错后下拉或点「重试」时用。 */
    private val restartSignal = MutableStateFlow(0)

    /** 上一次成功的首页数据：上游偶发出错时继续显示它，不整页换成错误。 */
    @Volatile
    private var lastHome: UiState<HomeUiState>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<UiState<HomeUiState>> = restartSignal
        .flatMapLatest { homeFlow().resilient() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    /**
     * 首页数据：多张表和设置合在一起。任何一路出错先按 1 / 2 / 4 秒重试三次；仍失败时保留上次的内容并提示，
     * 从没成功过才显示错误页。原始异常写进日志。
     */
    private fun homeFlow(): Flow<UiState<HomeUiState>> = combine(
        backlogRepository.observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC),
        // 后台补资料时作品表一秒内会变很多次，首页只按最新数据重算。
        workRepository.observeWorks().throttleLatest(WORKS_THROTTLE_MS),
        credentialStore.observeStatus(),
        combine(
            syncStatusRepository.status,
            settingsDataStore.homeModules,
            personalCollections.observeAll(),
        ) { s, m, collections -> Triple(s, m, collections) },
        scheduleFlow,
    ) { items, works, statuses, syncModulesCollections, schedule ->
        val upcoming = schedule.upcoming
        val scheduleState = schedule.refresh
        val aiDailyDraw = schedule.draw
        val seasonal = schedule.seasonal
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
                    collections.map { MoodSignal(it.workId, it.status, it.rating, it.updatedAt) },
                    worksById,
                    kotlin.random.Random(moodSeed),
                ),
                enabledModules = enabledModules,
                seasonalHot = seasonal?.hot?.takeIf { it.isNotEmpty() }?.let(::buildSeasonalCards)
                    ?: buildSeasonalHot(works),
                seasonalNew = schedule.pick?.items?.takeIf { it.isNotEmpty() }
                    ?.let { buildSeasonalCards(it, matches = schedule.pick.matches) }
                    ?: buildSeasonalNew(works),
                seasonalUpdatedAt = seasonal?.fetchedAt,
                upcomingBroadcasts = upcoming,
                scheduleRefreshing = scheduleState.refreshing,
                scheduleError = scheduleState.errorMessage,
            ),
        ) as UiState<HomeUiState>
    }

    /** 记下成功的内容；出错先重试，仍失败时保留上次的内容并提示，从没成功过才显示错误页。 */
    private fun Flow<UiState<HomeUiState>>.resilient(): Flow<UiState<HomeUiState>> = onEach { lastHome = it }
        .retryWhen { cause, attempt ->
            val retry = cause !is CancellationException && attempt < HOME_RETRY_LIMIT
            if (retry) {
                logger.w(TAG, "首页数据读取失败，准备重试", cause)
                delay(HOME_RETRY_BASE_MS shl attempt.toInt())
            }
            retry
        }
        .catch { cause ->
            if (cause is CancellationException) throw cause
            logger.e(TAG, "首页数据读取失败", cause)
            val last = lastHome
            if (last != null) {
                emit(last)
                _syncMessage.value = "首页有部分数据暂时没读到，下拉可以重新读取"
            } else {
                emit(UiState.Error(AppError.Server(cause = "首页数据读取失败", nextStep = "下拉或点重试重新读取")))
            }
        }

    /** 错误页的「重试」：重新订阅首页数据。 */
    fun onRetry() {
        restartSignal.update { it + 1 }
    }

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
                            "同步完成：新增 ${rep.added} · 更新 ${rep.updated} · 跳过 ${rep.skipped} · 失败 ${rep.failed}" +
                            rep.incompleteNote.orEmpty()
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
            val startedAt = System.currentTimeMillis()
            // 本季新番在本地已有的本季列表里马上换一批：看得到的变化立刻出现，不等网络，也不受 30 秒节流限制。
            val local = seasonalLists.value?.takeUnless { it.isEmpty }
            if (local != null) {
                repickSeasonal()
                _syncMessage.value = "本季新番换了一批"
            }
            // 联网部分：近期放送（跳过 HTTP 缓存）和过期了的本季列表，前台优先级、并行；30 秒内重复下拉不再联网。
            var networkOk = true
            val job = refreshGate.launch(viewModelScope) {
                if (uiState.value is UiState.Error) onRetry()
                val seasonal = async { refreshSeasonal(fresh = true, alreadyRepicked = local != null) }
                val result = runCatchingCancellable { scheduleRepository.refreshUpcoming(force = true) }
                    .getOrElse { Result.failure(it) }
                scheduleRefreshState.value = HomeScheduleRefreshState(
                    refreshing = false,
                    errorMessage = result.exceptionOrNull()?.message,
                )
                networkOk = seasonal.await() && result.isSuccess
            }
            refreshGate.awaitIndicator(job, startedAt)
            _refreshing.value = false
            when {
                // 指示器转满还没取完：说清楚还在取，取完再提示一次，免得以为刷新卡住了。
                job != null && job.isActive -> {
                    _syncMessage.value = "还在从 Bangumi 取最新的放送和评分，取到后会自动更新"
                    job.join()
                    _syncMessage.value = if (networkOk) "最新的放送和评分已经取到" else "有一部分没取到，先显示已有的数据"
                }
                job == null && local == null -> _syncMessage.value = "刚刷新过，过一会儿再下拉"
            }
        }
    }

    private suspend fun loadSeasonalLists() {
        seasonalLists.value = runCatchingCancellable {
            HomeSeasonal(
                new = workRepository.getCachedRanking(HomeSeasonalLists.NEW),
                hot = workRepository.getCachedRanking(HomeSeasonalLists.HOT),
            )
        }.getOrNull() ?: HomeSeasonal(emptyList(), emptyList())
    }

    /**
     * 本季列表联网更新（返回是否成功或不需要更新）：列表超过 [SEASONAL_STALE_MS] 没更新（或还没取过）才去 Bangumi 取，取到后只更新列表和已显示作品的评分，
     * 不再突然换一批。下拉时本地已经先换过一批（[alreadyRepicked]）；本地还没有列表时取到后再挑。打开首页时也是先显示上次的列表，再在后台取。
     */
    private suspend fun refreshSeasonal(fresh: Boolean, alreadyRepicked: Boolean = false): Boolean {
        val local = seasonalLists.value?.takeUnless { it.isEmpty }
        val stale = local?.fetchedAt?.let { System.currentTimeMillis() - it > SEASONAL_STALE_MS } ?: true
        if (fresh && alreadyRepicked && !stale) return true
        var fetched = false
        val result = runCatchingCancellable {
            workRepository.refreshHomeSeasonal(fresh) { lists ->
                if (lists.isEmpty) return@refreshHomeSeasonal
                seasonalLists.value = lists
                fetched = true
                if (seasonalPick.value?.items.isNullOrEmpty()) repickSeasonal() else refreshPickedRatings(lists)
            }
        }.getOrNull()
        if (fresh && !alreadyRepicked) {
            if (!fetched) repickSeasonal()
            _syncMessage.value = if (fetched || result is AppResult.Success) {
                "本季新番换了一批"
            } else {
                "这次没取到最新数据，先从已有的作品里换了一批"
            }
        }
        return fetched || result is AppResult.Success
    }

    /**
     * 从本季列表里重新随机挑一批（热度前一半 + 口味匹配度 ≥ 72%），先挑最近没显示过的。
     * 用上次算好的口味匹配度马上挑，不等重新计算（算匹配度要读资料、可能和后台任务抢资源，会让「换一批」慢好几秒）；
     * 新的匹配度在后台算好后存下，下次挑的时候用，这一批不再变。
     */
    private fun repickSeasonal() {
        val lists = seasonalLists.value ?: return
        val candidates = (lists.new + lists.hot).distinctBy { it.first.id }
        if (candidates.isEmpty()) return
        val picked = pickSeasonal(candidates, seasonalMatches, kotlin.random.Random.Default, exclude = shownSeasonal)
        val ids = picked.map { it.first.id }
        // 能挑的都显示过了就重新开始记。
        if (ids.any { it in shownSeasonal }) shownSeasonal.clear()
        shownSeasonal += ids
        seasonalPick.value = SeasonalPick(picked, seasonalMatches)
        val missing = candidates.map { it.first.id }.filterNot { it in seasonalMatches }
        if (missing.isNotEmpty()) viewModelScope.launch { updateSeasonalMatches(missing) }
    }

    private suspend fun updateSeasonalMatches(ids: List<String>) {
        val fresh = runCatchingCancellable {
            tasteEngine.scoreBatch(ids, networkBudget = 0).filterValues { !it.isRated }.mapValues { it.value.score }
        }.getOrDefault(emptyMap())
        if (fresh.isEmpty()) return
        seasonalMatches = seasonalMatches + fresh
        seasonalPick.value = seasonalPick.value?.copy(matches = seasonalMatches)
    }

    /** 已显示的那几部换成刚取到的评分，作品不变。 */
    private fun refreshPickedRatings(lists: HomeSeasonal) {
        val fresh = (lists.new + lists.hot).associate { it.first.id to it.second }
        seasonalPick.value = seasonalPick.value?.let { pick ->
            pick.copy(items = pick.items.map { (work, rating) -> work to (fresh[work.id] ?: rating) })
        }
    }

    /** Refreshes on first display and after returning home; repository TTL prevents unnecessary requests. */
    fun onHomeVisible() {
        val now = System.currentTimeMillis()
        if (now - lastScheduleRefreshAttemptAt < SCHEDULE_RETRY_GUARD_MS) return
        lastScheduleRefreshAttemptAt = now
        scheduleRefreshState.value = HomeScheduleRefreshState(refreshing = true)
        viewModelScope.launch {
            val result = runCatchingCancellable { scheduleRepository.refreshUpcoming() }
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
        const val WORKS_THROTTLE_MS = 500L
        const val HOME_RETRY_LIMIT = 3L
        const val HOME_RETRY_BASE_MS = 1_000L
        const val TAG = "Home"

        /** 本季列表多久没更新才在下拉时去 Bangumi 重新取（本季作品不多，评分变化也慢）。 */
        const val SEASONAL_STALE_MS = 3 * 60 * 60 * 1000L
    }
}

private data class HomeScheduleRefreshState(
    val refreshing: Boolean,
    val errorMessage: String? = null,
)

private data class HomeExtras(
    val upcoming: List<com.acgcompass.domain.schedule.BroadcastScheduleEntry>,
    val refresh: HomeScheduleRefreshState,
    val draw: DailyDraw?,
    val seasonal: HomeSeasonal?,
    val pick: SeasonalPick?,
)

/** 本季新番这次显示的几部和它们的口味匹配度。 */
private data class SeasonalPick(
    val items: List<Pair<com.acgcompass.domain.model.Work, com.acgcompass.domain.model.RatingEntry?>>,
    val matches: Map<String, Int>,
)

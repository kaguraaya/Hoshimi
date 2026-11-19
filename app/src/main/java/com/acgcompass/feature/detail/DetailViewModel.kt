package com.acgcompass.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.RadarCachePolicy
import com.acgcompass.domain.ai.RadarSummarySource
import com.acgcompass.domain.ai.SpoilerLevel
import com.acgcompass.domain.ai.SpoilerRadarOutput
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.ai.VndbTranslationCachePolicy
import com.acgcompass.domain.ai.VndbTranslationOutput
import com.acgcompass.domain.detail.BangumiRecordUpdate
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AiRepository
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.repository.DetailRepository
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.VisualNovelRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TasteAnalysisController
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.toFallbackFeature
import com.acgcompass.domain.usecase.GenerateSpoilerRadarUseCase
import com.acgcompass.domain.usecase.RadarOutcome
import com.acgcompass.domain.usecase.RadarRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 作品详情页 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 数据来源（单一可信源 = Room）：
 * - [WorkRepository.observeWork]：观察规范化作品，提供顶部信息区数据并贡献七态 [UiState]
 *   （加载 / 空 / 错误 / 字段缺失 / 成功 …）。
 * - [WorkRepository.aggregateRatings]：聚合多平台评分（内部经 [com.acgcompass.domain.usecase.AggregateRatingsUseCase]
 *   计算，缺失源标记 `missing`、样本不足 `consensus=null`），提供评分区与社区共识卡数据。
 * - [BacklogRepository.observeBacklog]：判定该作品是否已在待补池（驱动个人区「加入 / 移出待补池」按钮）。
 * - [TasteProfileRepository.observeTasteProfile]：口味画像；未生成时决策区口味匹配度显示「暂无数据」。
 *
 * 这些流合并：作品流决定页面整体状态；评分 / 待补 / 口味为「尽力而为」的附加数据——尚未就绪或拉取失败时，
 * 不阻塞作品展示（评分区按平台显示「暂无数据」、共识低置信）。
 *
 * `workId` 由导航参数经 [SavedStateHandle] 注入（[DETAIL_ARG_WORK_ID]）。
 */
@HiltViewModel
@Suppress("LongParameterList")
class DetailViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val visualNovelRepository: VisualNovelRepository,
    private val detailRepository: DetailRepository,
    private val aiRepository: AiRepository,
    private val backlogRepository: BacklogRepository,
    private val tasteProfileRepository: TasteProfileRepository,
    private val generateSpoilerRadar: GenerateSpoilerRadarUseCase,
    private val aiEngine: AiEngine,
    private val detailInteractions: DetailInteractionRepository,
    private val tasteAnalysis: TasteAnalysisController,
    private val json: Json,
    private val undoController: com.acgcompass.domain.undo.UndoController,
    private val trashRepository: com.acgcompass.domain.repository.TrashRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val workId: String = savedStateHandle.get<String>(DETAIL_ARG_WORK_ID).orEmpty()

    /**
     * 解析作品的**真实 Bangumi subjectId**（绝不把 Jikan/AniList 的裸数字 work.id 当成 subjectId）。
     * - 作品主源即 Bangumi → work.id 即 subjectId。
     * - 否则查 Bangumi 源链接（交叉验证后落库）取 sourceItemId。
     * - 都没有 → 返回 null（该作品在 Bangumi 无对应词条，不回写，避免污染无关条目）。
     */
    private suspend fun resolveBangumiSubjectId(work: Work): Int? {
        return detailInteractions.resolveBangumiSubjectId(work)
    }

    /** 编辑「我的记录」并回写 Bangumi 的结果提示（Snackbar 文案）；消费后置 null。 */
    private val _recordMessage = MutableStateFlow<String?>(null)
    val recordMessage: StateFlow<String?> = _recordMessage

    /** 这部作品在回收站里的记录（待补池条目 / 收藏）；不在回收站时为空列表。 */
    val trashEntries: StateFlow<List<com.acgcompass.domain.model.TrashEntry>> = trashRepository
        .observeWork(workId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** 把这部作品在回收站里的记录全部恢复（原来的加入时间、状态、评分不变）。 */
    fun onRestoreFromTrash() {
        viewModelScope.launch {
            val restored = trashEntries.value.count { trashRepository.restore(it.id) }
            _recordMessage.value = if (restored > 0) "已从回收站恢复" else "这部作品已经重新加入，回收站里的旧记录已丢弃"
        }
    }

    /** 消费一次性提示。 */
    fun consumeRecordMessage() {
        _recordMessage.value = null
    }

    /** AI 匹配度分析按钮状态；初始为 [AiMatchUi.Idle]。 */
    private val _aiMatch = MutableStateFlow<AiMatchUi>(AiMatchUi.Idle)
    val aiMatch: StateFlow<AiMatchUi> = _aiMatch

    /** 详情页角色·Staff / 关联作品 / 观看路线（来自各源端点拉取后折叠的可读正文）。 */
    private val detailExtras = MutableStateFlow(DetailExtras())

    /** 当前系列的 Bangumi subject id，供批量加入待补池。 */
    @Volatile
    private var seriesSubjectIds: List<Int> = emptyList()

    /** 我的个人记录（来自同步入库的用户收藏）；`null` 表示未记录 → 个人区显示「暂无数据」。 */
    private val myCollection: StateFlow<CollectionState?> =
        detailInteractions.observePersonalRecord(workId)
            .map { record ->
                record?.let {
                    CollectionState(
                        workId = it.workId,
                        status = it.status,
                        rating = it.rating,
                        shortReview = it.comment,
                        progress = it.progress,
                        tags = it.tags,
                        isPrivate = it.isPrivate,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    /** 评分聚合结果（附加数据流）；`null` 表示尚未加载 / 拉取失败 → 评分区显示「暂无数据」。 */
    private val ratings = MutableStateFlow<RatingAggregate?>(null)

    /**
     * 无剧透评价雷达；`null` 表示尚未生成 / 需成本确认 → 决策区显示「暂无数据」，
     * 绝不伪造。未配置 AI 时由 [GenerateSpoilerRadarUseCase] 自动回退到本地规则版（generator=RULE）。
     */
    private val radar = MutableStateFlow<SpoilerRadarResult?>(null)

    /**
     * 该作品在待补池或吃灰区的归属，驱动按钮形态与详情页归属徽章，
     * 并供 [onToggleBacklog] 决定增删。
     */
    private val backlogMembership: StateFlow<BacklogMembership> =
        backlogRepository.observeBacklog()
            .map { items ->
                val item = items.firstOrNull { it.workId == workId }
                BacklogMembership(
                    inBacklog = item != null,
                    inDustMuseum = item?.inDustMuseum == true,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = BacklogMembership(),
            )

    /** 口味画像流；`null` 时决策区口味匹配度为「暂无数据」（不伪造）。 */
    private val tasteProfile: StateFlow<TasteProfile?> =
        tasteProfileRepository.observeTasteProfile()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    /**
     * 12 维口味引擎对当前作品的校准匹配结果。
     * 随作品 / 我的评分 / 口味画像变化重算；引擎不可用（冷启动 / 无特征）时为 `null`，UI 回退旧标签重合估计。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val advancedTasteMatch: StateFlow<Pair<TasteMatchResult?, AdvancedTasteProfile?>> =
        combine(
            workRepository.observeWork(workId),
            myCollection,
            tasteAnalysis.advancedProfile,
            ratings,
        ) { workState, collection, profile, ratingAggregate ->
            val work = (workState as? UiState.Success)?.data
                ?: (workState as? UiState.PartialMissing)?.data
            TasteInputs(work, collection?.rating, profile, ratingAggregate)
        }
            // 评分区陆续刷新、作品资料补全都会触发上游；只在会影响出分的输入变化时才重新计算，
            // 否则联网取特征和重试会被反复打断、迟迟出不了分。
            .distinctUntilChangedBy { Triple(it.work?.id to it.work?.tags?.map { t -> t.name }, it.rating, it.profile) }
            .flatMapLatest { inputs ->
                val work = inputs.work
                val profile = inputs.profile
                if (work == null) return@flatMapLatest flowOf(null to profile)
                val bangumiId = runCatching { resolveBangumiSubjectId(work) }.getOrNull()
                val featureId = bangumiId?.toString() ?: work.id
                // 没有 Bangumi 条目（或一直取不到它的特征）时，用作品自己的标签与评分临时出分。
                tasteAnalysis.observeScore(
                    featureId,
                    inputs.rating,
                    allowNetwork = bangumiId != null,
                    fallback = work.toFallbackFeature(inputs.ratings ?: ratings.value),
                )
                    .map { match -> match to profile }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null to null,
            )

    /** 最近一次就绪的作品，供 [onToggleBacklog] 加入待补池时使用（addAll 需要 [Work]）。 */
    @Volatile
    private var currentWork: Work? = null

    /**
     * 是否已尝试「确保作品已加载」。初始为 false——在首次确保（必要时联网拉取并入库）完成前，
     * 把 [UiState.Empty] 视为加载中，避免观看路线等跳转时短暂闪现「暂无内容」。
     */
    private val workEnsured = MutableStateFlow(false)

    val uiState: StateFlow<UiState<DetailUiState>> =
        combine(
            workRepository.observeWork(workId),
            ratings,
            combine(backlogMembership, myCollection) { membership, collection -> membership to collection },
            combine(tasteProfile, advancedTasteMatch) { profile, adv -> profile to adv },
            combine(radar, detailExtras, workEnsured) { r, e, ensured -> Triple(r, e, ensured) },
        ) { workState, ratingAggregate, personal, profileAndAdv, radarExtrasEnsured ->
            workState.toDetailState(
                ratingAggregate,
                personal.first,
                personal.second,
                profileAndAdv.first,
                radarExtrasEnsured.first,
                radarExtrasEnsured.second,
                radarExtrasEnsured.third,
                profileAndAdv.second.first,
                profileAndAdv.second.second,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    init {
        ensureWorkLoaded()
        loadVndbDetailsEarly()
        viewModelScope.launch {
            workRepository.observeRatings(workId).collect { state ->
                if (state is UiState.Success) ratings.value = state.data
            }
        }
        loadRatings()
        // 打开详情即记录「最近浏览」，供发现-搜索页空态展示「上次点开的条目」。
        if (workId.isNotBlank()) viewModelScope.launch { runCatching { detailInteractions.addRecentlyViewed(workId) } }
        // 冷启动时先用已缓存特征重建画像；仍不可用且存在评分作品时，每进程至多联网补齐一次。
        // 后台执行，不阻塞 UI。
        viewModelScope.launch {
            delay(DETAIL_FIRST_PAINT_GRACE_MS)
            runCatching { tasteAnalysis.ensureReady() }
        }
    }

    /** VNDB 扩展资料不等待多源评分刷新，列表缓存落库后即可在详情页后台补齐。 */
    private fun loadVndbDetailsEarly() {
        if (!workId.startsWith("v", ignoreCase = true)) return
        viewModelScope.launch {
            val cached = visualNovelRepository.cachedDetail(workId)
            if (cached != null) {
                detailExtras.value = detailExtras.value.copy(
                    vndbDetail = cached.detail,
                    vndbLoading = false,
                    vndbError = null,
                )
                maybeTranslateVndbDescription(cached.detail)
            }
            if (cached != null && !cached.stale) {
                linkVndbToBangumi(cached.detail)
                return@launch
            }

            detailExtras.value = detailExtras.value.copy(vndbLoading = cached == null, vndbError = null)
            when (val result = visualNovelRepository.detail(workId, forceRefresh = true)) {
                is AppResult.Success -> {
                    detailExtras.value = detailExtras.value.copy(
                        vndbDetail = result.data,
                        vndbLoading = false,
                        vndbError = null,
                    )
                    maybeTranslateVndbDescription(result.data)
                }
                is AppResult.Failure -> detailExtras.value = detailExtras.value.copy(
                    vndbLoading = false,
                    vndbError = if (cached != null) {
                        "刷新失败，已显示缓存内容"
                    } else {
                        result.error.nextStep ?: "VNDB 扩展资料加载失败"
                    },
                )
            }
        }
    }

    /** 缓存里的 VNDB 详情还没关联 Bangumi 时补一次匹配，补到后刷新评分区。 */
    private suspend fun linkVndbToBangumi(detail: com.acgcompass.domain.visualnovel.VisualNovelDetail) {
        val linked = runCatching { visualNovelRepository.linkBangumiIfMissing(detail) }.getOrNull() ?: return
        detailExtras.value = detailExtras.value.copy(vndbDetail = linked)
        ratings.value = runCatching { workRepository.aggregateRatingsCached(workId) }.getOrNull()
    }

    /**
     * 尽早确保作品存在于本地（数字 id = Bangumi subjectId 时联网补全），完成后置 [workEnsured]。
     * 在此之前 UI 维持加载态，避免跳转瞬间闪现「暂无内容」。
     */
    private fun ensureWorkLoaded() {
        viewModelScope.launch {
            runCatching {
                if (workId.startsWith("v", ignoreCase = true)) {
                    visualNovelRepository.detail(workId)
                } else {
                    workId.toIntOrNull()?.let { detailInteractions.ensureBangumiWork(it) }
                }
            }
            workEnsured.value = true
        }
    }

    /** 重试入口（错误态「重试」）：重新拉取评分与雷达；作品流由 Room 自动刷新。 */
    fun retry() {
        loadRatings(forceRefresh = true)
    }

    private val _refreshing = MutableStateFlow(false)

    /** 下拉刷新进行中。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val refreshGate = com.acgcompass.core.common.RefreshGate()

    /**
     * 下拉刷新：跳过缓存重新拉取条目资料与标签、各平台评分、自己在 Bangumi 上的记录（视觉小说另拉 VNDB 详情），
     * 随后刷新角色 · Staff / 关联作品。口味匹配随作品标签更新自动重算；不重新调用 AI（雷达和 AI 分析保持原样）。
     */
    fun onPullRefresh() {
        if (_refreshing.value || workId.isBlank()) return
        _refreshing.value = true
        viewModelScope.launch {
            refreshGate.run(viewModelScope) {
                // 各平台评分按来源逐条落库、页面逐条更新；指示器最多转约 3 秒，较慢的跨源匹配继续在后台完成。
                launch { runCatching { workRepository.aggregateRatings(workId, forceRefresh = true) } }
                launch { runCatching { refreshPersonalState(forceRefresh = true) } }
                if (workId.startsWith("v", ignoreCase = true)) launch { refreshVndbDetail() }
                loadExtras(forceRefresh = true)
            }
            _refreshing.value = false
        }
    }

    private suspend fun refreshVndbDetail() {
        val result = runCatching { visualNovelRepository.detail(workId, forceRefresh = true) }.getOrNull()
        if (result is AppResult.Success) {
            detailExtras.value = detailExtras.value.copy(
                vndbDetail = result.data,
                vndbLoading = false,
                vndbError = null,
            )
        }
    }

    /**
     * 加入 / 移出待补池。当前已在池中则按作品 id 移除（[BulkOp.DELETE]）；
     * 否则把当前作品批量加入（[BacklogRepository.addAll] 去重幂等）。作品尚未就绪时不操作。
     */
    fun onToggleBacklog() {
        val work = currentWork ?: return
        viewModelScope.launch {
            if (backlogMembership.value.inBacklog) {
                // 移出待补池即「取消想看」（与 markWantToWatch 对称）：待补池条目和「想看」记录一起进回收站，可撤销。
                val batchId = java.util.UUID.randomUUID().toString()
                backlogRepository.removeToTrash(listOf(work.id), batchId)
                removeWantToWatch(work, batchId)
            } else {
                backlogRepository.addAll(listOf(work))
                // 加入待补池默认置「想看」。
                markWantToWatch(work)
            }
        }
    }

    /** 把作品标记为「想看」：写本地 user_collections（Bangumi 那边由待补池的同步队列负责）。 */
    private suspend fun markWantToWatch(work: Work) {
        val now = System.currentTimeMillis()
        val existing = detailInteractions.personalRecord(work.id)
        val localSource = if (work.primarySource == com.acgcompass.domain.model.SourceId.VNDB) {
            com.acgcompass.domain.model.SourceId.VNDB.name
        } else {
            com.acgcompass.domain.model.SourceId.BANGUMI.name
        }
        // 已有更进一步的状态（在看/看过）时不回退为想看。
        if (existing?.status in listOf("在看", "看过", "搁置", "抛弃")) return
        detailInteractions.savePersonalRecord(
            PersonalRecord(
                id = existing?.id ?: "$localSource:${work.id}",
                source = localSource,
                sourceItemId = existing?.sourceItemId ?: work.id,
                workId = work.id,
                status = "想看",
                rating = existing?.rating,
                progress = existing?.progress,
                comment = existing?.comment,
                tags = existing?.tags.orEmpty(),
                updatedAt = now,
                syncedAt = existing?.syncedAt ?: now,
                sourceUpdatedAt = existing?.sourceUpdatedAt,
                // 自动置为想看时保留既有可见性。
                isPrivate = existing?.isPrivate ?: false,
            ),
        )
        // 回写 Bangumi「想看」由待补池统一排队推送（先查 Bangumi 上有没有收藏，不会覆盖已有状态）。
    }

    /**
     * 取消「想看」（与 [markWantToWatch] 对称，由移出待补池触发）。
     *
     * 仅当当前状态为「想看」时，把这条记录和待补池条目一起移进回收站（同一批次，可撤销，原来的加入时间不变）；
     * 已经是在看 / 看过 / 搁置 / 抛弃时只移出待补池，保留观看记录。
     * 不改动 Bangumi：公开接口不能删除收藏，网页上的「想看」需要用户自己移除，所以撤销后两边的日期都保持原样。
     */
    private suspend fun removeWantToWatch(work: Work, batchId: String) {
        val existing = detailInteractions.personalRecord(work.id)
        val message = when {
            existing == null -> "已移出待补池"
            existing.status != "想看" -> "已移出待补池（保留你的「${existing.status}」记录）"
            else -> {
                detailInteractions.trashPersonalRecord(work.id, batchId)
                "已移出待补池并取消想看"
            }
        }
        undoController.offer(message, batchId)
    }

    private fun loadRatings(forceRefresh: Boolean = false) {
        if (workId.isBlank()) return
        viewModelScope.launch {
            // 「缓存优先加载」：点入详情时评分聚合走网络刷新，若等待请求会让评分区长时间「暂无数据」。
            // 故先立即读本地已缓存评分（不联网）铺上首帧——有缓存则秒显，随后网络刷新到手再原地更新数值。
            // 因 ratings 是 StateFlow、UI 为响应式重组，更新只改变化的数值，不会重建列表 / 改变滚动位置（无页面重载）。
            val cached = runCatching { workRepository.aggregateRatingsCached(workId) }.getOrNull()
            if (cached != null) ratings.value = cached

            // 仓库按来源逐行落库，上方观察器会立即发布每个结果，不等待整条跨源补齐链完成。
            runCatching { workRepository.aggregateRatings(workId, forceRefresh) }
        }
        viewModelScope.launch { runCatching { refreshPersonalState(forceRefresh) } }
        viewModelScope.launch {
            delay(if (forceRefresh) DETAIL_BACKGROUND_DELAY_MS / 2 else DETAIL_BACKGROUND_DELAY_MS)
            loadExtras(forceRefresh)
        }
        viewModelScope.launch {
            delay(DETAIL_RADAR_DELAY_MS)
            loadRadar(forceRefresh)
        }
    }

    /**
     * 生成无剧透评价雷达。等待作品就绪后以其标题 / 标签 / 个人短评为输入调用
     * [GenerateSpoilerRadarUseCase]：未配置 AI 时自动回退本地规则版（generator=RULE），保证雷达可展示；
     * 当 AI 已配置但需成本确认（[RadarOutcome.NeedsCostConfirmation]）时不擅自发起付费请求，
     * 决策区维持「暂无数据」直至用户在 AI 流程中确认。
     */
    private fun loadRadar(forceRefresh: Boolean = false) {
        if (workId.isBlank()) return
        viewModelScope.launch {
            val work = workRepository.observeWork(workId)
                .mapNotNull { state ->
                    when (state) {
                        is UiState.Success -> state.data
                        is UiState.PartialMissing -> state.data
                        else -> null
                    }
                }
                .first()
            val stableRequest = RadarRequest(
                workId = work.id,
                title = work.titles.canonical,
                summary = work.summary.orEmpty(),
                mediaType = work.mediaType.name,
                tags = work.tags.map { it.name },
                userComments = listOfNotNull(myCollection.value?.shortReview?.takeIf { it.isNotBlank() }),
                // 隐私开关：未允许时，评论只参与本地规则版雷达，不进 AI 请求。
                allowReviewsToAi = detailInteractions.isAiReviewAnalysisAllowed(),
            )
            val fingerprint = RadarCachePolicy.fingerprint(stableRequest)
            if (!forceRefresh) {
                val cached = (aiRepository.getCached(work.id, AiTaskType.SPOILER_RADAR) as? AppResult.Success)?.data
                if (
                    cached?.inputFingerprint == fingerprint &&
                    cached.promptVersion == RadarCachePolicy.PROMPT_VERSION
                ) {
                    val output = runCatching {
                        json.decodeFromString<SpoilerRadarOutput>(
                            cached.payloadJson,
                        )
                    }.getOrNull()
                    if (output != null) {
                        radar.value = SpoilerRadarResult(
                            workId = work.id,
                            output = output,
                            generator = cached.generator,
                            requestedLevel = SpoilerLevel.NO_SPOILER,
                            effectiveLevel = SpoilerLevel.NO_SPOILER,
                            summarySources = cached.dataSources.mapNotNull { label ->
                                RadarSummarySource.entries.firstOrNull { it.label == label }
                            },
                            generatedAt = cached.generatedAt,
                        )
                        return@launch
                    }
                }
            }
            // 社区评分信号（仅读本地缓存，不联网），让 AI 校准口碑判断。
            val communitySignal = runCatching {
                val agg = workRepository.aggregateRatingsCached(work.id)
                val scored = agg.perSource.entries.mapNotNull { (src, entry) ->
                    val s = entry?.score ?: return@mapNotNull null
                    if (s <= 0f) return@mapNotNull null
                    val ten = when (src) {
                        com.acgcompass.domain.model.SourceId.ANILIST,
                        com.acgcompass.domain.model.SourceId.VNDB,
                        -> s / 10f
                        else -> s
                    }.coerceIn(0f, 10f)
                    ten to (entry.voteCount.coerceAtLeast(0))
                }
                if (scored.isEmpty()) {
                    null
                } else {
                    scored.map { it.first }.average() to scored.sumOf { it.second }
                }
            }.getOrNull()
            // 真实他人短评作为雷达 publicReviews 输入（AI/规则都更有料）。
            // 用解析出的真实 Bangumi subjectId（主源或链接），避免对非 Bangumi 作品取错条目短评。
            val publicComments = resolveBangumiSubjectId(work)
                ?.let { detailInteractions.fetchBangumiComments(it, limit = 20) as? AppResult.Success }
                ?.data.orEmpty()
                .filter { it.isNotBlank() }
                .take(15)
            val request = stableRequest.copy(
                publicReviews = publicComments,
                communityScore = communitySignal?.first,
                communityVotes = communitySignal?.second,
            )
            // 雷达走 AI 驱动——已配置 AI 时直接以「成本已确认」运行，产出真实无剧透分析；
            // 未配置 AI 时回退本地规则（占位 + 引导配置 AI）。决策助手/评论摘要均消费该结果。
            val aiConfigured = detailInteractions.isAiConfigured()
            val generated = when (
                val outcome = generateSpoilerRadar(request, AiRunOptions(confirmed = aiConfigured))
            ) {
                is RadarOutcome.Ready -> {
                    // 已配置 AI 却回退到规则时，把原因暴露给用户（便于诊断 AI 为何没跑成）。
                    if (aiConfigured && outcome.aiFallbackReason != null) {
                        _recordMessage.value =
                            "AI 雷达本次回退本地规则：" + outcome.aiFallbackReason.cause +
                            "（" + outcome.aiFallbackReason.nextStep + "）"
                    }
                    outcome.result
                }
                is RadarOutcome.NeedsCostConfirmation -> generateSpoilerRadar.local(request)
            }
            radar.value = generated
            runCatching {
                aiRepository.put(
                    AiResult(
                        id = "spoiler-radar:${work.id}",
                        workId = work.id,
                        taskType = AiTaskType.SPOILER_RADAR,
                        generator = generated.generator,
                        payloadJson = json.encodeToString(generated.output),
                        confidence = generated.confidence,
                        dataSources = generated.summarySources.map { it.label },
                        generatedAt = generated.generatedAt,
                        inputFingerprint = fingerprint,
                        promptVersion = RadarCachePolicy.PROMPT_VERSION,
                    ),
                )
            }
        }
    }

    /**
     * 把作品的七态 [UiState] 映射为详情页七态。仅在作品**已就绪**（成功 / 字段缺失）时合入评分 / 待补 / 口味；
     * 其余状态（加载 / 空 / 错误 / 未授权 / 限流 / 无网络）原样透传，由 `StateScaffold` 统一渲染。
     */
    private fun UiState<Work>.toDetailState(
        ratingAggregate: RatingAggregate?,
        membership: BacklogMembership,
        collectionState: CollectionState?,
        profile: TasteProfile?,
        radarResult: SpoilerRadarResult?,
        extras: DetailExtras,
        workEnsured: Boolean,
        advancedMatch: TasteMatchResult?,
        advancedProfile: AdvancedTasteProfile?,
    ): UiState<DetailUiState> = when (this) {
        is UiState.Success -> {
            currentWork = data
            UiState.Success(
                buildDetailUiState(
                    data,
                    ratingAggregate,
                    membership.inBacklog,
                    collectionState,
                    tasteProfile = profile,
                    radar = radarResult,
                    inDustMuseum = membership.inDustMuseum,
                    extras = extras,
                    advancedMatch = advancedMatch,
                    advancedProfile = advancedProfile,
                ),
            )
        }
        // 作品字段缺失：仍渲染详情，缺失字段在 UI 内显示「暂无数据」。
        is UiState.PartialMissing -> {
            currentWork = data
            UiState.PartialMissing(
                buildDetailUiState(
                    data,
                    ratingAggregate,
                    membership.inBacklog,
                    collectionState,
                    tasteProfile = profile,
                    radar = radarResult,
                    inDustMuseum = membership.inDustMuseum,
                    extras = extras,
                    advancedMatch = advancedMatch,
                    advancedProfile = advancedProfile,
                ),
            )
        }
        is UiState.Loading -> UiState.Loading
        // 作品尚未确保加载完成（可能正在联网补全）时，把空态视为加载中，避免跳转闪现「暂无内容」。
        is UiState.Empty -> if (!workEnsured) UiState.Loading else this
        is UiState.Error -> this
        is UiState.Unauthorized -> UiState.Unauthorized
        is UiState.RateLimited -> UiState.RateLimited
        is UiState.NoNetwork -> UiState.NoNetwork
    }

    /**
     * 拉取角色·Staff / 关联作品 / 观看路线。仅对 Bangumi 来源作品（work id == subjectId，数字）发起；
     * 任一失败被吞掉（韧性，对应 Tab 显示「暂无数据」，绝不伪造）。
     */
    private fun loadExtras(forceRefresh: Boolean = false) {
        if (workId.isBlank()) return
        viewModelScope.launch {
            val work = workRepository.observeWork(workId)
                .mapNotNull { state ->
                    when (state) {
                        is UiState.Success -> state.data
                        is UiState.PartialMissing -> state.data
                        else -> null
                    }
                }
                .first()
            if (
                work.primarySource == com.acgcompass.domain.model.SourceId.VNDB &&
                (forceRefresh || detailExtras.value.vndbDetail == null)
            ) {
                detailExtras.value = detailExtras.value.copy(vndbLoading = true, vndbError = null)
                when (val vndb = visualNovelRepository.detail(work.id, forceRefresh)) {
                    is AppResult.Success -> {
                        detailExtras.value = detailExtras.value.copy(
                            vndbDetail = vndb.data,
                            vndbLoading = false,
                            vndbError = null,
                        )
                        maybeTranslateVndbDescription(vndb.data)
                        ratings.value = runCatching { workRepository.aggregateRatingsCached(work.id) }.getOrNull()
                    }
                    is AppResult.Failure -> detailExtras.value = detailExtras.value.copy(
                        vndbLoading = false,
                        vndbError = vndb.error.nextStep ?: "VNDB 扩展资料加载失败",
                    )
                }
            }
            // 解析真实 Bangumi subjectId（主源或交叉验证链接）——让有 Bangumi 词条的非主源作品
            // 也能拉角色/Staff/关联与真实短评；无 Bangumi 词条则跳过（绝不乱用裸数字 id）。
            val subjectId = resolveBangumiSubjectId(work)
            if (subjectId == null) {
                detailExtras.value = detailExtras.value.copy(bangumiLoad = ExtrasLoad.NO_SOURCE)
                return@launch
            }
            detailExtras.value = detailExtras.value.copy(bangumiLoad = ExtrasLoad.LOADING)
            val completed = runCatching { collectBangumiExtras(work, subjectId, forceRefresh) }
            detailExtras.value = detailExtras.value.copy(
                bangumiLoad = ExtrasLoad.LOADED,
                creditsFailed = detailExtras.value.creditsFailed || completed.isFailure,
                commentsFailed = detailExtras.value.commentsFailed || completed.isFailure,
                failure = detailExtras.value.failure
                    ?: com.acgcompass.domain.detail.ExtrasFailure.OTHER.takeIf { completed.isFailure },
            )
        }
    }

    private suspend fun collectBangumiExtras(work: Work, subjectId: Int, forceRefresh: Boolean) {
        detailRepository.observeBangumiExtras(work.id, subjectId, forceRefresh).collect { data ->
            val routeEntries = buildRouteEntries(work, data.relations)
            seriesSubjectIds = routeEntries.mapNotNull { it.workId.toIntOrNull() }
            val tags = data.tags.ifEmpty { work.tags.map { it.name } }
                .filter { it.isNotBlank() }
                .distinct()
                .take(15)
            detailExtras.value = DetailExtras(
                charactersStaffBody = charactersStaffText(data),
                relationsBody = relationsText(data),
                watchRouteBody = watchRouteText(data),
                routeEntries = routeEntries,
                comments = commentLines(data),
                tags = tags,
                vndbDetail = detailExtras.value.vndbDetail,
                vndbLoading = detailExtras.value.vndbLoading,
                vndbError = detailExtras.value.vndbError,
                bangumiLoad = ExtrasLoad.LOADING,
                creditsFailed = data.creditsFailed,
                commentsFailed = data.commentsFailed,
                failure = data.failure,
            )
        }
    }

    private suspend fun maybeTranslateVndbDescription(detail: com.acgcompass.domain.visualnovel.VisualNovelDetail) {
        val enabled = detailInteractions.shouldAutoTranslateVndbDescription()
        val source = detail.description
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { enabled && detail.bangumiSummary.isNullOrBlank() }
            ?.takeUnless(::containsCjk)
        if (source == null) return
        val fingerprint = VndbTranslationCachePolicy.fingerprint(source)
        val cachedText = cachedVndbTranslation(detail.id, source)
        if (cachedText != null) {
            applyVndbTranslation(detail, cachedText)
        } else {
            generateVndbTranslation(detail, source, fingerprint)
        }
    }

    private suspend fun cachedVndbTranslation(workId: String, source: String): String? {
        val cached = (aiRepository.getCached(workId, AiTaskType.VNDB_TRANSLATION) as? AppResult.Success)?.data
        val reusable = cached != null &&
            VndbTranslationCachePolicy.isReusable(cached.inputFingerprint, cached.promptVersion, source)
        return cached
            ?.takeIf { reusable }
            ?.let { runCatching { json.decodeFromString<VndbTranslationOutput>(it.payloadJson) }.getOrNull() }
            ?.text
            ?.takeIf(String::isNotBlank)
    }

    private suspend fun generateVndbTranslation(
        detail: com.acgcompass.domain.visualnovel.VisualNovelDetail,
        source: String,
        fingerprint: String,
    ) {
        val configured = detailInteractions.isAiConfigured()
        if (!configured) return
        when (
            val result = aiEngine.run(
                AiTask.VndbTranslation(detail.id, source),
                AiRunOptions(confirmed = true),
            )
        ) {
            is AiRunResult.Success -> {
                val translated = result.payload.text.trim()
                if (translated.isNotBlank()) {
                    aiRepository.put(
                        result.result.copy(
                            inputFingerprint = fingerprint,
                            promptVersion = VndbTranslationCachePolicy.PROMPT_VERSION,
                        ),
                    )
                    applyVndbTranslation(detail, translated)
                }
            }
            else -> Unit
        }
    }

    private fun containsCjk(value: String): Boolean = value.any { it in '\u4E00'..'\u9FFF' }

    private fun applyVndbTranslation(detail: com.acgcompass.domain.visualnovel.VisualNovelDetail, translated: String) {
        val current = detailExtras.value.vndbDetail ?: detail
        detailExtras.value = detailExtras.value.copy(
            vndbDetail = current.copy(translatedDescription = translated),
        )
    }

    /** 将主线必看条目加入待补池；关联作品资料按尽力而为方式拉取。 */
    fun onAddMainlineToBacklog() = addRouteToBacklog(mainOnly = true)

    /** 将全系列作品加入待补池。 */
    fun onAddAllSeriesToBacklog() = addRouteToBacklog(mainOnly = false)

    private fun addRouteToBacklog(mainOnly: Boolean) {
        viewModelScope.launch {
            val entries = detailExtras.value.routeEntries
            val targetIds = entries
                .filter { if (mainOnly) it.section == RouteSection.MAIN else true }
                .mapNotNull { it.workId.toIntOrNull() }
                .distinct()
            val works = mutableListOf<Work>()
            currentWork?.let { works += it }
            for (id in targetIds) {
                val r = detailInteractions.fetchBangumiWork(id)
                if (r is AppResult.Success) works += r.data
            }
            if (works.isNotEmpty()) {
                runCatching { backlogRepository.addAll(works.distinctBy { it.id }) }
            }
        }
    }

    /**
     * 点击 AI 匹配度分析按钮后：
     * 1. 未配置 AI → [AiMatchUi.NotConfigured]（提示可用本地模型估计），不发请求。
     * 2. 已配置 → [AiMatchUi.Loading] → 组装作品信息 + 口味画像高低分标签为输入，
     *    经 [AiEngine] 运行 [AiTask.TasteMatch]（成本已确认）。
     * 3. 成功 → [AiMatchUi.Result]（结构化 matchScore / likedReasons / riskReasons / confidence，
     *    AiEngine 自动写入 AI_RESULT 缓存）。
     * 4. 低置信 / 失败 / 未配置 → [AiMatchUi.Error]，提示已回退本地模型，页面不无响应。
     */
    fun onAnalyzeMatchWithAi() {
        val work = currentWork ?: return
        viewModelScope.launch {
            // AI 配置检查（不读取明文，仅看元数据 configured）。
            val configured = detailInteractions.isAiConfigured()
            if (!configured) {
                _aiMatch.value = AiMatchUi.NotConfigured("未配置 AI，可使用本地模型估计（见上方「口味匹配度」）")
                return@launch
            }
            _aiMatch.value = AiMatchUi.Loading

            val profile = tasteProfile.value
            val highTags = profile?.tagStats
                ?.filter { it.bucket == TagBucket.HIGH_SCORE }
                ?.map { it.tagName }
                ?.take(20)
                .orEmpty()
            val lowTags = profile?.tagStats
                ?.filter { it.bucket == TagBucket.LOW_SCORE }
                ?.map { it.tagName }
                ?.take(20)
                .orEmpty()
            val community = ratings.value
            // 纳入近期评分作品 + 历史最高/最低分锚点，比仅用标签更具参考价值。
            val allRated = runCatching { detailInteractions.ratedWorkContexts() }.getOrDefault(emptyList())
            val recentRated = allRated.sortedByDescending { it.updatedAt }.take(20)
            // 口味锚点——历史最高分（最喜欢）与最低分（最不喜欢），各取前若干。
            val topRated = allRated.sortedByDescending { it.rating }.take(5)
            val lowRated = allRated.filter { it.rating <= 6 }.sortedBy { it.rating }.take(5)
            // 隐私开关「允许把短评发给 AI」：关闭（默认）时，发给 AI 的内容里不带任何短评文本。
            val allowComments = detailInteractions.isAiReviewAnalysisAllowed()
            val lineOf: (com.acgcompass.domain.detail.RatedWorkContext) -> String = { context ->
                buildString {
                    append(context.title).append(" · ").append(context.rating).append("分")
                    context.comment?.takeIf { allowComments && it.isNotBlank() }?.let {
                        append("（短评：").append(it.take(40)).append("）")
                    }
                }
            }
            val ratedLines = recentRated.map(lineOf)
            val topLines = topRated.map(lineOf)
            val lowLines = lowRated.map(lineOf)
            // 本地个人分歧模型给出的证据：大众基线 / 预计评分 / 分歧来源、题材相近的已评分作品、
            // 与大众分歧最大的评分。让 AI 在有数据支撑的先验上做归因，而不是只看一堆标签凭感觉打分。
            val divergenceEvidence = runCatching {
                buildDivergenceEvidence(
                    work,
                    allRated,
                    allowComments,
                )
            }.getOrDefault("")
            val content = buildString {
                append("作品：").append(work.titles.canonical).append('\n')
                community?.let { agg ->
                    agg.perSource[com.acgcompass.domain.model.SourceId.BANGUMI]?.let { entry ->
                        append("Bangumi 社区评分：").append(entry.score)
                        entry.voteCount?.let { append("（").append(it).append(" 人评分）") }
                        append('\n')
                    }
                }
                work.titles.ja?.let { append("原名：").append(it).append('\n') }
                append("类型：").append(work.mediaType.name).append('\n')
                work.year?.let { append("年份：").append(it).append('\n') }
                val workTags = work.tags.map { it.name }
                if (workTags.isNotEmpty()) append("作品标签：").append(workTags.joinToString("、")).append('\n')
                work.summary?.takeIf { it.isNotBlank() }?.let {
                    append("简介：").append(it.take(400)).append('\n')
                }
                if (topLines.isNotEmpty()) {
                    append("用户历史高评分作品：\n").append(topLines.joinToString("\n")).append('\n')
                }
                if (lowLines.isNotEmpty()) {
                    append("用户历史低评分作品：\n").append(lowLines.joinToString("\n")).append('\n')
                }
                if (ratedLines.isNotEmpty()) {
                    append("用户近期评分作品（标题、评分、短评）：\n")
                    append(ratedLines.joinToString("\n")).append('\n')
                }
                append(divergenceEvidence)
                if (highTags.isNotEmpty()) append("高评分作品常见标签：").append(highTags.joinToString("、")).append('\n')
                if (lowTags.isNotEmpty()) append("低评分作品常见标签：").append(lowTags.joinToString("、")).append('\n')
                profile?.avgScore?.takeIf { it > 0f }?.let { append("用户平均分：").append("%.1f".format(it)).append('\n') }
            }
            val dataSources = buildList {
                add("作品资料")
                if (recentRated.isNotEmpty()) add("近期评分作品")
                if (profile != null) add("口味画像")
                if (community != null) add("社区评分")
            }

            val result = runCatching {
                aiEngine.run(
                    AiTask.TasteMatch(workId = work.id, content = content, dataSources = dataSources),
                    AiRunOptions(confirmed = true),
                )
            }.getOrElse { AiRunResult.Failure(com.acgcompass.core.common.AppError.Server()) }

            _aiMatch.value = when (result) {
                is AiRunResult.Success -> {
                    val out = result.payload
                    val fraction = (out.matchScore.coerceIn(0, 100)) / 100f
                    AiMatchUi.Result(
                        predictedRatingText = out.predictedRating.takeIf { it in 1f..10f }?.let { "%.1f".format(it) },
                        divergenceReason = out.divergenceReason.trim().takeIf { it.isNotEmpty() },
                        ratingAngles = out.ratingAngles.map { it.trim() }.filter { it.isNotEmpty() }.take(4),
                        matchPercentText = "${out.matchScore.coerceIn(0, 100)}%",
                        fraction = fraction,
                        likedReasons = out.likedReasons,
                        riskReasons = out.riskReasons,
                        confidenceText = "置信度 ${(out.confidence.coerceIn(0f, 1f) * 100).toInt()}%",
                        dataSources = result.result.dataSources.ifEmpty { dataSources },
                    )
                }
                is AiRunResult.LowConfidence ->
                    AiMatchUi.Error("AI 未返回可靠的结构化结果，当前显示本地口味匹配度")
                is AiRunResult.NotConfigured ->
                    AiMatchUi.NotConfigured("未配置 AI，可使用本地模型估计（见上方「口味匹配度」）")
                is AiRunResult.NeedsConfirmation ->
                    AiMatchUi.Error("AI 分析需确认成本，已回退本地模型估计")
                is AiRunResult.Failure ->
                    AiMatchUi.Error("AI 分析失败（${result.error.nextStep ?: "请稍后重试"}），已回退本地模型估计")
            }
        }
    }

    /**
     * 组装「个人分歧」证据文本（供 AI 口味匹配）。模型未就绪（评分太少）时返回空串——AI 只拿到原有输入。
     */
    private suspend fun buildDivergenceEvidence(
        work: Work,
        rated: List<com.acgcompass.domain.detail.RatedWorkContext>,
        allowComments: Boolean,
    ): String {
        val model = tasteAnalysis.advancedProfile.value?.divergence ?: return ""
        val bangumiId = runCatching { resolveBangumiSubjectId(work) }.getOrNull()
        val match = tasteAnalysis.score(bangumiId?.toString() ?: work.id, null, allowNetwork = false)
        val byId = rated.filter { it.subjectId.isNotBlank() }.associateBy { it.subjectId }
        fun line(e: com.acgcompass.domain.taste.RatedExample): String? {
            val context = byId[e.subjectId] ?: return null
            return buildString {
                append("- ").append(context.title).append("：你 ").append(e.rating).append(" 分，大众基线 ")
                append("%.1f".format(e.baseline)).append("（").append("%+.1f".format(e.divergence)).append("）")
                context.comment?.takeIf { allowComments && it.isNotBlank() }
                    ?.let { append("，短评：").append(it.take(AI_COMMENT_CHARS)) }
            }
        }
        return buildString {
            append("【本地模型】该用户均分 ").append("%.1f".format(model.meanRating))
            append("，比同批作品的社区均分").append(if (model.leniency >= 0) "高 " else "低 ")
            append("%.1f".format(kotlin.math.abs(model.leniency))).append(" 分\n")
            match?.prediction?.let { p ->
                append("大众基线 ").append("%.1f".format(p.baseline)).append("，预计评分 ").append("%.1f".format(p.rating))
                append("（个人分歧 ").append("%+.1f".format(p.divergence)).append("）\n")
                if (p.factors.isNotEmpty()) {
                    append("分歧来源：")
                    append(p.factors.joinToString("、") { "${it.label} ${"%+.1f".format(it.delta)}" }).append('\n')
                }
            }
            val similar = match?.similarRated.orEmpty().mapNotNull(::line)
            if (similar.isNotEmpty()) {
                append("【题材相近的已评分作品】\n").append(similar.joinToString("\n")).append('\n')
            }
            val (above, below) = model.strongestDivergences(AI_DIVERGENCE_EXAMPLES)
            val extremes = (above + below).mapNotNull(::line)
            if (extremes.isNotEmpty()) {
                append("【与大众分歧最大的评分】\n").append(extremes.joinToString("\n")).append('\n')
            }
        }
    }

    /**
     * 编辑「我的记录」并回写 Bangumi（状态 / 评分 / 进度 / 短评）。
     * 流程：本地先写 user_collections（即时反映 UI）→ 调 Bangumi `POST /v0/users/-/collections/{id}`
     * 回传；未配置 Bangumi 或非 Bangumi 条目则只本地保存并提示。失败不回滚本地，提示同步失败原因。
     */
    fun onUpdateMyRecord(
        statusLabel: String?,
        rating: Int?,
        progress: Int?,
        comment: String?,
        tags: List<String>? = null,
        private: Boolean = false,
    ) {
        // 进度不得超过总集数；并据进度自动推导状态（用户未显式选状态时）。
        val totalEps = currentWork?.units?.episodes?.takeIf { it > 0 }
        val clampedProgress = progress?.let { p ->
            if (totalEps != null) p.coerceIn(0, totalEps) else p.coerceAtLeast(0)
        }
        val effectiveStatus = statusLabel ?: when {
            clampedProgress == null || clampedProgress <= 0 -> null
            totalEps != null && clampedProgress >= totalEps -> "看过"
            else -> "在看"
        }
        viewModelScope.launch {
            // 本地先写——采用「编辑对话框即完整意图」的覆盖语义：未选状态/留空即清空该字段
            // （不再回退既有值），从而支持清空状态/评分/进度/短评。
            val now = System.currentTimeMillis()
            val existing = detailInteractions.personalRecord(workId)
            // 解析真实 Bangumi subjectId（非 Bangumi 作品的裸数字 id 绝不当 subjectId）。
            val subjectId = currentWork
                ?.takeUnless { it.primarySource == com.acgcompass.domain.model.SourceId.VNDB }
                ?.let { resolveBangumiSubjectId(it) }
            val configured = detailInteractions.isBangumiConfigured()
            // 全字段为空（无状态/评分/进度/短评/标签）→ 删除该收藏行，避免残留一条「暂无数据」空记录
            // 空白短评不写入，避免污染时光机基线和个人收藏记录。
            val fullyCleared = effectiveStatus == null && rating == null && clampedProgress == null &&
                comment.isNullOrBlank() && tags.isNullOrEmpty()
            if (fullyCleared) {
                // 清空记录表示停止追踪：记录和待补池条目一起移进回收站（可撤销）。Bangumi 上的收藏不动——
                // 公开接口不能删除收藏，网页上的记录在回收站里提供入口，由用户自己移除。
                val batchId = java.util.UUID.randomUUID().toString()
                val trashed = detailInteractions.trashPersonalRecord(workId, batchId)
                backlogRepository.removeToTrash(listOf(workId), batchId)
                undoController.offer(if (trashed) "已清空记录，可在回收站恢复" else "已清空记录", batchId)
                return@launch
            }
            detailInteractions.savePersonalRecord(
                PersonalRecord(
                    id = existing?.id ?: "${currentWork?.primarySource?.name ?: "BANGUMI"}:$workId",
                    source = existing?.source ?: currentWork?.primarySource?.name ?: "BANGUMI",
                    sourceItemId = existing?.sourceItemId ?: workId,
                    workId = workId,
                    status = effectiveStatus,
                    rating = rating,
                    progress = clampedProgress,
                    comment = comment,
                    tags = tags ?: emptyList(),
                    updatedAt = now,
                    syncedAt = existing?.syncedAt ?: now,
                    sourceUpdatedAt = existing?.sourceUpdatedAt,
                    // 本地持久化可见性，供编辑对话框回显。
                    isPrivate = private,
                ),
            )
            // 应用级画像协调器会观察这次本地写入并防抖更新，避免详情页重复计算。
            val tasteNote = if (rating != null) "；口味画像将在后台更新" else ""

            if (subjectId == null) {
                _recordMessage.value = "已本地保存（该作品在 Bangumi 无对应词条，仅本地，不同步）$tasteNote"
                return@launch
            }
            if (!configured) {
                _recordMessage.value = "已本地保存；未配置 Bangumi Token，未同步到云端$tasteNote"
                return@launch
            }
            // 写回前确认条目在 Bangumi 存在，避免对无词条作品反复 PATCH/POST 报错。
            if (!detailInteractions.bangumiSubjectExists(subjectId)) {
                _recordMessage.value = "已本地保存；该作品在 Bangumi 无词条，无法同步到云端$tasteNote"
                return@launch
            }
            val result = detailInteractions.updateBangumiRecord(
                subjectId,
                BangumiRecordUpdate(
                    type = effectiveStatus?.let { bangumiTypeOf(it) },
                    rating = rating ?: 0,
                    comment = comment ?: "",
                    tags = tags ?: emptyList(),
                    isPrivate = private,
                ),
            )
            // 动画进度通过专用章节端点上传（书籍 / 漫画不上传进度，仅本地）。
            val isAnime = currentWork?.mediaType == com.acgcompass.domain.model.MediaType.ANIME
            val progressResult: AppResult<Unit>? =
                if (isAnime && clampedProgress != null && clampedProgress > 0) {
                    detailInteractions.markEpisodesWatched(subjectId, clampedProgress)
                } else {
                    null
                }
            _recordMessage.value = when (result) {
                is AppResult.Success -> {
                    detailInteractions.markPersonalRecordSynced(workId, System.currentTimeMillis())
                    val progressNote = when (progressResult) {
                        is AppResult.Failure -> "（进度同步失败：${progressResult.error.cause}）"
                        else -> ""
                    }
                    "已保存并同步到 Bangumi$progressNote$tasteNote"
                }
                is AppResult.Failure -> {
                    // 失败的修改进待推送队列，联网后自动重试。
                    detailInteractions.schedulePendingPush()
                    "本地已保存，同步 Bangumi 失败，联网后会自动重试：${result.error.cause}"
                }
            }
        }
    }

    /**
     * 仅在用户明确点击后，把 VNDB 本地记录同步到高置信匹配的 Bangumi 词条。
     * 普通编辑始终只写本地，避免无感覆盖云端记录。
     */
    fun onSyncVndbRecordToBangumi() {
        val work = currentWork ?: return
        if (work.primarySource != com.acgcompass.domain.model.SourceId.VNDB) return
        viewModelScope.launch {
            val existing = detailInteractions.personalRecord(work.id)
            if (existing == null) {
                _recordMessage.value = "当前没有可同步的本地记录"
                return@launch
            }
            val subjectId = resolveBangumiSubjectId(work)
            if (subjectId == null) {
                _recordMessage.value = "没有达到自动关联门槛的 Bangumi 词条，未同步"
                return@launch
            }
            val configured = detailInteractions.isBangumiConfigured()
            if (!configured) {
                _recordMessage.value = "请先登录 Bangumi，再手动同步这条记录"
                return@launch
            }
            if (!detailInteractions.bangumiSubjectExists(subjectId)) {
                _recordMessage.value = "匹配的 Bangumi 词条当前不可用，未同步"
                return@launch
            }
            val result = detailInteractions.updateBangumiRecord(
                subjectId,
                BangumiRecordUpdate(
                    type = existing.status?.let(::bangumiTypeOf),
                    rating = existing.rating ?: 0,
                    comment = existing.comment.orEmpty(),
                    tags = existing.tags,
                    isPrivate = existing.isPrivate,
                ),
            )
            _recordMessage.value = when (result) {
                is AppResult.Success -> {
                    detailInteractions.markPersonalRecordSynced(work.id, System.currentTimeMillis())
                    "已将这条本地记录同步到 Bangumi"
                }
                is AppResult.Failure -> "同步失败：${result.error.nextStep ?: "请稍后重试"}"
            }
        }
    }

    /** 状态标签 → Bangumi 收藏 type（想看1/看过2/在看3/搁置4/抛弃5）。未知返回 null。 */
    private fun bangumiTypeOf(label: String): Int? = when (label) {
        "想看" -> 1
        "看过" -> 2
        "在看" -> 3
        "搁置" -> 4
        "抛弃" -> 5
        else -> null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DETAIL_FIRST_PAINT_GRACE_MS = 1_500L
        const val DETAIL_BACKGROUND_DELAY_MS = 450L
        const val DETAIL_RADAR_DELAY_MS = 1_200L
    }

    private suspend fun refreshPersonalState(forceRefresh: Boolean) {
        val work = workRepository.observeWork(workId)
            .mapNotNull { state ->
                when (state) {
                    is UiState.Success -> state.data
                    is UiState.PartialMissing -> state.data
                    else -> null
                }
            }
            .first()
        detailInteractions.refreshPersonalRecord(work, forceRefresh)
    }
}

/**
 * 该作品在待补池中的归属信号。
 *
 * @property inBacklog 是否已在待补池。
 * @property inDustMuseum 是否落入吃灰区（待补池中长期未动的分区）。
 */
data class BacklogMembership(
    val inBacklog: Boolean = false,
    val inDustMuseum: Boolean = false,
)

/**
 * 本地规则把 Bangumi 关联作品分为主线、可选和可跳过，不调用 AI。
 * - 主线 MAIN：前传 / 续集 / 正篇（含「本作」）。
 * - 可跳过 SKIPPABLE：总集篇 / PV / 角色歌 / 预告。
 * - 可选 OPTIONAL：其余（番外 / 外传 / 剧场版 / OVA / 其他）。
 * 排序：主线（前传→本作→续集）→ 可选 → 可跳过。
 */
private fun buildRouteEntries(
    current: Work,
    relations: List<com.acgcompass.domain.detail.DetailRelation>,
): List<RouteEntryUi> {
    if (relations.isEmpty()) return emptyList()
    fun sectionOf(rel: String?): RouteSection {
        val r = rel ?: ""
        return when {
            r.contains("总集") || r.contains("PV") || r.contains("预告") || r.contains("角色歌") -> RouteSection.SKIPPABLE
            r.contains("前") || r.contains("续") || r.contains("正篇") || r.contains("主线") -> RouteSection.MAIN
            else -> RouteSection.OPTIONAL
        }
    }
    fun mainOrder(rel: String?): Int = when {
        rel?.contains("前") == true -> 0
        rel?.contains("续") == true -> 2
        else -> 1
    }
    val others = relations.mapNotNull { rel ->
        val title = rel.nameCn.ifBlank { rel.name }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 非动画（游戏=4 / 书籍画集=1 / 音乐=3 / 三次元=6 等）归为衍生作/原作，不进可选/可跳过。
        val section = if (rel.type != null && rel.type != 2) RouteSection.DERIVED else sectionOf(rel.relation)
        RouteEntryUi(
            workId = rel.id.toString(),
            title = title,
            relationLabel = rel.relation?.takeIf { it.isNotBlank() } ?: "关联",
            section = section,
        )
    }
    // 当前作品作为主线「本作」。
    val self =
        RouteEntryUi(
            workId = current.id,
            title = current.titles.canonical,
            relationLabel = "本作",
            section = RouteSection.MAIN,
        )
    val main = (others.filter { it.section == RouteSection.MAIN } + self)
        .sortedBy { if (it.relationLabel == "本作") 1 else mainOrder(it.relationLabel) }
    val optional = others.filter { it.section == RouteSection.OPTIONAL }
    val skippable = others.filter { it.section == RouteSection.SKIPPABLE }
    val derived = others.filter { it.section == RouteSection.DERIVED }
    return main + optional + skippable + derived
}

/** AI 输入里每条短评最多带多少字，以及「分歧最大的评分」高低各取几部。 */
private const val AI_COMMENT_CHARS = 60
private const val AI_DIVERGENCE_EXAMPLES = 4

private data class TasteInputs(
    val work: Work?,
    val rating: Int?,
    val profile: AdvancedTasteProfile?,
    val ratings: RatingAggregate?,
)

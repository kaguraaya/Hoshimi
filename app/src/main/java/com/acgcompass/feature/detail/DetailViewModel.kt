package com.acgcompass.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.RatingAggregate
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
    private val vndbTranslator = VndbDescriptionTranslator(detailInteractions, aiRepository, aiEngine, json)
    private val aiMatchAnalyzer = DetailAiMatchAnalyzer(detailInteractions, aiEngine, tasteAnalysis)
    private val radarLoader =
        DetailRadarLoader(workRepository, detailInteractions, aiRepository, generateSpoilerRadar, json)
    private val recordEditor = DetailRecordEditor(detailInteractions, backlogRepository, undoController)

    private val detailExtras = MutableStateFlow(DetailExtras())

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
                val bangumiId = runCatchingCancellable { resolveBangumiSubjectId(work) }.getOrNull()
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
        if (workId.isNotBlank()) {
            viewModelScope.launch {
                runCatchingCancellable { detailInteractions.addRecentlyViewed(workId) }
            }
        }
        // 冷启动时先用已缓存特征重建画像；仍不可用且存在评分作品时，每进程至多联网补齐一次。
        // 后台执行，不阻塞 UI。
        viewModelScope.launch {
            delay(DETAIL_FIRST_PAINT_GRACE_MS)
            runCatchingCancellable { tasteAnalysis.ensureReady() }
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
        val linked = runCatchingCancellable { visualNovelRepository.linkBangumiIfMissing(detail) }.getOrNull() ?: return
        detailExtras.value = detailExtras.value.copy(vndbDetail = linked)
        ratings.value = runCatchingCancellable { workRepository.aggregateRatingsCached(workId) }.getOrNull()
    }

    /**
     * 尽早确保作品存在于本地（数字 id = Bangumi subjectId 时联网补全），完成后置 [workEnsured]。
     * 在此之前 UI 维持加载态，避免跳转瞬间闪现「暂无内容」。
     */
    private fun ensureWorkLoaded() {
        viewModelScope.launch {
            runCatchingCancellable {
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
                launch { runCatchingCancellable { workRepository.aggregateRatings(workId, forceRefresh = true) } }
                launch { runCatchingCancellable { refreshPersonalState(forceRefresh = true) } }
                if (workId.startsWith("v", ignoreCase = true)) launch { refreshVndbDetail() }
                loadExtras(forceRefresh = true)
            }
            _refreshing.value = false
        }
    }

    private suspend fun refreshVndbDetail() {
        val result = runCatchingCancellable { visualNovelRepository.detail(workId, forceRefresh = true) }.getOrNull()
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
            val cached = runCatchingCancellable { workRepository.aggregateRatingsCached(workId) }.getOrNull()
            if (cached != null) ratings.value = cached

            // 仓库按来源逐行落库，上方观察器会立即发布每个结果，不等待整条跨源补齐链完成。
            runCatchingCancellable { workRepository.aggregateRatings(workId, forceRefresh) }
        }
        viewModelScope.launch { runCatchingCancellable { refreshPersonalState(forceRefresh) } }
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
     * 生成无剧透评价雷达。等待作品就绪后交给 [DetailRadarLoader]：未配置 AI 时自动回退本地规则版，保证雷达可展示。
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
            radarLoader.load(
                work = work,
                myComment = myCollection.value?.shortReview,
                forceRefresh = forceRefresh,
                onAiFallback = { _recordMessage.value = it },
                publish = { radar.value = it },
            )
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
                        ratings.value =
                            runCatchingCancellable { workRepository.aggregateRatingsCached(work.id) }.getOrNull()
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
            val completed = runCatchingCancellable { collectBangumiExtras(work, subjectId, forceRefresh) }
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
        vndbTranslator.translate(detail)?.let { applyVndbTranslation(detail, it) }
    }

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
                runCatchingCancellable { backlogRepository.addAll(works.distinctBy { it.id }) }
            }
        }
    }

    /**
     * 点击 AI 匹配度分析按钮后：
     * 1. 未配置 AI → [AiMatchUi.NotConfigured]（提示可用本地模型估计），不发请求。
     * 2. 已配置 → [AiMatchUi.Loading] → [DetailAiMatchAnalyzer] 组装输入并经 [AiEngine] 运行（成本已确认）。
     * 3. 成功 → [AiMatchUi.Result]；低置信 / 失败 → [AiMatchUi.Error]，提示已回退本地模型，页面不无响应。
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
            _aiMatch.value = aiMatchAnalyzer.analyze(work, tasteProfile.value, ratings.value)
        }
    }

    /**
     * 编辑「我的记录」并回写 Bangumi（状态 / 评分 / 进度 / 短评），由 [DetailRecordEditor] 完成；结果提示写进 [recordMessage]。
     */
    fun onUpdateMyRecord(
        statusLabel: String?,
        rating: Int?,
        progress: Int?,
        comment: String?,
        tags: List<String>? = null,
        private: Boolean = false,
    ) {
        val edit = RecordEdit(statusLabel, rating, progress, comment, tags, private)
        val work = currentWork
        viewModelScope.launch {
            recordEditor.save(workId, work, edit)?.let { _recordMessage.value = it }
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
            _recordMessage.value = recordEditor.syncVndbToBangumi(work)
        }
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

private data class TasteInputs(
    val work: Work?,
    val rating: Int?,
    val profile: AdvancedTasteProfile?,
    val ratings: RatingAggregate?,
)

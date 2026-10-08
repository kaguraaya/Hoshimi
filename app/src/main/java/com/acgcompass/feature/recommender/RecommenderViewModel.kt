package com.acgcompass.feature.recommender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.TagNoise
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.recommend.RecommendSimilarity
import com.acgcompass.domain.recommend.SessionFit
import com.acgcompass.domain.recommend.TonightRanker
import com.acgcompass.domain.recommend.intent.IntentExplain
import com.acgcompass.domain.recommend.intent.IntentMatch
import com.acgcompass.domain.recommend.intent.IntentMatcher
import com.acgcompass.domain.recommend.intent.IntentTagDictionary
import com.acgcompass.domain.recommend.intent.TagRepresentativeness
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.usecase.PersonalTasteScorer
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「今晚看什么」推荐器 ViewModel。
 * MVVM + Hilt + StateFlow。本地规则推荐路径（无 AI key 时的默认实现）。
 *
 * 职责：
 * - 持有时间、标签、接受程度和候选池输入状态。
 * - 从本地候选中执行时间、状态、风险和标签硬过滤，再按个人口味与社区质量精排。
 * - 对跨源重复作品优先使用 Bangumi 代表，并记录近期曝光以维持推荐多样性。
 *
 * 韧性：抽取异常兜底为 [UiState.Error]，绝不让页面崩溃。
 *
 */
@HiltViewModel
class RecommenderViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val tasteProfileRepository: TasteProfileRepository,
    private val personalTasteScorer: PersonalTasteScorer,
    private val userCollectionDao: UserCollectionDao,
    private val settingsDataStore: SettingsDataStore,
    private val tasteEngine: TasteEngine,
    private val exposureDao: RecommendationExposureDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // 首页「今日状态」可携带预填标签进入（导航参数 presetTags，逗号分隔）。Navigation 已解码，直接切分。
    private val presetTags: Set<String> =
        savedStateHandle.get<String>(RECOMMENDER_ARG_TAGS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private val _input = MutableStateFlow(RecommenderInput(selectedTags = presetTags))
    val input: StateFlow<RecommenderInput> = _input.asStateFlow()

    private val _result = MutableStateFlow<UiState<List<RecommendationUiModel>>>(INITIAL_STATE)
    val result: StateFlow<UiState<List<RecommendationUiModel>>> = _result.asStateFlow()

    /**
     * 候选池作品的真实 Bangumi 社区标签（频次降序、过滤元数据噪声），作为「想看的标签」筛选项的
     * 动态来源。随候选池切换重算；候选无标签时为空（UI 提示补充待补池 / 切换全部作品）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val availableTags: StateFlow<List<String>> =
        _input
            .map { it.candidatePool }
            .distinctUntilChanged()
            .mapLatest { pool -> computeAvailableTags(pool) }
            // 统计候选池标签同样是纯计算，放到后台线程，切换候选池时界面不卡。
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 进入推荐页只从本地缓存恢复画像；远端特征补齐由独立后台任务负责。
        viewModelScope.launch { runCatching { tasteEngine.ensureReady() } }
    }

    // region 输入选择

    /** 选择时间预算（单选）。 */
    fun onSelectTime(time: TimeBudget) {
        _input.update { it.copy(time = time) }
    }

    /** 切换标签（多选）。标签来自候选池作品的真实 Bangumi 社区标签（[availableTags]）。 */
    fun onToggleTag(tag: String) {
        _input.update { current ->
            val next = if (tag in current.selectedTags) current.selectedTags - tag else current.selectedTags + tag
            current.copy(selectedTags = next)
        }
    }

    /** 切换接受程度（多选）。 */
    fun onToggleAcceptance(option: AcceptanceOption) {
        _input.update { current ->
            val next = if (option in current.acceptances) {
                current.acceptances - option
            } else {
                current.acceptances + option
            }
            current.copy(acceptances = next)
        }
    }

    /** 切换单一推荐模式：开启后仅给一个推荐。 */
    fun onToggleIndecisionMode() {
        _input.update { it.copy(indecisionMode = !it.indecisionMode) }
    }

    /** 切换期末周保护模式：过滤长篇 / 致郁 / 高上头 / 未完结。 */
    fun onToggleFinalsProtection() {
        _input.update { it.copy(finalsProtectionMode = !it.finalsProtectionMode) }
    }

    /** 切换深夜提醒模式：温柔提醒早点休息。 */
    fun onToggleLateNight() {
        _input.update { it.copy(lateNightMode = !it.lateNightMode) }
    }

    /**
     * 选择待补池或全部作品作为候选池；切换时保留已选标签，
     * 选好标签切到「全部作品」后标签全没了、需重选。标签是「命中其一即可」的软筛选，且 UI 的 displayTags
     * 会并入已选标签照常展示/高亮；新池若无该题材作品，仅该档推荐变少，不会出错。
     */
    fun onSelectCandidatePool(pool: CandidatePool) {
        _input.update { it.copy(candidatePool = pool) }
    }

    // endregion

    // region 提交 → 三推荐

    /**
     * 提交输入，生成三推荐。未选择时间则保持初始空态提示。
     *
     * 逐档抽取：稳妥 → 探索 → 随机，逐次把已抽中作品加入排除集，保证互不重复。
     * 若某档无满足约束的候选则跳过；三档均无候选时呈现空态（不伪造）。
     */
    fun onSubmit() {
        val current = _input.value
        if (!current.canSubmit) {
            _result.value = INITIAL_STATE
            return
        }
        _result.value = UiState.Loading
        // 候选去重、硬过滤、意图匹配和排序都是纯计算，几千部作品时要好几百毫秒：放到后台线程，
        // 主线程只负责画加载动画。
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val candidates = loadCandidates(current.candidatePool)
                _result.value = recommendFromWorks(current, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _result.value = UiState.Error(AppError.Server())
            }
        }
    }

    // endregion

    /** 按候选池加载候选作品（跨源去重、优先 Bangumi 代表）。供推荐与动态标签统计共用。 */
    private suspend fun loadCandidates(pool: CandidatePool): List<Work> {
        val allWorks = workRepository.observeWorks().first()
        return when (pool) {
            CandidatePool.ALL_WORKS ->
                dedupePreferBangumi(allWorks.filter { it.mediaType == MediaType.ANIME })
            CandidatePool.BACKLOG -> {
                val backlogIds = backlogRepository
                    .observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC)
                    .first()
                    .map { it.workId }
                    .toSet()
                dedupePreferBangumi(allWorks.filter { it.id in backlogIds })
            }
        }
    }

    /**
     * 统计候选池作品的真实社区标签作为「想看的标签」筛选项（频次降序，取前 N）。
     * 仅保留 [TasteTagTaxonomy.isSelectableGenre] 白名单内的题材标签，剔除厂商、人物名、梗和
     * 声优/时间等噪声——用户只想按「战斗、奇幻」这类题材筛选；同时减少 chip 数量，缓解展开/下滑掉帧。
     *
     * 按候选池规模区分标签上限；全部作品池的题材更丰富，
     * 放宽到 [ALL_WORKS_TAG_OPTIONS]；待补池维持精简 [MAX_TAG_OPTIONS]。仍按频次降序，使**主流热门**
     * 标签（标注人数多、频次高）优先展示，且随候选池内容动态增减。
     */
    private suspend fun computeAvailableTags(pool: CandidatePool): List<String> {
        val limit = if (pool == CandidatePool.ALL_WORKS) ALL_WORKS_TAG_OPTIONS else MAX_TAG_OPTIONS
        return loadCandidates(pool)
            .flatMap { w -> w.tags.map { it.name } }
            .map { TagNoise.clean(it) }
            .filter { TasteTagTaxonomy.isSelectableGenre(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    /**
     * 标签感知 + 个性化推荐（两个候选池共用）。今晚分 = **个人口味（主导）** + 所选标签覆盖 + 时间拟合 +
     * 社区口碑 + 新鲜度 / 补完轻松度 − 重复推荐惩罚（[TonightRanker]）。个人口味由共享 [PersonalTasteScorer] 计算（作品标签 × 你高/低分
     * 标签的加权重合），故**即使不选任何标签**也能按你的长期口味个性化排序，而非只推社区高分热门。
     *
     * 选了标签时仍硬筛（必须命中至少一个）；排除已看过/抛弃；三档各有分工（单一推荐只出稳妥档）。
     */
    private suspend fun recommendFromWorks(
        input: RecommenderInput,
        candidates: List<Work>,
    ): UiState<List<RecommendationUiModel>> {
        val taste: TasteProfile? = tasteProfileRepository.observeTasteProfile().first()
        // 推荐热路径只读取本地特征；后台补齐不会阻塞提交。画像尚未就绪时回退到
        // PersonalTasteScorer，继续使用个人标签偏好完成即时排序。
        runCatching { tasteEngine.rebuildFromCache() }
        // 用户可调的社区评分下限与口味匹配度阈值。
        val minScore = settingsDataStore.recommendMinCommunityScore.first()
        val tasteThreshold = settingsDataStore.tasteMatchThreshold.first()
        val excludeIds = runCatching {
            userCollectionDao.getAll()
                .filter { it.status == "看过" || it.status == "抛弃" }
                .map { it.localWorkId }
                .toSet()
        }.getOrDefault(emptySet())

        // 用户直接选择真实标签（来自候选池作品的 Bangumi 社区标签）；命中即匹配。
        // chip 标签是清洗后的社区标签，而作品原始标签未清洗——两侧口径不同会导致「选了却命不中」。
        // 统一清洗后再比较（下方硬筛 / 覆盖率均用清洗后的标签）。
        val selectedTags = input.selectedTags.map { TagNoise.clean(it) }.filter { it.isNotEmpty() }.toSet()
        val tagSelected = selectedTags.isNotEmpty()
        val intentAliases: Set<String> = selectedTags.flatMapTo(HashSet()) { tag ->
            IntentTagDictionary.bundleFor(tag).let { it.requiredTags + it.synonymTags }
        }

        val excludeUnfinished = AcceptanceOption.NO_UNFINISHED in input.acceptances

        // 第一步：硬过滤（仅裁剪，不打分）——媒介/时间/已看过抛弃/期末保护/未完结/不要X/标签。
        val prefiltered = candidates.asSequence()
            .filter { w ->
                w.mediaType == MediaType.ANIME && w.id !in excludeIds &&
                    // 单集都放不进这段时间（20 分钟配剧场版）的直接排除；时长未知放行。
                    TonightRanker.episodeFitsBudget(w.units.episodeMinutes, input.time?.availableMinutes) &&
                    // 期末保护模式——排除长篇/未完结/高耗能（致郁·上头·烧脑）。
                    (!input.finalsProtectionMode || passesFinalsProtection(w))
            }
            .mapNotNull { w ->
                val tagNames = w.tags.map { it.name }
                // 与所选标签同口径清洗，保证「chip 是清洗后名、作品是原始名」也能命中。
                val tagNamesClean = tagNames.map { TagNoise.clean(it) }
                // 硬排除：未完结（不要未完结）/ 含「不要X」关键词的作品直接淘汰。
                if (excludeUnfinished && w.status != com.acgcompass.domain.model.ReleaseStatus.FINISHED) {
                    return@mapNotNull null
                }
                // 标签召回：选择标签后至少要沾到一个所选意图（标签本身、包含关系或意图包里的近义标签）。
                // 这里只负责「别把完全无关的作品放进来」，是否真的契合由下方的意图匹配决定。
                val matched = tagNamesClean.filter { t ->
                    selectedTags.any { sel -> t == sel || t.contains(sel) } || t.lowercase() in intentAliases
                }
                if (tagSelected && matched.isEmpty()) return@mapNotNull null
                val hitSelected = selectedTags.count { sel -> tagNamesClean.any { t -> t == sel || t.contains(sel) } }
                Triple(w, matched.take(3), hitSelected)
            }
            .toList()

        // 标签/接受程度为硬性筛选——筛完无候选则返回空态，提示放宽条件（不硬塞不相干作品）。
        if (prefiltered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        data class Scored(
            val work: Work,
            val matchedSelected: List<String>,
            val hitSelected: Int,
            val matchedHigh: List<String>,
            val personal: Float,
            val mean10: Float?,
            val bayes10: Float?,
            val tasteFraction: Float?,
            val tasteAvailable: Boolean,
            val intent: IntentMatch?,
            val predictedRating: Double? = null,
        )

        // 重复推荐冷却——只看「今晚看什么」自己的曝光（探索队列的曝光不算，否则刚右滑进待补池的作品
        // 会在这里被藏两周）。最近 [HARD_REPEAT_MS] 内推过的本次直接回避（保证「再来一次」能换一批），
        // 更早的曝光交给排序器做随时间衰减的软惩罚；回避后候选为空则忽略冷却（绝不无结果）。
        val now = System.currentTimeMillis()
        val exposureAt: Map<String, Long> = runCatching {
            exposureDao.exposuresInContextSince(TONIGHT_CONTEXT, now - REPEAT_COOLDOWN_MS)
                .associate { it.subjectId to it.exposedAt }
        }.getOrDefault(emptyMap())
        val justShown = exposureAt.filterValues { now - it < HARD_REPEAT_MS }.keys
        val workingSet = prefiltered.filterNot { it.first.id in justShown }.ifEmpty { prefiltered }

        // 第二步：口味打分。优先使用 12 维口味引擎的校准匹配度（0–100）；
        // 引擎对该候选不可用（无特征 / 非 Bangumi / 画像未建）时回退共享 [PersonalTasteScorer] 旧标签重合估计。
        val engineScores: Map<String, com.acgcompass.domain.taste.TasteMatchResult> = runCatching {
            tasteEngine.scoreBatch(
                workingSet.mapNotNull { subjectIdOf(it.first) },
                networkBudget = TONIGHT_FEATURE_BUDGET,
            )
        }.getOrDefault(emptyMap())

        // 当晚意图匹配：按标签在作品里的代表性（标注人数 + 名次）判断「这部是不是今晚想看的那一类」，
        // 多个标签按 soft-AND 组合处理。有缓存特征时用带标注人数的标签，否则退回作品标签的顺序。
        val featureById = if (tagSelected) {
            tasteEngine.cachedFeatures(workingSet.mapNotNull { subjectIdOf(it.first) })
        } else {
            emptyMap()
        }
        val intentById: Map<String, IntentMatch> = if (tagSelected) {
            workingSet.mapNotNull { (w, _, _) ->
                val feature = subjectIdOf(w)?.let { featureById[it] }
                val rep = TagRepresentativeness(
                    tags = feature?.tagCounts?.takeIf { it.isNotEmpty() } ?: w.tags.map { TagCount(it.name, 0) },
                    episodes = feature?.eps?.takeIf { it > 0 } ?: w.units.episodes ?: 0,
                )
                IntentMatcher.match(selectedTags, rep)?.let { w.id to it }
            }.toMap()
        } else {
            emptyMap()
        }

        // 社区评分逐部查库，候选池大（全部作品）时很慢：先按「口味 + 意图」粗排，只给前
        // [RATING_LOOKUP_POOL] 名查评分并进入精排，排在后面的本来也不可能被选中。
        val preRanked = workingSet.map { triple ->
            val w = triple.first
            val engine = subjectIdOf(w)?.let { engineScores[it] }
            val roughTaste = engine?.let { it.satisfaction / 100f }
                ?: personalTasteScorer.score(w, taste, null).personal
            triple to (roughTaste + (intentById[w.id]?.fit?.toFloat() ?: 0f))
        }.sortedByDescending { it.second }.take(RATING_LOOKUP_POOL).map { it.first }

        val ranked = preRanked.map { (w, matchedSelected, hitSelected) ->
            val agg = workRepository.aggregateRatingsCached(w.id)
            val mean = agg.mean10()
            val votes = agg.totalVotes()
            // 贝叶斯均分：评分人数少的高分被拉回先验，避免「三个人打 9.5」压过大众口碑。
            val bayes = if (mean != null && votes > 0f) {
                (votes / (votes + BAYES_PRIOR_VOTES)) * mean +
                    (BAYES_PRIOR_VOTES / (votes + BAYES_PRIOR_VOTES)) * BAYES_PRIOR_MEAN
            } else {
                mean
            }
            val engine = subjectIdOf(w)?.let { engineScores[it] }
            if (engine != null) {
                // 排序用「预计满意度」（经真实评分数据验证的评分预测）；展示与阈值仍用口味匹配度。
                val frac = (engine.score / 100f).coerceIn(0f, 1f)
                Scored(
                    w,
                    matchedSelected,
                    hitSelected,
                    // 只展示正向贡献的维度——负向项不能写成「契合你的口味」。
                    engine.positiveReasons.take(3).map { it.label },
                    (engine.satisfaction / 100f).coerceIn(0f, 1f),
                    mean,
                    bayes,
                    frac,
                    true,
                    intentById[w.id],
                    engine.prediction?.rating,
                )
            } else {
                val ts = personalTasteScorer.score(w, taste, bayes)
                Scored(
                    w, matchedSelected, hitSelected, ts.matchedHighTags.take(3), ts.personal, mean, bayes,
                    tasteFraction = if (ts.available) ts.fraction else null,
                    tasteAvailable = ts.available,
                    intent = intentById[w.id],
                )
            }
        }

        // 推荐质量约束：
        // 1) 社区分下限：有评分则须 >= minScore；无评分（null）放行。可作为软护栏在口味达标集合内兜底放宽。
        // 2) 口味下限：阈值为 0 时不施加约束；
        //    阈值大于 0 时是硬约束，不因候选不足而放宽。
        val tasteFloor = tasteThreshold.coerceAtLeast(0f)
        fun passesCommunity(s: Scored): Boolean = s.mean10 == null || s.mean10 >= minScore
        fun passesTaste(s: Scored): Boolean =
            tasteFloor <= 0f || !s.tasteAvailable || s.tasteFraction == null || s.tasteFraction >= tasteFloor
        // 口味下限是硬约束；社区分下限仅在口味达标集合内作为可放宽的软护栏。
        val tasteEligible = ranked.filter { passesTaste(it) }
        if (tasteEligible.isEmpty()) {
            return UiState.Empty(if (tasteFloor > 0f) TASTE_FLOOR_CTA else NO_CANDIDATE_CTA)
        }
        val filtered = tasteEligible.filter { passesCommunity(it) }.ifEmpty { tasteEligible }
        if (filtered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        // 接受程度「可慢热 / 可致郁 / 可党争」：没勾选的风险气质按其在作品里的代表性做软惩罚
        // （不是硬排除——只沾一点的不受影响，主打这种气质的才明显下沉）；用户本次主动选了同名标签时不罚。
        val untolerated = RISK_TAGS.filterKeys { it !in input.acceptances }
            .filterValues { tags -> tags.none { it in selectedTags } }
        val riskById: Map<String, Pair<String, Double>> = if (untolerated.isEmpty()) {
            emptyMap()
        } else {
            val features = tasteEngine.cachedFeatures(filtered.mapNotNull { subjectIdOf(it.work) })
            filtered.mapNotNull { s ->
                val w = s.work
                val feature = subjectIdOf(w)?.let { features[it] }
                val rep = TagRepresentativeness(
                    tags = feature?.tagCounts?.takeIf { it.isNotEmpty() } ?: w.tags.map { TagCount(it.name, 0) },
                    episodes = feature?.eps?.takeIf { it > 0 } ?: w.units.episodes ?: 0,
                )
                untolerated.values.flatten().toSet().let(rep::strongest)
                    ?.takeIf { it.second >= RISK_MIN_REPRESENTATIVENESS }
                    ?.let { w.id to it }
            }.toMap()
        }

        val sessionById = filtered.associate { s ->
            val w = s.work
            w.id to SessionFit.evaluate(
                input.time?.availableMinutes,
                w.units.episodes,
                w.units.episodeMinutes,
                w.tags.map { it.name },
            )
        }

        // 第三步：今晚分精排 + 三档选片（稳妥 = 最高分；探索 = 与稳妥题材拉开；随机 = 高分段加权抽样），
        // 同系列最多占一个名额。详见 [TonightRanker]。
        val byId = filtered.associateBy { it.work.id }
        val picks = TonightRanker.pick(
            candidates = filtered.map { s ->
                val w = s.work
                TonightRanker.Candidate(
                    id = w.id,
                    taste = s.personal.toDouble(),
                    intentFit = if (tagSelected) s.intent?.fit ?: 0.0 else null,
                    dominantConflict = (
                        (s.intent?.dominantConflict ?: 0.0) + riskById[w.id]?.second.orZero() +
                            lateNightPenalty(input, w)
                        ).coerceAtMost(1.0),
                    scoreCap = s.intent?.scoreCap,
                    community10 = s.bayes10?.toDouble(),
                    sessionFit = sessionById.getValue(w.id).score,
                    timeWeight = SessionFit.weight(input.time?.availableMinutes),
                    completionEase = if (AcceptanceOption.LONG_FORM in input.acceptances) {
                        // 「长篇也行」：不因集数多而降权。
                        TonightRanker.completionEase(null, null)
                    } else {
                        TonightRanker.completionEase(
                            w.units.episodes,
                            when (w.status) {
                                com.acgcompass.domain.model.ReleaseStatus.FINISHED -> true
                                com.acgcompass.domain.model.ReleaseStatus.UNKNOWN -> null
                                else -> false
                            },
                        )
                    },
                    daysSinceExposure = exposureAt[w.id]?.let { (now - it).toDouble() / MILLIS_PER_DAY },
                    releaseYear = w.year,
                    franchiseKey = RecommendSimilarity.franchiseKey(
                        w.titles.ja?.takeIf { it.isNotBlank() } ?: w.titles.canonical,
                    ),
                    tags = RecommendSimilarity.genreFingerprint(w.tags.map { it.name }),
                )
            },
            count = if (input.indecisionMode) 1 else 3,
            currentYear = java.time.Year.now().value,
        )
        if (picks.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        recordExposure(picks.mapNotNull { byId[it.candidate.id]?.work })

        val safeTitle = picks.firstOrNull()?.let { byId[it.candidate.id]?.work?.titles?.canonical }
        val recommendations = picks.mapNotNull { pick ->
            val s = byId[pick.candidate.id] ?: return@mapNotNull null
            val kind = when (pick.slot) {
                TonightRanker.Slot.SAFE -> RecommendationKind.SAFE
                TonightRanker.Slot.EXPLORE -> RecommendationKind.GAMBLE
                TonightRanker.Slot.WILDCARD -> RecommendationKind.WILDCARD
            }
            val ratingPart = s.mean10?.let { "，社区均分约 %.1f".format(it) }.orEmpty() +
                s.predictedRating?.let { "，预计你会打 %.1f".format(it) }.orEmpty()
            // 理由聚焦「为什么这部贴合你」：优先所选标签，其次你高分常见标签，再退化为综合口碑。
            val fit = when {
                s.intent != null -> intentReason(s.intent.explain) + ratingPart
                s.matchedHigh.isNotEmpty() -> "契合你的长期口味：${s.matchedHigh.joinToString("、")}$ratingPart"
                else -> "综合口碑与你的口味挑选$ratingPart"
            }
            val riskNote = riskById[s.work.id]?.let { "；带明显的「${it.first}」气质" }.orEmpty()
            val timeNote = sessionById[s.work.id]?.let { fitResult ->
                val total = s.work.units.episodes?.let { e -> s.work.units.episodeMinutes?.let { e * it } }
                SessionFit.note(fitResult, total)
            }?.let { "；$it" }.orEmpty()
            // 探索档补一句「和稳妥选择哪里不同」，让三档的差别看得见。
            val contrast = if (kind == RecommendationKind.GAMBLE && safeTitle != null) {
                "；题材与《$safeTitle》错开，换个口味"
            } else {
                ""
            }
            RecommendationUiModel(
                kind = kind,
                workId = s.work.id,
                card = s.work.toRecommendCard(),
                reason = "${kind.tagline()}。$fit$timeNote$contrast$riskNote",
                matchPercent = s.tasteFraction?.takeIf { s.tasteAvailable }
                    ?.let { (it * PERCENT).toInt().coerceIn(0, PERCENT) },
            )
        }
        return UiState.Success(recommendations)
    }

    /** 把意图匹配结果写成一句人话：命中了什么、哪些只是沾边、缺了什么、主气质是否相冲。 */
    private fun intentReason(e: IntentExplain): String = buildString {
        when {
            e.matchedCombos.isNotEmpty() -> append("是一部典型的${e.matchedCombos.first()}")
            e.matchedStrongTags.isNotEmpty() ->
                append("${e.matchedStrongTags.joinToString("、") { "「$it」" }}是它的核心气质")
            e.matchedWeakTags.isNotEmpty() -> append("带有今晚想看的元素")
            else -> append("与今晚想看的标签只是沾边")
        }
        if (e.matchedWeakTags.isNotEmpty()) append("，${e.matchedWeakTags.joinToString("、") { "「$it」" }}成分较淡")
        if (e.missingImportantTags.isNotEmpty()) {
            append("，缺少${e.missingImportantTags.joinToString("、") { "「$it」" }}")
        }
        if (e.dominantConflicts.isNotEmpty()) append("，主气质更偏「${e.dominantConflicts.first()}」")
    }

    /**
     * 跨源去重并优先使用 Bangumi 代表。按规范化标题聚类后，再按来源优先级与标签完整度
     * 选择每组代表，避免同一作品的多源条目重复出现。
     */
    private fun dedupePreferBangumi(works: List<Work>): List<Work> {
        fun sourceRank(s: com.acgcompass.domain.model.SourceId): Int = when (s) {
            com.acgcompass.domain.model.SourceId.BANGUMI -> 0
            com.acgcompass.domain.model.SourceId.ANILIST -> 1
            com.acgcompass.domain.model.SourceId.MAL -> 2
            com.acgcompass.domain.model.SourceId.TENRAI -> 3
            com.acgcompass.domain.model.SourceId.JIKAN -> 4
            com.acgcompass.domain.model.SourceId.VNDB -> 5
        }
        return works
            .groupBy { w ->
                val key = w.titles.ja?.takeIf { it.isNotBlank() } ?: w.titles.canonical
                com.acgcompass.domain.matching.normalizeCompact(key)
            }
            .mapNotNull { (key, group) ->
                if (key.isBlank()) return@mapNotNull null
                group.minByOrNull { sourceRank(it.primarySource) * 1000 - it.tags.size }
            } + works.filter { w ->
            com.acgcompass.domain.matching.normalizeCompact(
                w.titles.ja?.takeIf { it.isNotBlank() } ?: w.titles.canonical,
            ).isBlank()
        }
    }

    /** 候选作品的 Bangumi subjectId（主源为 Bangumi 时即数字 work.id；否则 null → 引擎跳过、回退旧打分）。 */
    private fun subjectIdOf(w: Work): String? =
        if (w.primarySource == SourceId.BANGUMI) w.id.takeIf { it.toIntOrNull() != null } else null

    /** 记录本次「今晚看什么」曝光（支撑重复推荐冷却与后续点击统计）。best-effort，失败静默不影响结果。 */
    private suspend fun recordExposure(works: List<Work>) {
        runCatching {
            val now = System.currentTimeMillis()
            exposureDao.upsertAll(
                works.map { w ->
                    RecommendationExposureEntity(
                        id = "$TONIGHT_CONTEXT:${w.id}",
                        subjectId = subjectIdOf(w) ?: w.id,
                        context = TONIGHT_CONTEXT,
                        exposedAt = now,
                        clickedAt = null,
                        dismissedAt = null,
                    )
                },
            )
        }
    }

    /** 期末保护——排除长篇坑/未完结/高耗能（致郁·上头·烧脑）作品。 */
    /** 深夜模式：主打悬疑剧情、致郁、上头的作品容易一集接一集停不下来，往后排。 */
    private fun lateNightPenalty(input: RecommenderInput, w: Work): Double {
        if (!input.lateNightMode) return 0.0
        val heavy = SessionFit.SERIAL_TAGS + "上头"
        return if (w.tags.take(LATE_NIGHT_TAG_WINDOW).any { it.name in heavy }) LATE_NIGHT_PENALTY else 0.0
    }

    private fun passesFinalsProtection(w: Work): Boolean {
        if (w.completionCost == com.acgcompass.domain.model.CompletionCost.LONG_HAUL) return false
        if (w.status != com.acgcompass.domain.model.ReleaseStatus.FINISHED) return false
        val heavy = setOf("致郁", "上头", "烧脑")
        if (w.tags.any { it.name in heavy }) return false
        return true
    }

    /** 由 [Work] 构建推荐卡片模型（全部作品池无聚合评分，评分位留空 → UI 显示「暂无数据」）。 */
    private fun Work.toRecommendCard(): WorkCardUiModel = WorkCardUiModel(
        coverUrl = coverUrl,
        title = titles.canonical,
        subtitle = listOfNotNull(
            year?.toString(),
            airDate?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        type = when (mediaType) {
            MediaType.ANIME -> "动画"
            MediaType.MANGA -> "漫画"
            MediaType.NOVEL -> "小说"
            MediaType.GAME -> "游戏"
            MediaType.VN -> "视觉小说"
            MediaType.OTHER -> "其他"
        },
        ratingText = null,
        sourceTags = listOf(primarySource.recommenderSourceLabel()),
        moodRiskTags = tags.take(3).map { it.name },
    )

    private companion object {
        /** 初始 / 未提交态：引导用户选择条件并提交。 */
        val INITIAL_STATE = UiState.Empty(Cta(label = "选择条件后生成推荐", action = "submit"))

        /** 无满足条件候选时的空态：引导用户调整条件或补充待补池。 */
        val NO_CANDIDATE_CTA = Cta(label = "换个条件，或先去补充待补池", action = "adjust")

        /**
         * 设了口味匹配度阈值但无作品达标时的空态——提示调低阈值（不再退回推荐低于阈值的作品）。
         * 与 [NO_CANDIDATE_CTA] 区分，让用户明确是「阈值太高」而非「没候选」。
         */
        val TASTE_FLOOR_CTA = Cta(label = "没有达到口味匹配度阈值的作品，可到设置调低阈值", action = "adjust")

        /** 粗排后进入「查社区评分 + 精排」的候选规模上限（控制逐部查库的耗时）。 */
        const val RATING_LOOKUP_POOL = 120

        /** 贝叶斯均分先验：均值与等效票数。 */
        const val BAYES_PRIOR_MEAN = 6.5f
        const val BAYES_PRIOR_VOTES = 300f

        const val TONIGHT_CONTEXT = "tonight"

        /** 「可 X」对应的风险气质标签；未勾选时按代表性软惩罚。 */
        val RISK_TAGS: Map<AcceptanceOption, List<String>> = mapOf(
            AcceptanceOption.SLOW_BURN to listOf("慢热"),
            AcceptanceOption.DEPRESSING to listOf("致郁", "致郁系", "虐心", "胃痛", "黑深残"),
            AcceptanceOption.SHIPPING_WAR to listOf("党争", "修罗场"),
            AcceptanceOption.BRAIN_BURN to listOf("烧脑", "上头", "意识流"),
        )

        private const val LATE_NIGHT_TAG_WINDOW = 4
        private const val LATE_NIGHT_PENALTY = 0.5

        /** 风险气质的代表性达到多少才算「主打」并触发软惩罚。 */
        const val RISK_MIN_REPRESENTATIVENESS = 0.3
        const val PERCENT = 100
        const val MILLIS_PER_DAY = 24.0 * 3600 * 1000

        /** 这段时间内刚推过的作品本次直接回避，保证连续提交能换一批。 */
        const val HARD_REPEAT_MS = 36L * 3600 * 1000

        /** 动态标签筛选项最多展示个数（按频次取前 N，避免选项过多）。待补池用此值（精简）。 */
        const val MAX_TAG_OPTIONS = 30

        /**
         * 全部作品候选池的标签上限。放宽到此值，
         * 仍按频次降序保证主流热门优先；待补池维持 [MAX_TAG_OPTIONS] 精简。
         */
        const val ALL_WORKS_TAG_OPTIONS = 40

        /**
         * 精排热路径不联网补齐特征，只使用缓存特征；缺失时回退 [PersonalTasteScorer]。
         * 特征由初始化、导入或同步任务在后台补齐。
         */
        const val TONIGHT_FEATURE_BUDGET = 0

        /** 重复推荐软惩罚的回看窗口（惩罚随时间衰减，见 [TonightRanker]）。 */
        const val REPEAT_COOLDOWN_MS = 14L * 24 * 3600 * 1000
    }
}

/** 从多源评分聚合算「社区均分（0~10）」；各源按量纲归一，无有效评分返回 null（不伪造）。 */
private fun com.acgcompass.domain.model.RatingAggregate.mean10(): Float? {
    val values = perSource.entries.mapNotNull { (src, entry) ->
        val score = entry?.score ?: return@mapNotNull null
        if (com.acgcompass.domain.usecase.AggregateRatingsUseCase.isValidScore(src, score)) {
            com.acgcompass.domain.usecase.AggregateRatingsUseCase.normalizeToTen(src, score)
        } else {
            null
        }
    }
    return if (values.isEmpty()) null else values.average().toFloat()
}

/** 社区评分总人数（各源有效评分人数之和），用于贝叶斯加权（人数少的高分被拉回先验）。 */
private fun com.acgcompass.domain.model.RatingAggregate.totalVotes(): Float =
    perSource.values.sumOf { (it?.voteCount ?: 0).coerceAtLeast(0).toLong() }.toFloat()

private fun Double?.orZero(): Double = this ?: 0.0

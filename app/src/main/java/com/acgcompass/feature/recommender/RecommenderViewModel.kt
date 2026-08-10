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
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.PersonalTasteScorer
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
        viewModelScope.launch {
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
     * 标签感知 + 个性化推荐（两个候选池共用）。综合分 = **个人口味（主导）** + 所选标签命中加成 +
     * 社区评分（轻量护栏）+ 随机扰动。个人口味由共享 [PersonalTasteScorer] 计算（作品标签 × 你高/低分
     * 标签的加权重合），故**即使不选任何标签**也能按你的长期口味个性化排序，而非只推社区高分热门。
     *
     * 选了标签时仍硬筛（必须命中至少一个）；排除已看过/抛弃；按综合分降序取前 3（单一推荐取 1）。
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

        // 接受程度「不要X」→ 硬排除关键词（不要太累→烧脑/上头/意识流/电波）；「不要未完结」单独处理。
        val hardExcludeKeywords = input.acceptances.flatMap { ACCEPTANCE_EXCLUDE[it].orEmpty() }.toSet()
        val excludeUnfinished = AcceptanceOption.NO_UNFINISHED in input.acceptances

        // 第一步：硬过滤（仅裁剪，不打分）——媒介/时间/已看过抛弃/期末保护/未完结/不要X/标签。
        val prefiltered = candidates.asSequence()
            .filter { w ->
                w.mediaType == MediaType.ANIME && w.fitsTimeBudget(input.time) && w.id !in excludeIds &&
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
                if (hardExcludeKeywords.isNotEmpty() &&
                    tagNames.any { t -> hardExcludeKeywords.any { t.contains(it) } }
                ) {
                    return@mapNotNull null
                }
                // 标签硬筛：选择标签后必须命中至少一个（等值或包含）。
                val matched = tagNamesClean.filter { t -> selectedTags.any { sel -> t == sel || t.contains(sel) } }
                if (tagSelected && matched.isEmpty()) return@mapNotNull null
                // soft-AND 覆盖率按命中的所选标签去重计数。
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
            val combined: Float,
            val tasteFraction: Float?,
            val tasteAvailable: Boolean,
        )

        // 重复推荐冷却——最近 14 天已推过的作品本轮回避；若回避后候选为空则忽略冷却（绝不无结果）。
        val recentlyExposed = runCatching {
            exposureDao.exposedSince(System.currentTimeMillis() - REPEAT_COOLDOWN_MS).toSet()
        }.getOrDefault(emptySet())
        val workingSet = prefiltered.filterNot { it.first.id in recentlyExposed }.ifEmpty { prefiltered }

        // 第二步：精排打分。优先使用 12 维口味引擎的校准匹配度（0–100）；
        // 引擎对该候选不可用（无特征 / 非 Bangumi / 画像未建）时回退共享 [PersonalTasteScorer] 旧标签重合估计。
        // 社区评分仅作轻量质量护栏（贝叶斯均分，弱化低评分人数的高分），随机扰动保证多次提交的多样性。
        val engineScores: Map<String, com.acgcompass.domain.taste.TasteMatchResult> = runCatching {
            tasteEngine.scoreBatch(
                workingSet.mapNotNull { subjectIdOf(it.first) },
                networkBudget = TONIGHT_FEATURE_BUDGET,
            )
        }.getOrDefault(emptyMap())

        val ranked = workingSet.map { (w, matchedSelected, hitSelected) ->
            val agg = workRepository.aggregateRatingsCached(w.id)
            val mean = agg.mean10()
            val votes = agg.totalVotes()
            val prior = 6.5f
            val m = 300f
            val bayes = if (mean != null && votes > 0f) {
                (votes / (votes + m)) * mean + (m / (votes + m)) * prior
            } else {
                mean
            }
            val communityNudge = (bayes?.div(10f) ?: 0.5f).coerceIn(0f, 1f)
            // 用户显式选择标签时，按标签覆盖率增加当下意图权重。
            // 覆盖率使用平方项；全部命中保持 1.0，部分命中的权重降低，
            // 显著拉开「全中 vs 擦边」，让多选标签真正驱动排序而非命中 1 个即近似满足；未选标签时为 0。
            val intentBonus = if (tagSelected && selectedTags.isNotEmpty()) {
                val coverage = (hitSelected.toFloat() / selectedTags.size).coerceIn(0f, 1f)
                coverage * coverage * INTENT_COVERAGE_WEIGHT
            } else {
                0f
            }
            val engine = subjectIdOf(w)?.let { engineScores[it] }
            if (engine != null) {
                // 引擎匹配度 0–100 → 0~1 作为主导个人口味分量（已拉开差距）。
                val frac = (engine.score / 100f).coerceIn(0f, 1f)
                val combined = frac * 3f + intentBonus + communityNudge * 0.6f +
                    (Math.random().toFloat() * INTENT_RANDOM_JITTER)
                Scored(
                    w,
                    matchedSelected,
                    hitSelected,
                    engine.reasons.take(3).map { it.label },
                    frac,
                    mean,
                    combined,
                    frac,
                    true,
                )
            } else {
                val ts = personalTasteScorer.score(w, taste, bayes)
                val combined = ts.personal * 3f + intentBonus + communityNudge * 0.6f +
                    (Math.random().toFloat() * INTENT_RANDOM_JITTER)
                Scored(
                    w, matchedSelected, hitSelected, ts.matchedHighTags.take(3), ts.personal, mean, combined,
                    tasteFraction = if (ts.available) ts.fraction else null,
                    tasteAvailable = ts.available,
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
        val filtered = tasteEligible.filter { passesCommunity(it) }
            .ifEmpty { tasteEligible }
            .sortedByDescending { it.combined }
        if (filtered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        // 从综合分 top 段随机抽取，制造多样性；抽中后再按综合分排序（稳妥档=其中最贴合的一部）。
        val poolSize = if (input.indecisionMode) 6 else 12
        val topPool = filtered.take(poolSize)
        val takeCount = if (input.indecisionMode) 1 else 3
        // 多选至少两个标签表示强意图；优先保留命中所选标签最多的候选
        // 展示，避免它被 top 段随机抽样洗掉，导致「选了多个标签却没被尊重」。其余名额仍从池中随机抽取保多样性。
        // 复合排序键：覆盖数（hitSelected）优先，其次综合分（combined），故稳妥档=最贴合当下意图的一部。
        val picks = if (tagSelected && selectedTags.size > 1) {
            val best = topPool.maxByOrNull { it.hitSelected * 1000f + it.combined } ?: topPool.first()
            val rest = topPool.filter { it !== best }.shuffled().take((takeCount - 1).coerceAtLeast(0))
            (listOf(best) + rest).sortedByDescending { it.hitSelected * 1000f + it.combined }
        } else {
            topPool.shuffled().take(takeCount).sortedByDescending { it.combined }
        }
        if (picks.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        recordExposure(picks.map { it.work })

        val kinds = listOf(RecommendationKind.SAFE, RecommendationKind.GAMBLE, RecommendationKind.WILDCARD)
        val recommendations = picks.mapIndexed { idx, s ->
            val kind = kinds.getOrElse(idx) { RecommendationKind.WILDCARD }
            val ratingPart = s.mean10?.let { "，社区均分约 %.1f".format(it) }.orEmpty()
            // 理由聚焦「为什么这部贴合你」：优先所选标签，其次你高分常见标签，再退化为综合口碑。
            val reason = when {
                s.matchedSelected.isNotEmpty() -> {
                    val cov = if (selectedTags.size > 1) "（命中 ${s.hitSelected}/${selectedTags.size}）" else ""
                    "贴合你想看的标签：${s.matchedSelected.joinToString("、")}$cov$ratingPart"
                }
                s.matchedHigh.isNotEmpty() -> "契合你的长期口味：常给高分的「${s.matchedHigh.joinToString("、")}」$ratingPart"
                else -> "综合口碑与你的口味挑选$ratingPart"
            }
            RecommendationUiModel(
                kind = kind,
                workId = s.work.id,
                card = s.work.toRecommendCard(),
                reason = "${kind.tagline()}。$reason",
            )
        }
        return UiState.Success(recommendations)
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

    /** 记录本轮「今晚看什么」曝光（支撑重复推荐冷却与后续点击统计）。best-effort，失败静默不影响结果。 */
    private suspend fun recordExposure(works: List<Work>) {
        runCatching {
            val now = System.currentTimeMillis()
            exposureDao.upsertAll(
                works.map { w ->
                    RecommendationExposureEntity(
                        id = "tonight:${w.id}",
                        subjectId = subjectIdOf(w) ?: w.id,
                        context = "tonight",
                        exposedAt = now,
                        clickedAt = null,
                        dismissedAt = null,
                    )
                },
            )
        }
    }

    /** 期末保护——排除长篇坑/未完结/高耗能（致郁·上头·烧脑）作品。 */
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
        sourceTags = listOf(primarySource.name),
        moodRiskTags = tags.take(3).map { it.name },
    )

    private companion object {
        /** 三推荐生成顺序（逐档放宽），用于累积排除保证互不重复。 */
        val RECOMMENDATION_ORDER = listOf(
            RecommendationKind.SAFE,
            RecommendationKind.GAMBLE,
            RecommendationKind.WILDCARD,
        )

        /** 初始 / 未提交态：引导用户选择条件并提交。 */
        val INITIAL_STATE = UiState.Empty(Cta(label = "选择条件后生成推荐", action = "submit"))

        /** 无满足条件候选时的空态：引导用户调整条件或补充待补池。 */
        val NO_CANDIDATE_CTA = Cta(label = "换个条件，或先去补充待补池", action = "adjust")

        /**
         * 设了口味匹配度阈值但无作品达标时的空态——提示调低阈值（不再退回推荐低于阈值的作品）。
         * 与 [NO_CANDIDATE_CTA] 区分，让用户明确是「阈值太高」而非「没候选」。
         */
        val TASTE_FLOOR_CTA = Cta(label = "没有达到口味匹配度阈值的作品，可到设置调低阈值", action = "adjust")

        /** 全部作品池融入评分时，先按口味取的候选规模（之后用本地缓存评分二次排序）。 */
        const val RATING_BLEND_POOL = 30

        /** 动态标签筛选项最多展示个数（按频次取前 N，避免选项过多）。待补池用此值（精简）。 */
        const val MAX_TAG_OPTIONS = 30

        /**
         * 全部作品候选池的标签上限。放宽到此值，
         * 仍按频次降序保证主流热门优先；待补池维持 [MAX_TAG_OPTIONS] 精简。
         */
        const val ALL_WORKS_TAG_OPTIONS = 40

        /**
         * 意图覆盖率加成权重；用户主动选标签时按平方覆盖率乘以此值加分。
         * 全命中 +2.6（接近口味 frac×3 的最大 3.0），实现 soft-AND「尽量多命中所选标签」而非命中 1 个即满足；
         * 平方覆盖率使部分命中（如 1/2→0.25）被显著压低，进一步拉开「全中 vs 擦边」。
         */
        const val INTENT_COVERAGE_WEIGHT = 2.6f

        /**
         * 推荐综合分的随机扰动幅度。数值需低于标签覆盖差异，避免随机项主导排序。
         */
        const val INTENT_RANDOM_JITTER = 0.4f

        /**
         * 精排热路径不联网补齐特征，只使用缓存特征；缺失时回退 [PersonalTasteScorer]。
         * 特征由初始化、导入或同步任务在后台补齐。
         */
        const val TONIGHT_FEATURE_BUDGET = 0

        /** 重复推荐冷却窗口（最近此时长内推过的作品本轮回避）。 */
        const val REPEAT_COOLDOWN_MS = 14L * 24 * 3600 * 1000

        /** 接受程度「不要X」→ 硬排除的题材关键词（不要太累→烧脑/上头/意识流/电波）。 */
        val ACCEPTANCE_EXCLUDE: Map<AcceptanceOption, List<String>> = mapOf(
            AcceptanceOption.NO_TIRING to listOf("烧脑", "上头", "意识流", "电波"),
        )
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

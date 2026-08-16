package com.acgcompass.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingResult
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.recommend.ExploreRanker
import com.acgcompass.domain.recommend.RecommendSimilarity
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.WorkFeatureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 探索队列 ViewModel。使用 MVVM、Hilt 和 StateFlow；本地规则路径无需 AI 凭据。
 *
 * 生成一批（[BATCH_SIZE]）探索卡片：
 * - **候选召回**：本地单一可信源（含公共池）的动画作品，排除「已收藏（看过/在看/想看…）+ 已在待补池
 *   + 仍在冷却期内的探索曝光」，保证探索的是「还没接触」的新作品；
 * - **口味精排 + 组批**：12 维引擎 [TasteEngine.scoreBatch] 批量打分后交给 [ExploreRanker]——
 *   按历史左右滑学到的标签偏好修正、MMR 去同质化、同系列至多一张，并留约三成名额给「跨界」候选；
 * - **曝光记录**：卡片真正显示时才写入 `recommendation_exposure`（context=explore），冷却按结局分档。
 *
 * 交互：右滑 [onLike] → 加入待补池（[BacklogRepository.addAll] 去重）；左滑 [onSkip] → 记「暂不」。
 * 每次滑动都会更新反馈并重排尚未看到的卡片。
 * 韧性：生成异常兜底为 [ExploreQueueUiState.Error]，绝不崩溃。
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class ExploreQueueViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    private val userCollectionDao: UserCollectionDao,
    private val tasteEngine: TasteEngine,
    private val exposureDao: RecommendationExposureDao,
    private val bangumi: BangumiRemoteDataSource,
    private val workFeatureRepository: WorkFeatureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ExploreQueueUiState>(ExploreQueueUiState.Loading)
    val state: StateFlow<ExploreQueueUiState> = _state.asStateFlow()

    /** 当前批次作品对象（workId → Work），供左滑加入待补池（addAll 需 [Work]）。 */
    private val worksById = mutableMapOf<String, Work>()
    private var likedCount = 0
    private var skippedCount = 0
    private var generation = 0
    private var unscoredCandidates: List<Work> = emptyList()

    /** 当前批次卡片对应的排序输入（供滑动后重排尚未看到的卡片）。 */
    private val rankerCandidates = mutableMapOf<String, ExploreRanker.Candidate>()

    /** 历史 + 本次的左右滑反馈。 */
    private val feedback = mutableListOf<ExploreRanker.Feedback>()

    /** 本批已记过曝光的卡片（避免重复写库）。 */
    private val shownIds = mutableSetOf<String>()

    init {
        generate()
    }

    /** 生成 / 重新生成一批探索卡片（首屏与「再来一批」共用）。 */
    fun generate() {
        viewModelScope.launch {
            val currentGeneration = ++generation
            _state.value = ExploreQueueUiState.Loading
            likedCount = 0
            skippedCount = 0
            worksById.clear()
            rankerCandidates.clear()
            shownIds.clear()
            unscoredCandidates = emptyList()
            publish(
                runCatching { buildBatch() }
                    .getOrElse { ExploreQueueUiState.Error("生成探索队列失败，请稍后重试") },
            )
            if (unscoredCandidates.isNotEmpty()) enrichMissingMatches(currentGeneration)
        }
    }

    private suspend fun buildBatch(): ExploreQueueUiState {
        val all = workRepository.observeWorks().first()
        val collected = userCollectionDao.getAll().map { it.localWorkId }.toSet()
        val inBacklog = backlogRepository.observeBacklog().first().map { it.workId }.toSet()
        val now = System.currentTimeMillis()
        val history = exposureDao.exposuresInContextSince(EXPLORE_CONTEXT, now - FEEDBACK_WINDOW_MS)
        // 冷却按「上次的结局」分档：明确划走的很久不出现；只是看过没表态的，一周后可以再来。
        val cooled = history.filter { e ->
            val window = when {
                e.dismissedAt != null -> DISMISSED_COOLDOWN_MS
                e.clickedAt != null -> LIKED_COOLDOWN_MS
                else -> SEEN_COOLDOWN_MS
            }
            now - e.exposedAt < window
        }.mapTo(HashSet()) { it.subjectId }
        val excluded = collected + inBacklog + cooled

        val candidates = selectExploreCandidates(all, excluded)
        if (candidates.isEmpty()) {
            val msg = if (all.isEmpty()) {
                "本地候选不足：先到「发现 → 本季 / 榜单」加载作品池，或在「我的」同步 Bangumi 收藏"
            } else {
                "暂时没有新作品可探索：当前候选都浏览过了，去发现页加载更多，或稍后再来"
            }
            return ExploreQueueUiState.Empty(msg)
        }

        // 历史左右滑 → 标签级反馈（本次的滑动会继续追加，见 [recordFeedback]）。
        val worksAll = all.associateBy { it.id }
        feedback.clear()
        history.forEach { e ->
            val liked = e.clickedAt != null
            if (!liked && e.dismissedAt == null) return@forEach
            val tags = worksAll[e.subjectId]?.let(::fingerprintOf) ?: return@forEach
            if (tags.isNotEmpty()) feedback += ExploreRanker.Feedback(tags, liked)
        }
        val model = ExploreRanker.feedbackModel(feedback)

        // 首批已有真实分数的卡片先展示，其余候选独立补齐后渐进加入。
        val scores = tasteEngine.scoreBatch(candidates.map { it.id }, networkBudget = 0)
        // 待补齐的候选按「标签越全越优先」排，优先把信息量大的作品补出分数。
        unscoredCandidates = candidates.filter { it.id !in scores }.sortedByDescending { it.tags.size }
        val byId = candidates.associateBy { it.id }
        val ranked = ExploreRanker.buildBatch(
            candidates = candidates.mapNotNull { work -> scores[work.id]?.let { rankerCandidate(work, it) } },
            model = model,
            batchSize = BATCH_SIZE,
        )

        if (ranked.isEmpty()) {
            return ExploreQueueUiState.Preparing(
                completed = 0,
                total = unscoredCandidates.take(BACKGROUND_ENRICH_LIMIT).size,
            )
        }

        val cards = ranked.mapNotNull { r ->
            val work = byId[r.candidate.id] ?: return@mapNotNull null
            worksById[work.id] = work
            rankerCandidates[work.id] = r.candidate
            work.toExploreCard(scores[work.id], discovery = r.discovery)
        }
        return ExploreQueueUiState.Ready(cards = cards, index = 0)
    }

    private fun fingerprintOf(work: Work): Set<String> = RecommendSimilarity.genreFingerprint(work.tags.map { it.name })

    private fun rankerCandidate(work: Work, match: TasteMatchResult): ExploreRanker.Candidate = ExploreRanker.Candidate(
        id = work.id,
        // 排序用预计满意度（看完大概有多满意）；卡片上展示的仍是口味匹配度。
        matchScore = match.satisfaction,
        familiarity = match.familiarity,
        community10 = match.communityScore10,
        votes = match.communityVotes,
        franchiseKey = RecommendSimilarity.franchiseKey(
            work.titles.ja?.takeIf { it.isNotBlank() } ?: work.titles.canonical,
        ),
        tags = fingerprintOf(work),
    )

    /**
     * 发布状态；若为 [ExploreQueueUiState.Ready]，把**当前这张**卡片记为已曝光。
     * 曝光只在卡片真正出现在用户眼前时记录：如果组批时就把整批写入冷却，
     * 用户看了三张就退出，剩下七张没见过的也要被藏两周。
     */
    private fun publish(newState: ExploreQueueUiState) {
        _state.value = newState
        val current = (newState as? ExploreQueueUiState.Ready)?.let { it.cards.getOrNull(it.index) }
        if (current != null && shownIds.add(current.workId)) {
            viewModelScope.launch { runCatchingResult { exposureDao.upsert(exposureRow(current.workId)) } }
        }
    }

    private fun exposureRow(workId: String, clickedAt: Long? = null, dismissedAt: Long? = null) =
        RecommendationExposureEntity(
            id = "$EXPLORE_CONTEXT:$workId",
            subjectId = workId,
            context = EXPLORE_CONTEXT,
            exposedAt = System.currentTimeMillis(),
            clickedAt = clickedAt,
            dismissedAt = dismissedAt,
        )

    private suspend fun enrichMissingMatches(expectedGeneration: Int) {
        val candidates = unscoredCandidates.take(BACKGROUND_ENRICH_LIMIT)
        val preparingCards = mutableListOf<ExploreCardUiModel>()
        for ((position, work) in candidates.withIndex()) {
            if (generation != expectedGeneration) return
            workFeatureRepository.ensureBasic(listOf(work.id), networkBudget = 1)
            val match = tasteEngine.scoreBatch(listOf(work.id), networkBudget = 0)[work.id] ?: continue
            if (generation != expectedGeneration) return
            worksById[work.id] = work
            rankerCandidates[work.id] = rankerCandidate(work, match)
            acceptEnrichedCard(
                position = position,
                lastPosition = candidates.lastIndex,
                newCard = work.toExploreCard(match),
                preparingCards = preparingCards,
            )
        }
        finalizePreparingCards(preparingCards)
    }

    private fun acceptEnrichedCard(
        position: Int,
        lastPosition: Int,
        newCard: ExploreCardUiModel,
        preparingCards: MutableList<ExploreCardUiModel>,
    ) {
        val current = _state.value
        when (current) {
            is ExploreQueueUiState.Preparing -> {
                preparingCards += newCard
                if (preparingCards.size < MIN_READY_CARDS && position < lastPosition) {
                    _state.value = current.copy(completed = position + 1)
                } else {
                    val ranked = preparingCards.sortedByDescending { it.tasteFraction }
                    publish(ExploreQueueUiState.Ready(cards = ranked, index = 0))
                }
            }
            is ExploreQueueUiState.Ready -> {
                val canAdd = current.cards.none { it.workId == newCard.workId } && current.cards.size < BATCH_SIZE
                if (canAdd) {
                    val cards = insertExploreCardPreservingProgress(current.cards, current.index, newCard)
                    _state.value = current.copy(cards = cards)
                }
            }
            else -> Unit
        }
    }

    private fun finalizePreparingCards(cards: List<ExploreCardUiModel>) {
        if (_state.value !is ExploreQueueUiState.Preparing) return
        if (cards.isEmpty()) {
            _state.value = ExploreQueueUiState.Empty("暂时没有已准备好的口味匹配候选，请稍后再试")
        } else {
            val ranked = cards.sortedByDescending { it.tasteFraction }
            publish(ExploreQueueUiState.Ready(cards = ranked, index = 0))
        }
    }

    /** 右滑：加入待补池（去重）并前进。 */
    fun onLike() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            worksById[card.workId]?.let { work -> runCatchingResult { backlogRepository.addAll(listOf(work)) } }
            // 整行写入（而非 UPDATE）：不依赖「曝光行已落库」的时序。clickedAt 记录「加入」，供反馈学习。
            runCatchingResult { exposureDao.upsert(exposureRow(card.workId, clickedAt = now)) }
        }
        likedCount++
        recordFeedback(card.workId, liked = true)
        advance(st)
    }

    /** 左滑：记为「暂不」（较长冷却内不推荐）并前进。 */
    fun onSkip() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatchingResult { exposureDao.upsert(exposureRow(card.workId, dismissedAt = now)) }
        }
        skippedCount++
        recordFeedback(card.workId, liked = false)
        advance(st)
    }

    private fun recordFeedback(workId: String, liked: Boolean) {
        val tags = rankerCandidates[workId]?.tags.orEmpty()
        if (tags.isNotEmpty()) feedback += ExploreRanker.Feedback(tags, liked)
    }

    private fun advance(st: ExploreQueueUiState.Ready) {
        val next = st.index + 1
        if (next >= st.cards.size) {
            publish(ExploreQueueUiState.Finished(liked = likedCount, skipped = skippedCount))
            return
        }
        // 用最新反馈重排「下一张之后」的卡片：连续划走同一题材，后面同题材的卡片自动下沉。
        // 下一张（即将显示）保持不动，保证滑动后的衔接稳定。
        val model = ExploreRanker.feedbackModel(feedback)
        val cards = ExploreRanker.rerankRemaining(st.cards, keepUntil = next, model = model) { card ->
            rankerCandidates[card.workId]
                ?: ExploreRanker.Candidate(card.workId, (card.tasteFraction * PERCENT).toInt())
        }
        publish(st.copy(cards = cards, index = next))
    }

    /** 正在联网补齐简介的 workId（去重，避免重复请求）。 */
    private val synopsisInFlight = mutableSetOf<String>()

    /** 已成功尝试过补齐简介的 workId（含「确实无简介」）——不再重试；失败不记，允许下次翻面重试。 */
    private val synopsisAttempted = mutableSetOf<String>()

    /**
     * 翻到背面时懒加载作品简介。探索候选多来自榜单 / 发现池（`WorkEntity` 无 `summary`），
     * 故此处按主源（Bangumi subjectId = 数字 workId）单条拉取补齐，成功后就地写回当前批次卡片，
     * 背面即时刷新。去重（[synopsisInFlight]）+ 已尝试标记（[synopsisAttempted]）避免重复请求；
     * 联网失败不标记、下次可重试；全程兜底不崩溃。
     */
    fun loadSynopsis(workId: String) {
        val ready = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = ready.cards.firstOrNull { it.workId == workId } ?: return
        if (card.synopsis != null || workId in synopsisAttempted || workId in synopsisInFlight) return
        val sid = workId.toIntOrNull() ?: return
        synopsisInFlight += workId
        updateCard(workId) { it.copy(synopsisLoading = true) }
        viewModelScope.launch {
            val res = runCatching { bangumi.getSubject(sid) }.getOrNull()
            synopsisInFlight -= workId
            val summary = (res as? AppResult.Success)?.data?.summary?.trim()?.takeIf { it.isNotBlank() }
            // 仅联网成功（含确实无简介）标记已尝试，失败保留可重试。
            if (res is AppResult.Success) synopsisAttempted += workId
            updateCard(workId) { it.copy(synopsis = summary, synopsisLoading = false) }
        }
    }

    /** 在当前 Ready 批次中按 workId 就地更新卡片（不改 index，保持翻面 / 滑动手势状态）。 */
    private fun updateCard(workId: String, transform: (ExploreCardUiModel) -> ExploreCardUiModel) {
        val ready = _state.value as? ExploreQueueUiState.Ready ?: return
        _state.value = ready.copy(cards = ready.cards.map { if (it.workId == workId) transform(it) else it })
    }

    private companion object {
        const val BATCH_SIZE = 10
        const val BACKGROUND_ENRICH_LIMIT = 20
        const val MIN_READY_CARDS = 3
        const val EXPLORE_CONTEXT = "explore"
        const val PERCENT = 100
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 只是出现过、没有表态的卡片：一周后可再次进入候选。 */
        const val SEEN_COOLDOWN_MS = 7 * DAY_MS

        /** 「暂不」的作品：一个半月内不出现（划走可能只是当时不想看，不宜藏太久）。 */
        const val DISMISSED_COOLDOWN_MS = 45 * DAY_MS

        /** 已加入待补池的作品本就被待补池排除；此冷却只兜住「加入后又移出」的情况。 */
        const val LIKED_COOLDOWN_MS = 30 * DAY_MS

        /** 反馈学习回看窗口（同时是冷却查询的最大范围）。 */
        const val FEEDBACK_WINDOW_MS = 180 * DAY_MS
    }
}

/** 同标题簇优先保留 Bangumi 规范作品，默认不展示 Jikan 条目。 */
internal fun selectExploreCandidates(all: List<Work>, excluded: Set<String>): List<Work> = all
    .asSequence()
    .filter {
        it.mediaType == MediaType.ANIME &&
            it.id !in excluded &&
            it.primarySource !in setOf(SourceId.TENRAI, SourceId.JIKAN)
    }
    .groupBy(::exploreTitleKey)
    .values
    .mapNotNull { cluster ->
        cluster.minWithOrNull(compareBy<Work>({ exploreSourceRank(it) }, { it.id }))
    }
    .toList()

internal fun insertExploreCardPreservingProgress(
    cards: List<ExploreCardUiModel>,
    currentIndex: Int,
    newCard: ExploreCardUiModel,
): List<ExploreCardUiModel> {
    val stableCount = (currentIndex + 1).coerceIn(0, cards.size)
    val stablePrefix = cards.take(stableCount)
    val remaining = (cards.drop(stableCount) + newCard).sortedByDescending { it.tasteFraction }
    return stablePrefix + remaining
}

private fun exploreTitleKey(work: Work): String = work.displayTitle()
    .lowercase()
    .filter(Char::isLetterOrDigit)
    .ifBlank { work.id }

private fun exploreSourceRank(work: Work): Int = when (work.primarySource) {
    SourceId.BANGUMI -> 0
    SourceId.ANILIST, SourceId.MAL -> 1
    SourceId.VNDB -> 2
    SourceId.TENRAI -> JIKAN_SOURCE_RANK
    SourceId.JIKAN -> JIKAN_SOURCE_RANK
}

private const val JIKAN_SOURCE_RANK = 3

package com.acgcompass.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.WorkRepository
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
 *   + 近 [COOLDOWN_MS] 曝光过」，保证探索的是「还没接触」的新作品；
 * - **口味精排**：12 维引擎 [TasteEngine.scoreBatch] 批量打分，按匹配度降序（加稳定微抖动避免每批雷同）；
 * - **曝光记录**：本批写入 `recommendation_exposure`（context=explore）支撑冷却，复用既有表（无需新表）。
 *
 * 交互：左滑 [onLike] → 加入待补池（[BacklogRepository.addAll] 去重）；右滑 [onSkip] → 记曝光略过。
 * 韧性：生成异常兜底为 [ExploreQueueUiState.Error]，绝不崩溃。
 */
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
            unscoredCandidates = emptyList()
            _state.value = runCatching { buildBatch() }
                .getOrElse { ExploreQueueUiState.Error("生成探索队列失败，请稍后重试") }
            if (unscoredCandidates.isNotEmpty()) enrichMissingMatches(currentGeneration)
        }
    }

    private suspend fun buildBatch(): ExploreQueueUiState {
        val all = workRepository.observeWorks().first()
        val collected = userCollectionDao.getAll().map { it.localWorkId }.toSet()
        val inBacklog = backlogRepository.observeBacklog().first().map { it.workId }.toSet()
        val cooled = exposureDao.exposedSince(System.currentTimeMillis() - COOLDOWN_MS).toSet()
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

        // 首批已有真实分数的卡片先展示，其余候选独立补齐后渐进加入。
        val scores = tasteEngine.scoreBatch(candidates.map { it.id }, networkBudget = 0)
        unscoredCandidates = candidates.filter { it.id !in scores }
        val ranked = candidates
            .mapNotNull { work -> scores[work.id]?.let { work to it } }
            .sortedByDescending { (work, match) ->
                match.score.toDouble() + work.id.hashCode().mod(7) * 0.4
            }
            .take(BATCH_SIZE)

        if (ranked.isEmpty()) {
            return ExploreQueueUiState.Preparing(
                completed = 0,
                total = unscoredCandidates.take(BACKGROUND_ENRICH_LIMIT).size,
            )
        }

        val now = System.currentTimeMillis()
        exposureDao.upsertAll(
            ranked.map { (work, _) ->
                RecommendationExposureEntity(
                    id = "$EXPLORE_CONTEXT:${work.id}",
                    subjectId = work.id,
                    context = EXPLORE_CONTEXT,
                    exposedAt = now,
                    clickedAt = null,
                    dismissedAt = null,
                )
            },
        )

        val cards = ranked.map { (work, match) ->
            worksById[work.id] = work
            work.toExploreCard(match)
        }
        return ExploreQueueUiState.Ready(cards = cards, index = 0)
    }

    private suspend fun enrichMissingMatches(expectedGeneration: Int) {
        val candidates = unscoredCandidates.take(BACKGROUND_ENRICH_LIMIT)
        val preparingCards = mutableListOf<ExploreCardUiModel>()
        for ((position, work) in candidates.withIndex()) {
            if (generation != expectedGeneration) return
            workFeatureRepository.ensureBasic(listOf(work.id), networkBudget = 1)
            val match = tasteEngine.scoreBatch(listOf(work.id), networkBudget = 0)[work.id] ?: continue
            if (generation != expectedGeneration) return
            worksById[work.id] = work
            acceptEnrichedCard(
                position = position,
                lastPosition = candidates.lastIndex,
                newCard = work.toExploreCard(match),
                preparingCards = preparingCards,
            )
        }
        finalizePreparingCards(preparingCards)
    }

    private suspend fun acceptEnrichedCard(
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
                    exposureDao.recordExploreExposures(ranked.map { it.workId })
                    _state.value = ExploreQueueUiState.Ready(cards = ranked, index = 0)
                }
            }
            is ExploreQueueUiState.Ready -> {
                val canAdd = current.cards.none { it.workId == newCard.workId } && current.cards.size < BATCH_SIZE
                if (canAdd) {
                    val cards = insertExploreCardPreservingProgress(current.cards, current.index, newCard)
                    exposureDao.recordExploreExposures(listOf(newCard.workId))
                    _state.value = current.copy(cards = cards)
                }
            }
            else -> Unit
        }
    }

    private suspend fun finalizePreparingCards(cards: List<ExploreCardUiModel>) {
        if (_state.value !is ExploreQueueUiState.Preparing) return
        if (cards.isEmpty()) {
            _state.value = ExploreQueueUiState.Empty("暂时没有已准备好的口味匹配候选，请稍后再试")
        } else {
            val ranked = cards.sortedByDescending { it.tasteFraction }
            exposureDao.recordExploreExposures(ranked.map { it.workId })
            _state.value = ExploreQueueUiState.Ready(cards = ranked, index = 0)
        }
    }

    /** 右滑：加入待补池（去重）并前进。 */
    fun onLike() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        worksById[card.workId]?.let { work ->
            viewModelScope.launch { backlogRepository.addAll(listOf(work)) }
        }
        likedCount++
        advance(st)
    }

    /** 左滑：记曝光略过（冷却内不再推荐）并前进。 */
    fun onSkip() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        viewModelScope.launch {
            exposureDao.markDismissed("$EXPLORE_CONTEXT:${card.workId}", System.currentTimeMillis())
        }
        skippedCount++
        advance(st)
    }

    private fun advance(st: ExploreQueueUiState.Ready) {
        val next = st.index + 1
        _state.value = if (next >= st.cards.size) {
            ExploreQueueUiState.Finished(liked = likedCount, skipped = skippedCount)
        } else {
            st.copy(index = next)
        }
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
        const val COOLDOWN_MS = 14L * 24 * 60 * 60 * 1000
    }
}

private suspend fun RecommendationExposureDao.recordExploreExposures(workIds: List<String>) {
    val now = System.currentTimeMillis()
    upsertAll(
        workIds.map { workId ->
            RecommendationExposureEntity(
                id = "explore:$workId",
                subjectId = workId,
                context = "explore",
                exposedAt = now,
                clickedAt = null,
                dismissedAt = null,
            )
        },
    )
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

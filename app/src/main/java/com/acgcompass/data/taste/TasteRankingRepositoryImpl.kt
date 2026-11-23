package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.sync.WorkFeatureEnrichmentScheduler
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.DivergenceOptions
import com.acgcompass.domain.taste.LocalPoolStatus
import com.acgcompass.domain.taste.LocalRankedWork
import com.acgcompass.domain.taste.LocalRanking
import com.acgcompass.domain.taste.PoolExpansion
import com.acgcompass.domain.taste.RankOwnership
import com.acgcompass.domain.taste.TasteBacktest
import com.acgcompass.domain.taste.TasteMatchBranch
import com.acgcompass.domain.taste.TasteModelVersion
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.TasteRankingRepository
import com.acgcompass.domain.taste.WorkFormat
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 口味排行：调 [TasteEngine.rankLocalPoolDetailed] 排序，再补上标题、年份、是否完结和「是否已收藏 / 在待补池」。
 *
 * 结果按「画像输入指纹」（[TasteEngine.inputsKey]）缓存：评分、作品资料、标签分类、兴趣信号、分支都没变时，
 * 排行一定和上次一样，直接返回，不用重排整个作品库。内存一份，本地文件（[TasteRankingDiskCache]）一份：
 * 重启应用后指纹没变也直接用上次的排行，只把已评作品换成当前的回测值。
 */
@Singleton
@Suppress("LongParameterList") // 排行要合并画像、作品、收藏、待补池、作品库补充和补资料
class TasteRankingRepositoryImpl @Inject constructor(
    private val engine: TasteEngine,
    private val workDao: WorkDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
    private val workFeatureDao: WorkFeatureDao,
    private val expander: LocalPoolExpander,
    private val enrichment: WorkFeatureEnrichmentScheduler,
    private val diskCache: TasteRankingDiskCache,
    private val dispatchers: DispatcherProvider,
) : TasteRankingRepository {

    override val expansion: StateFlow<PoolExpansion?> get() = expander.state

    override fun startExpansion(target: Int) = expander.start(target)

    /** 作品库现状；还有作品缺资料时顺便排一次后台补资料。 */
    override suspend fun poolStatus(): LocalPoolStatus = withContext(dispatchers.io) {
        val status = LocalPoolStatus(
            rankable = workFeatureDao.countAnime(),
            missingFeatures = workFeatureDao.countPoolWithoutFeature(MediaType.ANIME.name),
        )
        if (status.missingFeatures > 0) enrichment.enqueue()
        status
    }

    override val profileChanges: Flow<Unit> = merge(
        engine.observeProfile()
            .filterNotNull()
            .distinctUntilChanged { a, b -> a === b }
            .drop(1)
            .map { },
        // 回测算完或开始更新时，已评分作品显示的回测值会变：页面开着就跟着换（只重新套回测值，不重新排整个作品库）。
        engine.observeBacktest()
            .distinctUntilChanged { a, b -> a === b }
            .drop(1)
            .map { },
    )

    private val mutex = Mutex()
    private var cachedFor: AdvancedTasteProfile? = null
    private var cachedKey: String? = null
    private var cachedPoolSize: Int = -1
    private var cachedBacktest: TasteBacktest? = null
    private var cachedLocal: LocalRanking? = null
    private var cached: TasteRanking? = null

    /** 一次加载时的现状：画像、回测、画像输入指纹、作品库部数。 */
    private class Snapshot(
        val profile: AdvancedTasteProfile?,
        val backtest: TasteBacktest?,
        val key: String?,
        val poolSize: Int,
    )

    override suspend fun load(force: Boolean): TasteRanking? = mutex.withLock {
        // 先把画像准备好（冷启动时用本地资料重建），才能拿到输入指纹判断能不能沿用上次的排行。
        val profile = engine.observeProfile().value ?: withContext(dispatchers.io) { engine.ensureReady() }
        val now = Snapshot(
            profile = profile,
            backtest = engine.observeBacktest().value,
            key = engine.inputsKey(),
            poolSize = withContext(dispatchers.io) { workFeatureDao.countAnime() },
        )
        // 画像输入和作品库部数（补资料、补充作品库都会让部数变化）都没变。
        val sameInputs = if (now.key != null) now.key == cachedKey else now.profile === cachedFor
        val unchanged = now.profile != null && sameInputs && now.poolSize == cachedPoolSize
        (if (force) null else reuse(now, unchanged)) ?: compute(now, cachedLocal.takeIf { !force && unchanged })
    }

    /** 能沿用上次的结果就返回它：内存里有且回测没变；或只是回测变了；或冷启动时本地文件里的指纹对得上。 */
    private suspend fun reuse(now: Snapshot, unchanged: Boolean): TasteRanking? {
        val hit = cached
        val backtest = engine.observeBacktest().value
        val branch = engine.observeProfile().value?.matchBranch ?: TasteMatchBranch.STANDARD
        return when {
            hit != null && unchanged && now.backtest === cachedBacktest -> hit
            // 这份排行是从本地文件读的（没有逐部的打分结果），回测变了就直接改已评作品的分。
            hit != null && unchanged && cachedLocal == null -> remember(now, null, hit.withBacktest(backtest, branch))
            hit == null && now.key != null -> {
                val disk = withContext(dispatchers.io) { diskCache.readExact(modelVersion(), now.key, now.poolSize) }
                disk?.let {
                    remember(now, null, it.withBacktest(backtest, branch))
                }
            }
            else -> null
        }
    }

    /** 重新排：[reusable] 不为空（画像输入没变、只是回测变了）时沿用逐部打分，否则给整个作品库重新打分。 */
    private suspend fun compute(now: Snapshot, reusable: LocalRanking?): TasteRanking? {
        val ranking = withContext(dispatchers.io) {
            val local = reusable ?: engine.rankLocalPoolDetailed() ?: return@withContext null
            cachedLocal = local
            val collected = userCollectionDao.getAll().flatMap { listOf(it.sourceItemId, it.localWorkId) }.toSet()
            val backlog = backlogDao.getAll().map { it.workId }.toSet()
            val today = LocalDate.now()
            TasteRanking(
                // 已评分作品显示回测值后按显示的分数重新排一次：列表从高到低和页面上的数字一致。
                items = local.entries.map { toItem(it, collected, backlog, today) }.sortedByDescending { it.score },
                poolSize = local.poolSize,
                computedAt = System.currentTimeMillis(),
                unratedCount = local.unratedCount,
            )
        }
        remember(Snapshot(engine.observeProfile().value, now.backtest, now.key, now.poolSize), cachedLocal, ranking)
        if (ranking != null) {
            withContext(dispatchers.io) { diskCache.write(ranking, modelVersion(), now.key, now.poolSize) }
        }
        return ranking
    }

    private fun remember(now: Snapshot, local: LocalRanking?, ranking: TasteRanking?): TasteRanking? {
        cachedFor = now.profile
        cachedKey = now.key
        cachedPoolSize = now.poolSize
        cachedBacktest = now.backtest
        cachedLocal = local
        cached = ranking
        return ranking
    }

    override suspend fun lastResult(): TasteRanking? =
        cached ?: withContext(dispatchers.io) { diskCache.read(modelVersion()) }

    /** 当前画像的评分模型、匹配度刻度和分支：换了任何一个，上次的结果都不再先显示。画像还没建好时按默认配置。 */
    private fun modelVersion(): String {
        val profile = engine.observeProfile().value
        return profile?.let { TasteModelVersion.ledger(it) }
            ?: "${TasteModelVersion.of(DivergenceOptions.DEFAULT)}|formula-${TasteMatchBranch.STANDARD.key}"
    }

    private suspend fun toItem(
        ranked: LocalRankedWork,
        collected: Set<String>,
        backlog: Set<String>,
        today: LocalDate,
    ): TasteRankItem {
        val f = ranked.feature
        val work = workDao.getById(f.subjectId)
        val airDate = (work?.airDate ?: f.airDate)?.let { runCatchingCancellable { LocalDate.parse(it) }.getOrNull() }
        val reason = ranked.match.positiveReasons.maxByOrNull { it.contribution }?.label
        val shown = displayed(ranked)
        return TasteRankItem(
            subjectId = f.subjectId,
            title = work?.titleCn ?: work?.canonicalTitle ?: f.titleVariants.firstOrNull() ?: "条目 ${f.subjectId}",
            coverUrl = work?.coverUrl ?: f.coverUrl,
            year = work?.year ?: airDate?.year,
            format = WorkFormat.of(f.platform),
            finished = finished(work?.status, airDate, f.eps, today),
            score = shown.score,
            predictedRating = shown.prediction?.rating ?: shown.predicted,
            predictedText = shown.prediction?.headline,
            scorePending = shown.pending,
            reason = reason?.let { "命中「$it」" },
            ownership = when {
                ranked.myRating != null -> RankOwnership.RATED
                f.subjectId in backlog -> RankOwnership.BACKLOG
                f.subjectId in collected -> RankOwnership.COLLECTED
                else -> null
            },
            myRating = ranked.myRating,
        )
    }

    /**
     * 显示的匹配度和预计评分：已评分作品用回测值（建画像时不知道这部作品）；刚评分、画像还没重建时当前的分就是盲测值。
     * 回测还没算完、又没有盲测值时标成待定，不显示当前画像下的分（它把这部作品自己的评分也算进去了）。
     */
    private fun displayed(ranked: LocalRankedWork): Shown {
        val id = ranked.feature.subjectId
        val rating = ranked.myRating ?: return Shown(ranked.match.score, ranked.match.prediction, null, false)
        val profile = engine.observeProfile().value
        val branch = profile?.matchBranch ?: TasteMatchBranch.STANDARD
        val state = engine.observeBacktest().value
        val view = state?.view(id, branch, rating) ?: TasteBacktest.beforeRating(id, ranked.match, rating, profile)
        val pending = view == null && TasteBacktest.pending(state, id, rating)
        return Shown(
            score = view?.match ?: ranked.match.score,
            prediction = view?.prediction ?: ranked.match.prediction.takeIf { !pending },
            predicted = view?.predicted,
            pending = pending,
        )
    }

    /** 一行显示的数：[score] 也用来排序；[pending] 时界面不显示分数。 */
    private data class Shown(
        val score: Int,
        val prediction: com.acgcompass.domain.taste.RatingPrediction?,
        val predicted: Double?,
        val pending: Boolean,
    )
}

/**
 * 把已评作品的分换成当前的回测值（排行本身来自上次的结果，没有逐部打分可重算）。还没有回测时保留原值：
 * 画像输入没变，上次存下的回测值就是这次会算出的值。
 */
internal fun TasteRanking.withBacktest(state: TasteBacktest?, branch: TasteMatchBranch): TasteRanking {
    state ?: return this
    val updated = items.map { item ->
        val rating = item.myRating ?: return@map item
        val view = state.view(item.subjectId, branch, rating)
        val pending = TasteBacktest.pending(state, item.subjectId, rating)
        item.copy(
            score = view?.match ?: item.score,
            predictedRating = view?.prediction?.rating ?: view?.predicted
                ?: item.predictedRating.takeIf { !pending },
            predictedText = view?.prediction?.headline ?: item.predictedText.takeIf { !pending },
            scorePending = pending,
        )
    }
    return copy(items = updated.sortedByDescending { it.score })
}

/** 优先用作品状态；没有状态时按开播日期 + 集数 × 一周估算。 */
private fun finished(status: String?, airDate: LocalDate?, eps: Int, today: LocalDate): Boolean? = when (status) {
    "FINISHED" -> true
    "RELEASING", "NOT_RELEASED" -> false
    else -> airDate?.let { start ->
        if (eps > 0) start.plusWeeks(eps.toLong()).isBefore(today) else start.plusYears(1).isBefore(today)
    }
}

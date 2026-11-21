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
import com.acgcompass.domain.taste.PoolExpansion
import com.acgcompass.domain.taste.RankOwnership
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 口味排行：调 [TasteEngine.rankLocalPoolDetailed] 排序，再补上标题、年份、是否完结和「是否已收藏 / 在待补池」。
 * 结果按画像对象缓存在内存里：画像没重建就直接返回上次的结果，重新打开页面不用再算；
 * 同时存一份到本地文件（[TasteRankingDiskCache]），冷启动后也能先显示上次的结果，再在后台重算。
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

    override val profileChanges: Flow<Unit> = engine.observeProfile()
        .filterNotNull()
        .distinctUntilChanged { a, b -> a === b }
        .drop(1)
        .map { }

    private val mutex = Mutex()
    private var cachedFor: AdvancedTasteProfile? = null
    private var cachedPoolSize: Int = -1
    private var cached: TasteRanking? = null

    override suspend fun load(force: Boolean): TasteRanking? = mutex.withLock {
        val profile = engine.observeProfile().value
        val hit = cached
        // 画像没重建、作品库也没变（补资料、补充作品库都会让部数变化）时直接用上次的结果。
        val poolSize = withContext(dispatchers.io) { workFeatureDao.countAnime() }
        val unchanged = profile != null && profile === cachedFor && poolSize == cachedPoolSize
        if (!force && unchanged && hit != null) return@withLock hit
        val ranking = withContext(dispatchers.io) {
            val local = engine.rankLocalPoolDetailed() ?: return@withContext null
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
        cachedFor = engine.observeProfile().value
        cachedPoolSize = poolSize
        cached = ranking
        if (ranking != null) withContext(dispatchers.io) { diskCache.write(ranking, modelVersion()) }
        ranking
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
        val (score, predicted) = displayed(ranked)
        return TasteRankItem(
            subjectId = f.subjectId,
            title = work?.titleCn ?: work?.canonicalTitle ?: f.titleVariants.firstOrNull() ?: "条目 ${f.subjectId}",
            coverUrl = work?.coverUrl ?: f.coverUrl,
            year = work?.year ?: airDate?.year,
            format = WorkFormat.of(f.platform),
            finished = finished(work?.status, airDate, f.eps, today),
            score = score,
            predictedRating = predicted,
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

    /** 显示的匹配度和预计评分：已评分作品用回测值（建画像时不知道这部作品），回测还没算完时先用当前画像下的分。 */
    private fun displayed(ranked: LocalRankedWork): Pair<Int, Double?> {
        val branch = engine.observeProfile().value?.matchBranch ?: TasteMatchBranch.STANDARD
        val backtest = ranked.myRating?.let { engine.observeBacktest().value?.view(ranked.feature.subjectId, branch) }
        return (backtest?.match ?: ranked.match.score) to (backtest?.predicted ?: ranked.match.prediction?.rating)
    }

    /** 优先用作品状态；没有状态时按开播日期 + 集数 × 一周估算。 */
    private fun finished(status: String?, airDate: LocalDate?, eps: Int, today: LocalDate): Boolean? = when (status) {
        "FINISHED" -> true
        "RELEASING", "NOT_RELEASED" -> false
        else -> airDate?.let { start ->
            if (eps > 0) start.plusWeeks(eps.toLong()).isBefore(today) else start.plusYears(1).isBefore(today)
        }
    }
}

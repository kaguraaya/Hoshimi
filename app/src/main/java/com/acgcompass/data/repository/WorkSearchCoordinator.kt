package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 作品搜索：Bangumi、AniList、MAL（已配置时）、VNDB 四源并行，按标题相似度与热度排序后落库；
 * 只在其它源出现的动画再用原名去 Bangumi 反查补上。
 */
@Singleton
class WorkSearchCoordinator @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val rowWriter: WorkRowWriter,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * 渐进式搜索：四个源并行，**哪个先回来就先发一版结果**，不必等最慢的源（通常是 VNDB）；
     * 全部回来后再补 Bangumi 匹配并发最终结果。最终结果与排序与一次性返回时完全一致。
     */
    fun searchProgressive(query: String): Flow<AppResult<List<WorkMatch>>> = channelFlow {
        val merged = mutableListOf<WorkMatch>()
        val lock = Mutex()
        val sources: List<suspend () -> List<WorkMatch>> = listOf(
            { safeSearch { bangumi.searchSubjects(keyword = query) } },
            { safeSearch { anilist.searchMedia(keyword = query) } },
            { if (mal.isEnabled()) safeSearch { mal.searchAnime(keyword = query) } else emptyList() },
            { safeSearch { vndb.searchVn(keyword = query) } },
        )
        sources.map { source ->
            launch {
                val found = source()
                if (found.isEmpty()) return@launch
                val snapshot = lock.withLock {
                    merged += found
                    merged.toList()
                }
                send(AppResult.Success(rankSearchResults(query, snapshot).also { rowWriter.persistMatches(it) }))
            }
        }.joinAll()
        val all = lock.withLock { merged.toList() }
        val final =
            runCatchingApp {
                rankSearchResults(
                    query,
                    all + backfillBangumi(all),
                ).also { rowWriter.persistMatches(it) }
            }
        send(final)
    }.flowOn(dispatchers.io)

    private fun rankSearchResults(query: String, matches: List<WorkMatch>): List<WorkMatch> = matches
        .map { it.withRecomputedConfidence(query) }
        .sortedWith(
            compareByDescending<WorkMatch> {
                com.acgcompass.domain.matching.representativeScore(it.matchConfidence.toDouble(), it.popularity)
            }
                .thenByDescending { it.popularity }
                .thenBy { sourceCredibilityRank(it.sourceTag) },
        )

    /**
     * 只在其它源出现、Bangumi 没搜到的动画：用它的日文 / 罗马音 / 原名再去 Bangumi 搜一次补上。
     * 各作品之间并行（最多 [BACKFILL_CONCURRENCY] 路，仍受 Bangumi 限流约束）；同一作品的几个标题按顺序试，
     * 搜到就停。总请求数不超过 [BANGUMI_BACKFILL_BUDGET]。
     */
    private suspend fun backfillBangumi(matches: List<WorkMatch>): List<WorkMatch> {
        if (matches.isEmpty()) return emptyList()
        val budget = java.util.concurrent.atomic.AtomicInteger(BANGUMI_BACKFILL_BUDGET)
        val gate = Semaphore(BACKFILL_CONCURRENCY)
        val targets = com.acgcompass.domain.matching.clusterMatches(matches)
            .filter { cluster -> cluster.none { it.sourceTag == SourceId.BANGUMI } }
            .map { com.acgcompass.domain.matching.representativeOf(it) }
            .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
        return coroutineScope {
            targets.map { rep -> async { gate.withPermit { backfillOne(rep, budget) } } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun backfillOne(rep: WorkMatch, budget: java.util.concurrent.atomic.AtomicInteger): WorkMatch? {
        val queries = listOfNotNull(rep.work.titles.ja, rep.work.titles.romaji, rep.work.titles.canonical)
            .filter { it.isNotBlank() }
            .distinct()
        var found: WorkMatch? = null
        for (q in queries) {
            if (budget.getAndDecrement() <= 0) break
            val hits = (bangumi.searchSubjects(keyword = q) as? AppResult.Success)?.data.orEmpty()
            val best = hits
                .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
                // 先排除续作、主题曲、衍生条目：长标题加一个「続」时标题相似度依然很高。
                .filter { com.acgcompass.domain.matching.sameSeasonAndEdition(rep.work, it.work) }
                .map { m -> m to candidateTitles(m).maxOf { matchConfidence(candidate = it, query = q) } }
                .maxWithOrNull(
                    compareBy<Pair<WorkMatch, Double>>(
                        { com.acgcompass.domain.matching.representativeScore(it.second, it.first.popularity) },
                        { it.first.popularity },
                    ),
                )
            if (best != null && best.second >= CROSS_MATCH_THRESHOLD) {
                found = best.first.copy(matchConfidence = best.second.toFloat())
                break
            }
        }
        return found
    }

    /** 来源可信度排序（数值越小越靠前）；动画综合资料优先使用 Bangumi 和 AniList。 */
    private fun sourceCredibilityRank(source: SourceId): Int = when (source) {
        SourceId.BANGUMI -> 0
        SourceId.ANILIST -> 1
        SourceId.MAL -> 2
        SourceId.TENRAI -> 3
        SourceId.JIKAN -> 4
        SourceId.VNDB -> 5
    }

    /** 以标题相似度（覆盖所有已知标题 / 别名取最大值）重算匹配置信度。 */
    private fun WorkMatch.withRecomputedConfidence(query: String): WorkMatch {
        val titles = buildList {
            add(work.titles.canonical)
            work.titles.ja?.let(::add)
            work.titles.romaji?.let(::add)
            work.titles.en?.let(::add)
            addAll(work.titles.aliases)
        }
        val best = titles.maxOfOrNull { matchConfidence(candidate = it, query = query) } ?: 0.0
        return copy(matchConfidence = best.toFloat())
    }

    private companion object {
        /** 跨源补齐 Bangumi 的请求预算（单次搜索最多反查次数，控开销）。 */
        const val BANGUMI_BACKFILL_BUDGET = 8
        const val BACKFILL_CONCURRENCY = 2

        /** 跨平台交叉验证的标题相似度阈值（高阈值避免误配，宁缺毋滥）。 */
        const val CROSS_MATCH_THRESHOLD = 0.86
    }
}

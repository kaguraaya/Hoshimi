package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkIds
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 单源作品跨源补齐：用作品的各种标题去其它平台搜索，与本作同簇的条目写成源链接并补上它们的评分。
 */
@Singleton
@Suppress("LongParameterList")
class CrossSourceLinker @Inject constructor(
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val settingsDataStore: SettingsDataStore,
    private val rowWriter: WorkRowWriter,
    private val ratingFetcher: SourceRatingFetcher,
) {
    /**
     * 单源作品跨源补齐：通过多源搜索和季度感知聚类补齐评分链接。
     *
     * 将本作作为锚点与各来源搜索结果聚类，只把同簇条目的来源链接和评分写到 [workId]。
     * 搜索与评分均按来源并行，单一来源失败不会阻塞其他来源。
     */
    suspend fun enrich(workId: String, priority: RequestPriority = RequestPriority.FOREGROUND) {
        val entity = workDao.getById(workId) ?: return
        if (com.acgcompass.domain.model.MediaType.fromStorage(entity.mediaType) !=
            com.acgcompass.domain.model.MediaType.ANIME
        ) {
            return
        }
        val self = entity.toDomain()
        val primary = sourceIdFromName(entity.primarySource) ?: SourceId.BANGUMI
        val existingSources = buildSet {
            add(primary.name)
            sourceLinkDao.getByWork(workId).forEach { add(it.sourceId) }
        }
        val searchers = crossSourceSearchers(existingSources, priority)
        if (searchers.isEmpty()) return
        val queries = com.acgcompass.domain.matching.foreignSearchQueries(self)
        if (queries.isEmpty()) return

        // 以本作为锚点参与聚类（matchConfidence=1f 确保先建簇），取「与本作同簇」的其他源候选。
        val anchor = WorkMatch(work = self, matchConfidence = 1f, sourceTag = primary, popularity = Int.MAX_VALUE)
        fun clusterOfSelf(candidates: List<WorkMatch>): List<WorkMatch>? =
            com.acgcompass.domain.matching.clusterMatches(listOf(anchor) + candidates)
                .firstOrNull { c -> c.any { it.work.id == self.id && it.sourceTag == primary } }

        // 分两步：先搜 Bangumi / AniList；AniList 命中的同簇条目带罗马音和英文名，再用它们去搜 Tenrai / MAL
        // （MAL 的主标题就是罗马音），只有外文名的平台更容易精确命中。
        val (firstStage, secondStage) = searchers.partition { (sid, _) ->
            sid == SourceId.BANGUMI || sid == SourceId.ANILIST
        }
        val fresh = searchAll(firstStage, queries).toMutableList()
        if (secondStage.isNotEmpty()) {
            val bridgeTitles = clusterOfSelf(fresh).orEmpty()
                .filter { it.sourceTag == SourceId.ANILIST }
                .flatMap { listOfNotNull(it.work.titles.romaji, it.work.titles.en) }
            val secondQueries = (bridgeTitles + queries)
                .distinctBy { com.acgcompass.domain.matching.normalizeTitle(it) }
                .take(SECOND_STAGE_QUERY_LIMIT)
            fresh += searchAll(secondStage, secondQueries)
        }
        if (fresh.isEmpty()) return
        val cluster = clusterOfSelf(fresh) ?: return
        persistCrossSourceCluster(workId, primary, existingSources, cluster, priority)
    }

    private suspend fun searchAll(
        searchers: List<Pair<SourceId, suspend (String) -> AppResult<List<WorkMatch>>>>,
        queries: List<String>,
    ): List<WorkMatch> = if (searchers.isEmpty()) {
        emptyList()
    } else {
        coroutineScope {
            queries.flatMap { q -> searchers.map { (sid, search) -> async { safeSearch(sid) { search(q) } } } }
                .flatMap { it.await() }
        }
    }

    private suspend fun crossSourceSearchers(
        existingSources: Set<String>,
        priority: RequestPriority,
    ): List<Pair<SourceId, suspend (String) -> AppResult<List<WorkMatch>>>> {
        val tenraiEnabled = settingsDataStore.jikanEnabled.first()
        return buildList {
            if (SourceId.BANGUMI.name !in existingSources) {
                add(SourceId.BANGUMI to { query -> bangumi.searchSubjects(keyword = query, priority = priority) })
            }
            if (SourceId.ANILIST.name !in existingSources) {
                add(SourceId.ANILIST to { query -> anilist.searchMedia(keyword = query, priority = priority) })
            }
            if (tenraiEnabled && SourceId.TENRAI.name !in existingSources) {
                // 给本地已有作品找 Tenrai（MAL）对应条目：不带 sfw 过滤，否则 R+ 分级的普通作品搜不到。
                add(
                    SourceId.TENRAI to { query ->
                        tenrai.searchAnime(keyword = query, priority = priority, safeForWork = false)
                    },
                )
            }
            if (mal.isEnabled() && SourceId.MAL.name !in existingSources) {
                add(SourceId.MAL to { query -> mal.searchAnime(keyword = query, priority = priority) })
            }
        }
    }

    private suspend fun persistCrossSourceCluster(
        workId: String,
        primary: SourceId,
        existingSources: Set<String>,
        cluster: List<WorkMatch>,
        priority: RequestPriority,
    ) = coroutineScope {
        cluster
            .filter { it.sourceTag != primary && it.sourceTag.name !in existingSources }
            .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
            .groupBy { it.sourceTag }
            .map { (sid, list) ->
                async {
                    val bestOfSource = com.acgcompass.domain.matching.representativeOf(list)
                    val itemId = WorkIds.sourceItemId(bestOfSource.work.id, sid)
                    rowWriter.persistSourceLink(workId, sid, itemId, bestOfSource.matchConfidence.toDouble())
                    if (sid == SourceId.BANGUMI) {
                        val subjectId = bestOfSource.work.id.toIntOrNull()
                        val bundle = subjectId?.let { bangumi.getSubjectBundle(it, priority) }
                        if (bundle is AppResult.Success) rowWriter.persistBangumiBundle(workId, bundle.data)
                    } else {
                        rowWriter.persistRatingResult(workId, sid, ratingFetcher.fetch(sid, itemId, priority))
                    }
                }
            }.forEach { it.await() }
    }

    private companion object {
        /** 第二步（Tenrai / MAL）的查询词上限：AniList 给出的罗马音、英文名在前。 */
        const val SECOND_STAGE_QUERY_LIMIT = 3
    }
}

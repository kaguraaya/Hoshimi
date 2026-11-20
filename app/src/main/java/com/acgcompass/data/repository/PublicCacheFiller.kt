package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.sync.CacheFillBatch
import com.acgcompass.data.sync.CacheFillSourcePager
import com.acgcompass.data.sync.PublicCacheFillCoordinator
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.cache.CacheSourceAllocation
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 公共缓存补充：首页 / 榜单需要的本季、排行与各补充源作品落库，并按设置的上限从 Bangumi 排行续拉补齐。
 * 分页游标存在 Room，下次接着补；同一时间只跑一个补充任务（[PublicCacheFillCoordinator]）。
 */
@Singleton
@Suppress("LongParameterList")
class PublicCacheFiller @Inject constructor(
    private val cacheDao: CacheDao,
    private val ratingDao: RatingDao,
    private val vndbRankingCacheDao: VndbRankingCacheDao,
    private val bangumi: BangumiRemoteDataSource,
    private val settingsDataStore: SettingsDataStore,
    private val cacheFillCoordinator: PublicCacheFillCoordinator,
    private val cacheFillSourcePager: CacheFillSourcePager,
    private val rowWriter: WorkRowWriter,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun loadPublicDiscovery(background: Boolean): AppResult<Int> = withContext(dispatchers.io) {
        cacheFillCoordinator.run {
            runCatchingApp {
                val requestPriority = if (background) RequestPriority.BACKGROUND else RequestPriority.FOREGROUND
                var written = 0
                // 收集全部公共源命中，最后**一次性** persistMatches → 跨源按标题聚类合并，
                // 同一作品的多平台链接指向同一代表作品，以聚合多平台评分。
                val allMatches = mutableListOf<com.acgcompass.domain.model.WorkMatch>()
                // 评分写入缓冲：(workId, source, entry)，persistMatches 后统一写。
                val ratingRows = mutableListOf<Triple<String, SourceId, com.acgcompass.domain.model.RatingEntry>>()

                // 本季 / 近期热门**默认 Bangumi**（每日放送）。
                val calendar = (bangumi.getCalendar(requestPriority) as? AppResult.Success)?.data.orEmpty()
                calendar.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
                }
                written += calendar.size

                // 分页拉取 Bangumi `sort=rank` 的 Top 动画和本季榜，补充筛选与评分差异样本；
                // 带真实评分，使「榜单/筛选/差异」有量且准确。失败降级（不阻塞）。
                val rankedOverall = buildList {
                    for (page in 0 until 3) {
                        val pageData = (
                            bangumi.searchRankedSubjects(
                                limit = 50,
                                offset = page * 50,
                                priority = requestPriority,
                            ) as? AppResult.Success
                            )?.data?.items.orEmpty()
                        if (pageData.isEmpty()) break
                        addAll(pageData)
                    }
                }
                val seasonRange = currentSeasonAirDateRange()
                val rankedSeason =
                    (
                        bangumi.searchRankedSubjects(
                            airDate = seasonRange,
                            limit = 30,
                            priority = requestPriority,
                        ) as? AppResult.Success
                        )
                        ?.data?.items.orEmpty()
                (rankedOverall + rankedSeason).forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
                }
                written += rankedOverall.size + rankedSeason.size

                // Supplementary sources keep durable cursors and continue until their actual slot target.
                val settingsState = settingsDataStore.settings.first()
                val policy = settingsState.toCachePolicy()
                val sourceTargets = CacheSourceAllocation.countTargets(policy.targetWorkSlots())
                val fillUpdates = mutableListOf<Pair<SourceId, CacheFillBatch<*>>>()
                val tenraiBatch = cacheFillSourcePager.loadTenrai(
                    targetCount = sourceTargets.getValue(SourceId.TENRAI),
                    priority = requestPriority,
                )
                val tenraiItems = tenraiBatch?.items.orEmpty()
                if (tenraiBatch != null) fillUpdates += SourceId.TENRAI to tenraiBatch
                tenraiItems.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.TENRAI, entry)
                }
                written += tenraiItems.size
                val aniListBatch = cacheFillSourcePager.loadAniList(
                    targetCount = sourceTargets.getValue(SourceId.ANILIST),
                    priority = requestPriority,
                )
                val trending = aniListBatch?.items.orEmpty()
                if (aniListBatch != null) fillUpdates += SourceId.ANILIST to aniListBatch
                trending.forEach { (match, entry) ->
                    allMatches += match
                    if (entry != null) ratingRows += Triple(match.work.id, SourceId.ANILIST, entry)
                }
                written += trending.size

                // VNDB stays in the visual-novel boundary and keeps R18 filtering at the source.
                if (settingsState.vndbEnabled) {
                    val vndbBatch = cacheFillSourcePager.loadVndb(
                        targetCount = sourceTargets.getValue(SourceId.VNDB),
                        showAdultContent = settingsState.showAdultContent,
                        priority = requestPriority,
                    )
                    val vndbItems = vndbBatch?.items.orEmpty()
                    if (vndbBatch != null) fillUpdates += SourceId.VNDB to vndbBatch
                    vndbItems.forEach { item ->
                        allMatches += com.acgcompass.domain.model.WorkMatch(
                            work = item.work,
                            matchConfidence = 1f,
                            sourceTag = SourceId.VNDB,
                            popularity = item.voteCount,
                        )
                        item.rating?.takeIf { it > 0f && item.voteCount > 0 }?.let { score ->
                            ratingRows += Triple(
                                item.work.id,
                                SourceId.VNDB,
                                com.acgcompass.domain.model.RatingEntry(score, item.voteCount),
                            )
                        }
                    }
                    written += vndbItems.size
                }

                if (allMatches.isEmpty()) {
                    cacheFillSourcePager.persist(fillUpdates)
                    if (!background && cacheDao.countUnprotectedEntries() > 0) return@runCatchingApp 0
                    throw AppError.Network(cause = "未能获取公共榜单，请检查网络后重试").asException()
                }
                // 一次性持久化 + 跨源聚类（source links 指向代表作品）。
                rowWriter.persistMatches(allMatches)
                // 写入各源评分行（按各源 work id；详情/差异聚合时 aggregateRatings 会按 source links 汇总）。
                ratingRows.forEach { (workId, source, entry) ->
                    ratingDao.upsert(
                        entry.toEntity(id = WorkRowIds.ratingId(workId, source), workId = workId, sourceId = source),
                    )
                }
                cacheFillSourcePager.persist(fillUpdates)
                // 基础榜单写入后，如本地作品不足目标规模，则继续分页拉取 Bangumi 排行补齐
                // （rankedOverall 上面已拉 0..2 页，故从第 3 页续拉）。目标上限来自设置：用户未设（0）时
                // 用 App 智能默认目标，设了则以其为准。已达标则仅一次 count 开销后跳过。
                written += backfillCacheToPolicy(startPage = 3, policy = policy, background = background)
                written
            }
        }
    }

    /**
     * 渐进补齐本地作品候选池。从 Bangumi 排行（`sort=rank`）的
     * [startPage] 续拉，每页落库后重新计数；达到目标用量或接口耗尽即停。返回本次新写入条数。
     *
     * [target]：目标部数（来自设置的自动缓存上限；`<=0` 直接返回不缓存）。
     *
     * 分段限速：前 [FAST_PHASE_WORKS] 部不附加延迟，以缩短首次填充时间；
     * 越过快速阶段后按「距目标的进度」递增页间延迟（[THROTTLE_MIN_DELAY_MS]→[THROTTLE_MAX_DELAY_MS]），
     * 越接近目标越慢，降低持续联网 / 落库的资源占用。未设上限（用默认目标）与已设上限两种情况均
     * 沿此曲线（曲线相对 target 归一，故自适应不同上限）。
     *
     * 分页游标保存在 Room 中，跨源去重后的实际公共缓存数量决定是否继续；不使用固定页数作为停止条件。
     *
     * 任一页失败（含限流）静默跳出，不阻塞启动；下次进首页仍不足会继续渐进补齐。
     */
    private suspend fun backfillCacheToPolicy(startPage: Int, policy: CachePolicy, background: Boolean): Int {
        val pageSize = 50
        val saved = vndbRankingCacheDao.getFillState(SourceId.BANGUMI.name)
        val currentDate = java.time.LocalDate.now()
        val annualPools = (1..POPULAR_YEAR_POOL_COUNT).map { yearsAgo ->
            val year = currentDate.year - yearsAgo
            "YEAR_$year" to listOf(">=$year-01-01", "<${year + 1}-01-01")
        }
        val pools = listOf(
            "OVERALL" to null,
            "YEAR_${currentDate.year}" to
                listOf(">=${currentDate.withDayOfYear(1)}", "<${currentDate.plusYears(1).withDayOfYear(1)}"),
            "SEASON" to currentSeasonAirDateRange(),
        ) + annualPools
        val exhaustedPools = saved?.exhaustedPools.orEmpty().split(',').filter(String::isNotBlank).toMutableSet()
        var poolIndex = pools.indexOfFirst { it.first !in exhaustedPools }.takeIf { it >= 0 } ?: pools.size
        var page = if (poolIndex == 0) maxOf(startPage, saved?.nextPage ?: startPage) else 0
        val initialCount = cacheDao.countUnprotectedEntries()
        var usage = cacheUsage(policy)
        var calendarFallbackUsed = false
        while (usage < 1f && poolIndex <= pools.size) {
            if (poolIndex == pools.size) {
                if (calendarFallbackUsed) break
                calendarFallbackUsed = true
                val calendar = (bangumi.getCalendar(RequestPriority.BACKGROUND) as? AppResult.Success)?.data.orEmpty()
                if (calendar.isNotEmpty()) {
                    rowWriter.persistMatches(calendar.map { it.first })
                    calendar.forEach { (match, entry) ->
                        entry?.let {
                            ratingDao.upsert(
                                it.toEntity(
                                    id = WorkRowIds.ratingId(match.work.id, SourceId.BANGUMI),
                                    workId = match.work.id,
                                    sourceId = SourceId.BANGUMI,
                                ),
                            )
                        }
                    }
                    usage = cacheUsage(policy)
                }
                if (usage >= 1f) break
                exhaustedPools += "CALENDAR"
                poolIndex++
                break
            }
            val pool = pools[poolIndex]
            val result = bangumi.searchRankedSubjects(
                airDate = pool.second,
                limit = pageSize,
                offset = page * pageSize,
                priority = if (background) RequestPriority.BACKGROUND else RequestPriority.FOREGROUND,
            )
            if (result is AppResult.Failure) {
                val now = System.currentTimeMillis()
                vndbRankingCacheDao.upsertFillState(
                    CacheFillStateEntity(
                        source = SourceId.BANGUMI.name,
                        nextPage = page,
                        exhausted = false,
                        lastRunAt = now,
                        lastResult = "FAILED",
                        failureReason = listOf(result.error.cause, result.error.nextStep)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        updatedAt = now,
                    ),
                )
                break
            }
            val pageResult = (result as AppResult.Success).data
            val items = pageResult.items
            if (items.isEmpty()) {
                exhaustedPools += pool.first
                poolIndex++
                page = 0
                continue
            }
            rowWriter.persistMatches(items.map { it.first })
            rowWriter.upsertRatings(SourceId.BANGUMI, items)
            usage = cacheUsage(policy)
            page++
            val exhausted = page * pageSize >= pageResult.total
            if (exhausted) {
                exhaustedPools += pool.first
                poolIndex++
                page = 0
            }
            val now = System.currentTimeMillis()
            vndbRankingCacheDao.upsertFillState(
                CacheFillStateEntity(
                    source = SourceId.BANGUMI.name,
                    nextPage = page,
                    exhausted = usage >= 1f || poolIndex >= pools.size + 1,
                    lastRunAt = now,
                    lastResult = when {
                        usage >= 1f -> "TARGET_REACHED"
                        poolIndex >= pools.size + 1 -> "EXHAUSTED"
                        else -> "RUNNING"
                    },
                    failureReason = null,
                    exhaustedPools = exhaustedPools.sorted().joinToString(","),
                    updatedAt = now,
                ),
            )
            if (usage >= 1f) break
            delay(CacheSourceAllocation.delayMillis(usage))
        }
        val now = System.currentTimeMillis()
        val completed = usage >= 1f
        vndbRankingCacheDao.upsertFillState(
            CacheFillStateEntity(
                source = SourceId.BANGUMI.name,
                nextPage = page,
                exhausted = completed || poolIndex > pools.size,
                lastRunAt = now,
                lastResult = when {
                    completed -> "TARGET_REACHED"
                    poolIndex > pools.size -> "EXHAUSTED"
                    else -> "RUNNING"
                },
                failureReason = null,
                updatedAt = now,
                exhaustedPools = exhaustedPools.sorted().joinToString(","),
            ),
        )
        return (cacheDao.countUnprotectedEntries() - initialCount).coerceAtLeast(0)
    }

    private suspend fun cacheUsage(policy: CachePolicy): Float = when (policy.mode) {
        CacheLimitMode.COUNT -> cacheDao.countUnprotectedEntries().toFloat() / policy.normalizedCountLimit
        CacheLimitMode.SPACE -> (cacheDao.metadataBytes() + cacheDao.mediaBytes()).toFloat() /
            policy.normalizedSpaceLimitBytes
    }.coerceAtLeast(0f)

    fun cancel() {
        cacheFillCoordinator.cancel()
    }

    private companion object {
        const val POPULAR_YEAR_POOL_COUNT = 15
    }
}

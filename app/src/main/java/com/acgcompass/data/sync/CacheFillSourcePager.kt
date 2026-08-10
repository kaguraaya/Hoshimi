package com.acgcompass.data.sync

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.jikan.toRatingEntry
import com.acgcompass.data.remote.jikan.toWorkMatch
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.cache.CacheSourceAllocation
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class CacheFillSourcePager @Inject constructor(
    private val cacheDao: CacheDao,
    private val fillStateDao: VndbRankingCacheDao,
    private val aniList: AniListRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
) {

    suspend fun loadTenrai(
        targetCount: Int,
        priority: RequestPriority,
    ): CacheFillBatch<Pair<WorkMatch, RatingEntry?>>? {
        val state = availableState(SourceId.TENRAI) ?: return null
        return collectCacheFillPages(
            config = pagingConfig(SourceId.TENRAI, targetCount, state.nextPage),
            keyOf = { it.first.work.id },
            fetchPage = { page ->
                when (val result = tenrai.getTopAnime(PAGE_SIZE, page, priority)) {
                    is AppResult.Success -> CacheCandidatePageResult.Page(
                        items = result.data.map { dto ->
                            dto.toWorkMatch(source = SourceId.TENRAI) to dto.toRatingEntry()
                        },
                        hasMore = result.data.size == PAGE_SIZE,
                    )
                    is AppResult.Failure -> CacheCandidatePageResult.Failure(result.failureReason())
                }
            },
            afterPage = ::delayForFill,
        )
    }

    suspend fun loadAniList(
        targetCount: Int,
        priority: RequestPriority,
    ): CacheFillBatch<Pair<WorkMatch, RatingEntry?>>? {
        val state = availableState(SourceId.ANILIST) ?: return null
        return collectCacheFillPages(
            config = pagingConfig(SourceId.ANILIST, targetCount, state.nextPage),
            keyOf = { it.first.work.id },
            fetchPage = { page ->
                when (val result = aniList.getTrending(page, PAGE_SIZE, priority)) {
                    is AppResult.Success -> CacheCandidatePageResult.Page(
                        items = result.data,
                        hasMore = result.data.size == PAGE_SIZE,
                    )
                    is AppResult.Failure -> CacheCandidatePageResult.Failure(result.failureReason())
                }
            },
            afterPage = ::delayForFill,
        )
    }

    suspend fun loadVndb(
        targetCount: Int,
        showAdultContent: Boolean,
        priority: RequestPriority,
    ): CacheFillBatch<VisualNovelListItem>? {
        val state = availableState(SourceId.VNDB) ?: return null
        return collectCacheFillPages(
            config = pagingConfig(SourceId.VNDB, targetCount, state.nextPage),
            keyOf = { it.work.id },
            fetchPage = { page ->
                when (val result = vndb.getAutomaticPool(page, PAGE_SIZE, showAdultContent, priority)) {
                    is AppResult.Success -> CacheCandidatePageResult.Page(result.data.items, result.data.hasMore)
                    is AppResult.Failure -> CacheCandidatePageResult.Failure(result.failureReason())
                }
            },
            afterPage = ::delayForFill,
        )
    }

    suspend fun persist(updates: List<Pair<SourceId, CacheFillBatch<*>>>) {
        val now = System.currentTimeMillis()
        updates.forEach { (source, batch) ->
            val previous = fillStateDao.getFillState(source.name)
            fillStateDao.upsertFillState(nextCacheFillState(source, batch, previous, now))
        }
    }

    private suspend fun availableState(source: SourceId): CacheFillStateEntity? {
        val state = fillStateDao.getFillState(source.name)
        val unavailable = state?.exhausted == true && state.lastResult != RESULT_TARGET_REACHED ||
            state?.cooldownUntil?.let { it > System.currentTimeMillis() } == true
        return if (unavailable) {
            null
        } else {
            state ?: CacheFillStateEntity(source.name, 1, false, null, null, null, updatedAt = 0L)
        }
    }

    private suspend fun pagingConfig(source: SourceId, target: Int, startPage: Int) = CacheFillPagingConfig(
        targetCount = target,
        existingCount = cacheDao.countSourceCoverage(source.name),
        startPage = startPage,
    )

    private suspend fun delayForFill(projectedCount: Int, targetCount: Int) {
        delay(CacheSourceAllocation.delayMillis(projectedCount.toFloat() / targetCount.coerceAtLeast(1)))
    }

    private fun AppResult.Failure.failureReason(): String = listOf(error.cause, error.nextStep)
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .ifBlank { "请求失败" }

    private companion object {
        const val PAGE_SIZE = 25
    }
}

internal fun nextCacheFillState(
    source: SourceId,
    batch: CacheFillBatch<*>,
    previous: CacheFillStateEntity?,
    now: Long,
): CacheFillStateEntity {
    val failed = batch.failureReason != null || batch.result == RESULT_FAILED
    val failures = if (failed) (previous?.consecutiveFailures ?: 0) + 1 else 0
    val cooldownUntil = if (source == SourceId.TENRAI && failed) {
        now + JIKAN_COOLDOWNS[(failures - 1).coerceAtMost(JIKAN_COOLDOWNS.lastIndex)]
    } else {
        null
    }
    return CacheFillStateEntity(
        source = source.name,
        nextPage = batch.nextPage,
        exhausted = batch.exhausted,
        lastRunAt = now,
        lastResult = batch.result,
        failureReason = batch.failureReason,
        consecutiveFailures = failures,
        cooldownUntil = cooldownUntil,
        lastSuccessAt = if (failed) previous?.lastSuccessAt else now,
        updatedAt = now,
        exhaustedPools = previous?.exhaustedPools.orEmpty(),
    )
}

private const val JIKAN_COOLDOWN_1_MS = 30_000L
private const val JIKAN_COOLDOWN_2_MS = 120_000L
private const val JIKAN_COOLDOWN_3_MS = 300_000L
private const val JIKAN_COOLDOWN_4_MS = 900_000L

private val JIKAN_COOLDOWNS = longArrayOf(
    JIKAN_COOLDOWN_1_MS,
    JIKAN_COOLDOWN_2_MS,
    JIKAN_COOLDOWN_3_MS,
    JIKAN_COOLDOWN_4_MS,
)

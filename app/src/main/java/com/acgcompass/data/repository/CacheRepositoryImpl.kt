package com.acgcompass.data.repository

import android.content.Context
import androidx.room.withTransaction
import coil.imageLoader
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.entity.MediaCacheEntryEntity
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.cache.CacheClearRequest
import com.acgcompass.domain.cache.CacheFillRun
import com.acgcompass.domain.cache.CacheItem
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.cache.CacheTrimResult
import com.acgcompass.domain.cache.CacheUsage
import com.acgcompass.domain.cache.ManagedMediaRecord
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.repository.CacheRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class CacheRepositoryImpl @Inject constructor(
    private val database: AcgCompassDatabase,
    private val cacheDao: CacheDao,
    private val fillStateDao: VndbRankingCacheDao,
    private val settings: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : CacheRepository {
    override fun observeFillRuns(): Flow<List<CacheFillRun>> = fillStateDao.observeFillStates().map { rows ->
        rows.map { row ->
            CacheFillRun(
                source = row.source,
                nextPage = row.nextPage,
                exhausted = row.exhausted,
                lastRunAt = row.lastRunAt,
                result = row.lastResult,
                failureReason = row.failureReason,
                consecutiveFailures = row.consecutiveFailures,
                cooldownUntil = row.cooldownUntil,
                lastSuccessAt = row.lastSuccessAt,
            )
        }
    }

    override fun observeUsage(): Flow<CacheUsage> = combine(
        cacheDao.observePublicUsage(),
        cacheDao.observeMediaBytes(),
        cacheDao.observeSourceCoverage(),
    ) { usage, mediaBytes, coverage ->
        CacheUsage(
            publicWorkCount = usage.workCount,
            metadataBytes = usage.metadataBytes,
            mediaBytes = mediaBytes,
            protectedWorkCount = usage.protectedCount,
            sourceCoverage = coverage.associate { sourceId(it.sourceId) to it.workCount },
            appStorageBytes = measureAppStorageBytes(),
        )
    }

    override fun observeItems(): Flow<List<CacheItem>> = cacheDao.observeItems().map { rows ->
        rows.map { row ->
            CacheItem(
                workId = row.workId,
                title = row.title,
                source = sourceId(row.originSource),
                estimatedBytes = row.estimatedBytes,
                mediaBytes = row.mediaBytes,
                lastAccessedAt = row.lastAccessedAt,
                protected = row.protected,
                assetKinds = buildSet {
                    add(CacheAssetKind.WORK)
                    row.mediaKinds.split(',')
                        .mapNotNull { raw -> CacheAssetKind.entries.firstOrNull { it.name == raw } }
                        .forEach(::add)
                    if (row.hasDetail) add(CacheAssetKind.DETAIL)
                },
            )
        }
    }

    override suspend fun currentPolicy(): CachePolicy {
        val state = settings.settings.first()
        return CachePolicy(
            mode = state.cacheLimitMode,
            countLimit = state.autoCacheMaxCount,
            spaceLimitBytes = state.cacheSpaceLimitMb.toLong() * 1024L * 1024L,
            backgroundFillEnabled = state.cacheBackgroundFillEnabled,
        )
    }

    override suspend fun trimToPolicy(policy: CachePolicy): CacheTrimResult {
        refreshProtection()
        var usage = snapshotUsage()
        var result = CacheTrimResult()

        if (policy.mode == CacheLimitMode.SPACE && usage.totalBytes > policy.normalizedSpaceLimitBytes) {
            val media = cacheDao.getMediaOldestFirst().sortedWith(
                compareBy<MediaCacheEntryEntity>({ assetKind(it.kind).evictionPriority }, { it.lastAccessedAt }),
            )
            for (entry in media) {
                if (usage.totalBytes <= policy.normalizedSpaceLimitBytes) break
                removeMedia(entry)
                usage = usage.copy(mediaBytes = (usage.mediaBytes - entry.byteSize).coerceAtLeast(0))
                result = result.copy(
                    removedAssets = result.removedAssets + 1,
                    releasedBytes = result.releasedBytes + entry.byteSize,
                )
            }
        }

        // 最近几天打开过的作品先不清，常看的资料不必反复重新下载。
        val recentCutoff = System.currentTimeMillis() - RECENT_KEEP_MILLIS
        val entries = cacheDao.getEntriesOldestFirst().filter { it.lastAccessedAt < recentCutoff }
        for (entry in entries) {
            val overLimit = when (policy.mode) {
                CacheLimitMode.COUNT -> usage.publicWorkCount > policy.normalizedCountLimit
                CacheLimitMode.SPACE -> usage.totalBytes > policy.normalizedSpaceLimitBytes
            }
            if (!overLimit) break
            val deleted = deleteWorkCache(entry)
            if (!deleted) {
                result = result.copy(protectedSkipped = result.protectedSkipped + 1)
                continue
            }
            usage = usage.copy(
                publicWorkCount = (usage.publicWorkCount - 1).coerceAtLeast(0),
                metadataBytes = (usage.metadataBytes - entry.estimatedBytes).coerceAtLeast(0),
                mediaBytes = cacheDao.mediaBytes(),
            )
            result = result.copy(
                removedWorks = result.removedWorks + 1,
                releasedBytes = result.releasedBytes + entry.estimatedBytes,
            )
        }
        return result
    }

    override suspend fun clear(request: CacheClearRequest): CacheTrimResult {
        var result = CacheTrimResult()
        val now = System.currentTimeMillis()
        if (request.expiredDetailsOnly) {
            result = result.copy(removedAssets = cacheDao.deleteExpiredDetails(now))
        }
        result = clearDetails(request, result)

        val media = cacheDao.getMediaOldestFirst().filter { entry ->
            (request.workIds.isEmpty() || entry.workId in request.workIds) &&
                (request.sources.isEmpty() || sourceId(entry.source) in request.sources) &&
                (request.assetKinds.isEmpty() || assetKind(entry.kind) in request.assetKinds)
        }
        if (request.assetKinds.isNotEmpty()) {
            result = removeSelectedMedia(media, result)
        }

        return clearSelectedWorks(request, result)
    }

    override suspend fun clearAllPublicCache(): CacheTrimResult {
        var result = CacheTrimResult()
        cacheDao.getEntriesOldestFirst().forEach { entry ->
            result = if (deleteWorkCache(entry)) {
                result.copy(
                    removedWorks = result.removedWorks + 1,
                    releasedBytes = result.releasedBytes + entry.estimatedBytes,
                )
            } else {
                clearProtectedAssets(entry, result)
            }
        }
        return result
    }

    private suspend fun clearDetails(request: CacheClearRequest, result: CacheTrimResult): CacheTrimResult {
        if (CacheAssetKind.DETAIL !in request.assetKinds) return result
        val sourceNames = request.sources.map { it.name }.toSet()
        val removed = when {
            request.workIds.isNotEmpty() && sourceNames.isNotEmpty() ->
                cacheDao.deleteDetailsForWorksAndSources(request.workIds, sourceNames)
            request.workIds.isNotEmpty() -> cacheDao.deleteDetailsForWorks(request.workIds)
            sourceNames.isNotEmpty() -> cacheDao.deleteDetailsForSources(sourceNames)
            else -> cacheDao.deleteAllDetails()
        }
        return result.copy(removedAssets = result.removedAssets + removed)
    }

    private suspend fun removeSelectedMedia(
        media: List<MediaCacheEntryEntity>,
        result: CacheTrimResult,
    ): CacheTrimResult {
        media.forEach { removeMedia(it) }
        return result.copy(
            removedAssets = result.removedAssets + media.size,
            releasedBytes = result.releasedBytes + media.sumOf { it.byteSize },
        )
    }

    private suspend fun clearSelectedWorks(request: CacheClearRequest, initial: CacheTrimResult): CacheTrimResult {
        val hasSelection = request.workIds.isNotEmpty() || request.sources.isNotEmpty()
        if (!hasSelection || request.assetKinds.isNotEmpty()) return initial
        val candidates = cacheDao.getEntriesOldestFirst().filter { entry ->
            (request.workIds.isEmpty() || entry.workId in request.workIds) &&
                (request.sources.isEmpty() || sourceId(entry.originSource) in request.sources)
        }
        var result = initial
        candidates.forEach { entry ->
            result = if (deleteWorkCache(entry)) {
                result.copy(
                    removedWorks = result.removedWorks + 1,
                    releasedBytes = result.releasedBytes + entry.estimatedBytes,
                )
            } else {
                clearProtectedAssets(entry, result)
            }
        }
        return result
    }

    private suspend fun clearProtectedAssets(entry: PublicCacheEntryEntity, result: CacheTrimResult): CacheTrimResult {
        cacheDao.deleteDetailsForWork(entry.workId)
        val media = cacheDao.getMediaOldestFirst().filter { it.workId == entry.workId }
        media.forEach { removeMedia(it) }
        return result.copy(
            removedAssets = result.removedAssets + media.size,
            releasedBytes = result.releasedBytes + media.sumOf { it.byteSize },
            protectedSkipped = result.protectedSkipped + 1,
        )
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    override suspend fun recordMedia(record: ManagedMediaRecord) {
        val snapshot = context.imageLoader.diskCache?.openSnapshot(record.cacheKey) ?: return
        val byteSize = snapshot.use { it.data.toFile().length() }
        if (byteSize <= 0L) return
        val inferredOwner = record.workId?.let { null } ?: cacheDao.findCoverOwner(record.url)
        val now = System.currentTimeMillis()
        cacheDao.upsertMedia(
            MediaCacheEntryEntity(
                cacheKey = record.cacheKey,
                workId = record.workId ?: inferredOwner?.workId,
                source = (record.source ?: inferredOwner?.source?.let(::sourceId) ?: SourceId.BANGUMI).name,
                kind = record.kind.name,
                url = record.url,
                byteSize = byteSize,
                adult = record.adult,
                cachedAt = now,
                lastAccessedAt = now,
            ),
        )
        val policy = currentPolicy()
        if (policy.mode == CacheLimitMode.SPACE && snapshotUsage().totalBytes > policy.normalizedSpaceLimitBytes) {
            trimToPolicy(policy)
        }
    }

    private suspend fun refreshProtection() {
        cacheDao.getEntriesOldestFirst().forEach { entry ->
            val protected = cacheDao.isWorkProtected(entry.workId)
            if (protected != entry.protected) cacheDao.updateProtection(entry.workId, protected)
        }
    }

    private suspend fun snapshotUsage(): CacheUsage {
        val public = cacheDao.observePublicUsage().first()
        return CacheUsage(
            publicWorkCount = public.workCount,
            metadataBytes = public.metadataBytes,
            mediaBytes = cacheDao.mediaBytes(),
            protectedWorkCount = public.protectedCount,
            appStorageBytes = measureAppStorageBytes(),
        )
    }

    private fun measureAppStorageBytes(): Long = sequenceOf(
        context.dataDir,
        context.cacheDir,
        context.codeCacheDir,
        context.noBackupFilesDir,
    ).distinct().sumOf(::directorySize)

    private fun directorySize(file: java.io.File): Long = if (!file.exists()) {
        0L
    } else if (file.isFile) {
        file.length().coerceAtLeast(0L)
    } else {
        file.listFiles().orEmpty().sumOf(::directorySize)
    }

    private suspend fun deleteWorkCache(entry: PublicCacheEntryEntity): Boolean = database.withTransaction {
        if (cacheDao.isWorkProtected(entry.workId)) {
            cacheDao.updateProtection(entry.workId, true)
            return@withTransaction false
        }
        cacheDao.getMediaOldestFirst()
            .filter { it.workId == entry.workId }
            .forEach { removeMedia(it) }
        cacheDao.deleteDetailsForWork(entry.workId)
        cacheDao.deleteEntry(entry.workId)
        cacheDao.deleteRankingReferences(entry.workId)
        cacheDao.deleteVndbRankingReferences(entry.workId)
        cacheDao.deleteRatings(entry.workId)
        cacheDao.deleteSourceLinks(entry.workId)
        cacheDao.deleteWorkTags(entry.workId)
        // 作品资料（标签、评分、封面地址等，每部几 KB）和 AI 生成的内容不随缓存清理删除：
        // 口味画像和匹配度要靠前者，后者重新生成要消耗额度。
        cacheDao.deleteRouteNodes(entry.workId)
        cacheDao.deleteRecommendationExposures(entry.workId)
        cacheDao.deleteWork(entry.workId)
        true
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun removeMedia(entry: MediaCacheEntryEntity) {
        runCatchingCancellable { context.imageLoader.diskCache?.remove(entry.cacheKey) }
        cacheDao.deleteMedia(entry.cacheKey)
    }

    private fun sourceId(raw: String): SourceId = SourceId.entries.firstOrNull { it.name == raw } ?: SourceId.BANGUMI

    private fun assetKind(raw: String): CacheAssetKind =
        CacheAssetKind.entries.firstOrNull { it.name == raw } ?: CacheAssetKind.COVER
}

private const val RECENT_KEEP_MILLIS = 3L * 24L * 60L * 60L * 1_000L

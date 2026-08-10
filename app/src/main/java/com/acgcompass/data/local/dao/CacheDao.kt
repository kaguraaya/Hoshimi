package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.DetailCacheEntity
import com.acgcompass.data.local.entity.MediaCacheEntryEntity
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import kotlinx.coroutines.flow.Flow

data class PublicCacheUsageRow(
    val workCount: Int,
    val metadataBytes: Long,
    val protectedCount: Int,
)

data class CacheItemRow(
    val workId: String,
    val title: String,
    val originSource: String,
    val estimatedBytes: Long,
    val mediaBytes: Long,
    val lastAccessedAt: Long,
    val protected: Boolean,
    val mediaKinds: String,
    val hasDetail: Boolean,
)

data class CachedCoverOwnerRow(
    val workId: String,
    val source: String,
)

data class SourceCoverageRow(
    val sourceId: String,
    val workCount: Int,
)

@Dao
interface CacheDao {
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN protected = 0 THEN 1 ELSE 0 END), 0) AS workCount, " +
            "COALESCE(SUM(CASE WHEN protected = 0 THEN estimatedBytes ELSE 0 END), 0) AS metadataBytes, " +
            "COALESCE(SUM(CASE WHEN protected = 1 THEN 1 ELSE 0 END), 0) AS protectedCount " +
            "FROM public_cache_entries",
    )
    fun observePublicUsage(): Flow<PublicCacheUsageRow>

    @Query(
        "SELECT sourceId, COUNT(DISTINCT workId) AS workCount FROM (" +
            "SELECT p.workId AS workId, p.originSource AS sourceId " +
            "FROM public_cache_entries p WHERE p.protected = 0 " +
            "UNION SELECT p.workId AS workId, s.sourceId AS sourceId " +
            "FROM public_cache_entries p INNER JOIN source_links s ON s.workId = p.workId " +
            "WHERE p.protected = 0 " +
            "UNION SELECT p.workId AS workId, r.sourceId AS sourceId " +
            "FROM public_cache_entries p INNER JOIN ratings r ON r.workId = p.workId " +
            "WHERE p.protected = 0) GROUP BY sourceId",
    )
    fun observeSourceCoverage(): Flow<List<SourceCoverageRow>>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM media_cache_entries")
    fun observeMediaBytes(): Flow<Long>

    @Query(
        "SELECT p.workId, COALESCE(w.titleCn, w.canonicalTitle, p.workId) AS title, " +
            "p.originSource, p.estimatedBytes, " +
            "COALESCE((SELECT SUM(m.byteSize) FROM media_cache_entries m " +
            "WHERE m.workId = p.workId), 0) AS mediaBytes, " +
            "p.lastAccessedAt, p.protected, " +
            "COALESCE((SELECT GROUP_CONCAT(DISTINCT m.kind) FROM media_cache_entries m " +
            "WHERE m.workId = p.workId), '') AS mediaKinds, " +
            "EXISTS(SELECT 1 FROM detail_cache d WHERE d.workId = p.workId) AS hasDetail " +
            "FROM public_cache_entries p LEFT JOIN works w ON w.id = p.workId " +
            "ORDER BY p.lastAccessedAt DESC",
    )
    fun observeItems(): Flow<List<CacheItemRow>>

    @Query("SELECT * FROM public_cache_entries ORDER BY lastAccessedAt ASC")
    suspend fun getEntriesOldestFirst(): List<PublicCacheEntryEntity>

    @Query("SELECT * FROM media_cache_entries ORDER BY lastAccessedAt ASC")
    suspend fun getMediaOldestFirst(): List<MediaCacheEntryEntity>

    @Query("SELECT * FROM detail_cache ORDER BY fetchedAt ASC")
    suspend fun getDetailsOldestFirst(): List<DetailCacheEntity>

    @Query("SELECT COUNT(*) FROM public_cache_entries WHERE protected = 0")
    suspend fun countUnprotectedEntries(): Int

    @Query("SELECT COUNT(*) FROM public_cache_entries WHERE protected = 0 AND originSource = :source")
    suspend fun countUnprotectedEntriesBySource(source: String): Int

    @Query(
        "SELECT COUNT(DISTINCT workId) FROM (" +
            "SELECT p.workId AS workId FROM public_cache_entries p " +
            "WHERE p.protected = 0 AND p.originSource = :source " +
            "UNION SELECT p.workId AS workId FROM public_cache_entries p " +
            "INNER JOIN source_links s ON s.workId = p.workId " +
            "WHERE p.protected = 0 AND s.sourceId = :source " +
            "UNION SELECT p.workId AS workId FROM public_cache_entries p " +
            "INNER JOIN ratings r ON r.workId = p.workId " +
            "WHERE p.protected = 0 AND r.sourceId = :source)",
    )
    suspend fun countSourceCoverage(source: String): Int

    @Query("SELECT COALESCE(SUM(estimatedBytes), 0) FROM public_cache_entries WHERE protected = 0")
    suspend fun metadataBytes(): Long

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM media_cache_entries")
    suspend fun mediaBytes(): Long

    @Upsert
    suspend fun upsertEntries(entries: List<PublicCacheEntryEntity>)

    @Upsert
    suspend fun upsertDetail(detail: DetailCacheEntity)

    @Upsert
    suspend fun upsertMedia(media: MediaCacheEntryEntity)

    @Query("SELECT id AS workId, primarySource AS source FROM works WHERE coverUrl = :url LIMIT 1")
    suspend fun findCoverOwner(url: String): CachedCoverOwnerRow?

    @Query("SELECT * FROM detail_cache WHERE workId = :workId AND source = :source AND section = :section LIMIT 1")
    suspend fun getDetail(workId: String, source: String, section: String): DetailCacheEntity?

    @Query("UPDATE public_cache_entries SET lastAccessedAt = :accessedAt WHERE workId = :workId")
    suspend fun touch(workId: String, accessedAt: Long)

    @Query(
        "SELECT CASE WHEN " +
            "EXISTS(SELECT 1 FROM backlog_items WHERE workId = :workId) OR " +
            "EXISTS(SELECT 1 FROM user_collections WHERE localWorkId = :workId) OR " +
            "EXISTS(SELECT 1 FROM import_items WHERE workId = :workId) OR " +
            "EXISTS(SELECT 1 FROM recommendation_counts WHERE workId = :workId) " +
            "THEN 1 ELSE 0 END",
    )
    suspend fun isWorkProtected(workId: String): Boolean

    @Query("UPDATE public_cache_entries SET protected = :protected WHERE workId = :workId")
    suspend fun updateProtection(workId: String, protected: Boolean)

    @Query("DELETE FROM media_cache_entries WHERE cacheKey = :cacheKey")
    suspend fun deleteMedia(cacheKey: String)

    @Query("DELETE FROM media_cache_entries WHERE workId = :workId")
    suspend fun deleteMediaForWork(workId: String)

    @Query("DELETE FROM detail_cache WHERE workId = :workId")
    suspend fun deleteDetailsForWork(workId: String)

    @Query("DELETE FROM detail_cache WHERE expiresAt <= :now")
    suspend fun deleteExpiredDetails(now: Long): Int

    @Query("DELETE FROM detail_cache")
    suspend fun deleteAllDetails(): Int

    @Query("DELETE FROM detail_cache WHERE workId IN (:workIds)")
    suspend fun deleteDetailsForWorks(workIds: Set<String>): Int

    @Query("DELETE FROM detail_cache WHERE source IN (:sources)")
    suspend fun deleteDetailsForSources(sources: Set<String>): Int

    @Query("DELETE FROM detail_cache WHERE workId IN (:workIds) AND source IN (:sources)")
    suspend fun deleteDetailsForWorksAndSources(workIds: Set<String>, sources: Set<String>): Int

    @Query("DELETE FROM public_cache_entries WHERE workId = :workId")
    suspend fun deleteEntry(workId: String)

    @Query("DELETE FROM ranking_cache WHERE workId = :workId")
    suspend fun deleteRankingReferences(workId: String)

    @Query("DELETE FROM vndb_ranking_cache WHERE workId = :workId")
    suspend fun deleteVndbRankingReferences(workId: String)

    @Query("DELETE FROM ratings WHERE workId = :workId")
    suspend fun deleteRatings(workId: String)

    @Query("DELETE FROM source_links WHERE workId = :workId")
    suspend fun deleteSourceLinks(workId: String)

    @Query("DELETE FROM work_tags WHERE workId = :workId")
    suspend fun deleteWorkTags(workId: String)

    @Query("DELETE FROM work_features WHERE subjectId = :workId")
    suspend fun deleteWorkFeature(workId: String)

    @Query("DELETE FROM ai_results WHERE workId = :workId")
    suspend fun deleteAiResults(workId: String)

    @Query("DELETE FROM route_nodes WHERE workId = :workId")
    suspend fun deleteRouteNodes(workId: String)

    @Query("DELETE FROM recommendation_exposure WHERE subjectId = :workId")
    suspend fun deleteRecommendationExposures(workId: String)

    @Query("DELETE FROM works WHERE id = :workId")
    suspend fun deleteWork(workId: String)
}

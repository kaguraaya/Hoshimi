package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * 把旧的「其它平台作品用原始数字编号」的作品行改成「来源:编号」（见 `WorkIds`）。
 * 同一作品的源链接、评分、标签、待补池、该平台的收藏记录、公共缓存一起改；冲突时以已存在的新编号为准。
 */
@Dao
@Suppress("TooManyFunctions") // 每张附属表一条语句
abstract class WorkIdMigrationDao {
    /** 还在用原始数字编号的其它平台作品：id 和来源。 */
    @Query(
        "SELECT id, primarySource FROM works WHERE primarySource IN ('ANILIST', 'TENRAI', 'JIKAN', 'MAL') " +
            "AND instr(id, ':') = 0",
    )
    abstract suspend fun legacyForeignWorks(): List<LegacyWorkId>

    /** 这个编号有没有 Bangumi 收藏指向它（那样的话这一行其实是被覆盖过的 Bangumi 作品，留给同步改回）。 */
    @Query("SELECT COUNT(*) FROM user_collections WHERE localWorkId = :workId AND source = 'BANGUMI'")
    abstract suspend fun bangumiCollectionCount(workId: String): Int

    @Query("SELECT COUNT(*) FROM works WHERE id = :workId")
    abstract suspend fun workCount(workId: String): Int

    @Query("UPDATE works SET id = :newId WHERE id = :oldId")
    protected abstract suspend fun renameWork(oldId: String, newId: String)

    @Query("DELETE FROM works WHERE id = :workId")
    protected abstract suspend fun deleteWork(workId: String)

    @Query("UPDATE source_links SET workId = :newId WHERE workId = :oldId")
    protected abstract suspend fun moveLinks(oldId: String, newId: String)

    @Query(
        "UPDATE OR IGNORE ratings SET workId = :newId, id = :newId || substr(id, length(:oldId) + 1) " +
            "WHERE workId = :oldId",
    )
    protected abstract suspend fun moveRatings(oldId: String, newId: String)

    @Query("DELETE FROM ratings WHERE workId = :workId")
    protected abstract suspend fun deleteRatings(workId: String)

    @Query("UPDATE OR IGNORE work_tags SET workId = :newId WHERE workId = :oldId")
    protected abstract suspend fun moveTags(oldId: String, newId: String)

    @Query("DELETE FROM work_tags WHERE workId = :workId")
    protected abstract suspend fun deleteTags(workId: String)

    @Query("UPDATE OR IGNORE backlog_items SET workId = :newId WHERE workId = :oldId")
    protected abstract suspend fun moveBacklog(oldId: String, newId: String)

    @Query("UPDATE user_collections SET localWorkId = :newId WHERE localWorkId = :oldId AND source = :source")
    protected abstract suspend fun moveCollections(oldId: String, newId: String, source: String)

    @Query("UPDATE OR IGNORE recommendation_counts SET workId = :newId WHERE workId = :oldId")
    protected abstract suspend fun moveRecommendationCounts(oldId: String, newId: String)

    @Query("UPDATE OR IGNORE public_cache_entries SET workId = :newId WHERE workId = :oldId")
    protected abstract suspend fun moveCacheEntries(oldId: String, newId: String)

    @Query("DELETE FROM public_cache_entries WHERE workId = :workId")
    protected abstract suspend fun deleteCacheEntries(workId: String)

    @Query("DELETE FROM detail_cache WHERE workId = :workId")
    protected abstract suspend fun deleteDetailCache(workId: String)

    @Query("DELETE FROM work_features WHERE subjectId = :subjectId")
    abstract suspend fun deleteFeature(subjectId: String)

    /**
     * 作品资料表里用「来源:编号」做键的行：内容其实是关联的 Bangumi 条目，却永远不会按这个键刷新，
     * 还会在口味排行里和 Bangumi 编号那一行重复出现。
     */
    @Query(
        "DELETE FROM work_features WHERE subjectId LIKE 'ANILIST:%' OR subjectId LIKE 'TENRAI:%' " +
            "OR subjectId LIKE 'JIKAN:%' OR subjectId LIKE 'MAL:%'",
    )
    abstract suspend fun deleteNamespacedFeatures(): Int

    /** 已经改成「来源:编号」的其它平台作品，连同标题（用来认出撞号的旧资料行）。 */
    @Query(
        "SELECT id, canonicalTitle, titleJa, titleCn, aliases FROM works " +
            "WHERE id LIKE 'ANILIST:%' OR id LIKE 'TENRAI:%' OR id LIKE 'JIKAN:%' OR id LIKE 'MAL:%'",
    )
    abstract suspend fun namespacedWorks(): List<NamespacedWorkTitles>

    /** 作品资料的标题变体（JSON 数组原文）；没有这一行时为 null。 */
    @Query("SELECT titles FROM work_features WHERE subjectId = :subjectId")
    abstract suspend fun featureTitlesJson(subjectId: String): String?

    /** 把一部作品从 [oldId] 改到 [newId]；新编号已存在时保留新编号那一行，旧行的附属数据能并就并，并不了就删。 */
    @Transaction
    open suspend fun migrate(oldId: String, newId: String, source: String) {
        if (workCount(newId) > 0) deleteWork(oldId) else renameWork(oldId, newId)
        moveLinks(oldId, newId)
        moveRatings(oldId, newId)
        deleteRatings(oldId)
        moveTags(oldId, newId)
        deleteTags(oldId)
        moveBacklog(oldId, newId)
        moveCollections(oldId, newId, source)
        moveRecommendationCounts(oldId, newId)
        moveCacheEntries(oldId, newId)
        deleteCacheEntries(oldId)
        // 详情缓存（评论、角色等）可能是按撞号的 Bangumi 条目拉的，直接丢掉，打开时重新拉。
        deleteDetailCache(oldId)
        // 旧编号下的作品资料是这部作品关联的 Bangumi 条目，留着会和同号的另一部 Bangumi 条目串在一起。
        deleteFeature(oldId)
    }
}

/** 旧作品行的编号和来源。 */
data class LegacyWorkId(val id: String, val primarySource: String)

/** 改过编号的其它平台作品和它的标题。 */
data class NamespacedWorkTitles(
    val id: String,
    val canonicalTitle: String,
    val titleJa: String?,
    val titleCn: String?,
    val aliases: List<String>,
)

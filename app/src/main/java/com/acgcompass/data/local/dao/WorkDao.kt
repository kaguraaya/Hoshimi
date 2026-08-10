package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the canonical [WorkEntity] plus its tightly-coupled satellites:
 * the [WorkTagEntity] join table and the per-work [RecommendationCountEntity]
 * counter (被安利次数). Observe queries return [Flow] for local-first
 * reactive UI ().
 */
@Dao
interface WorkDao {

    // --- Works -------------------------------------------------------------

    @Query("SELECT * FROM works ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkEntity>>

    @Query(
        "SELECT w.* FROM works w INNER JOIN public_cache_entries p ON p.workId = w.id " +
            "WHERE p.protected = 0 ORDER BY w.updatedAt DESC",
    )
    fun observePublicCached(): Flow<List<WorkEntity>>

    /** One-shot snapshot of every work — used by backup export . */
    @Query("SELECT * FROM works")
    suspend fun getAll(): List<WorkEntity>

    /** 本地作品总数，用于判断候选池是否需要补齐到目标规模。 */
    @Query("SELECT COUNT(*) FROM works")
    suspend fun count(): Int

    @Query("SELECT * FROM works WHERE id = :id")
    fun observeById(id: String): Flow<WorkEntity?>

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: String): WorkEntity?

    @Query("SELECT * FROM works WHERE mediaType = :mediaType ORDER BY updatedAt DESC")
    fun observeByMediaType(mediaType: String): Flow<List<WorkEntity>>

    /**
     * 取指定类型作品 id，按最近入池优先，供口味校准候选池冷启动补齐。
     * `work_features`。发现池（works 表）常有数百~上千条，据此挑未评分未缓存者 best-effort 联网补特征。
     */
    @Query("SELECT id FROM works WHERE mediaType = :mediaType ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getIdsByMediaType(mediaType: String, limit: Int): List<String>

    @Query(
        "SELECT * FROM works WHERE canonicalTitle LIKE '%' || :query || '%' " +
            "OR titleJa LIKE '%' || :query || '%' " +
            "OR titleRomaji LIKE '%' || :query || '%' " +
            "OR titleEn LIKE '%' || :query || '%' ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<WorkEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(work: WorkEntity)

    @Upsert
    suspend fun upsert(work: WorkEntity)

    @Upsert
    suspend fun upsertAll(works: List<WorkEntity>)

    @Update
    suspend fun update(work: WorkEntity)

    @Delete
    suspend fun delete(work: WorkEntity)

    @Query("DELETE FROM works WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- Work <-> Tag join -------------------------------------------------

    @Query("SELECT * FROM work_tags WHERE workId = :workId")
    fun observeTagLinks(workId: String): Flow<List<WorkTagEntity>>

    /** One-shot snapshot of every work-tag link — used by backup export . */
    @Query("SELECT * FROM work_tags")
    suspend fun getAllWorkTags(): List<WorkTagEntity>

    @Upsert
    suspend fun upsertWorkTags(links: List<WorkTagEntity>)

    @Delete
    suspend fun deleteWorkTag(link: WorkTagEntity)

    @Query("DELETE FROM work_tags WHERE workId = :workId")
    suspend fun deleteTagLinksForWork(workId: String)

    // --- Recommendation counter (被安利次数) ---------------------

    @Query("SELECT * FROM recommendation_counts WHERE workId = :workId")
    fun observeRecommendationCount(workId: String): Flow<RecommendationCountEntity?>

    @Query("SELECT * FROM recommendation_counts ORDER BY recommendedCount DESC")
    fun observeRecommendationCounts(): Flow<List<RecommendationCountEntity>>

    /** One-shot snapshot of every recommendation counter — used by backup export . */
    @Query("SELECT * FROM recommendation_counts")
    suspend fun getAllRecommendationCounts(): List<RecommendationCountEntity>

    @Upsert
    suspend fun upsertRecommendationCount(count: RecommendationCountEntity)
}

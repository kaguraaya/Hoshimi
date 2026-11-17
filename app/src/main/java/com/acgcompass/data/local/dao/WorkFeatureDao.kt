package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.WorkFeatureEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO：作品结构化特征缓存（v7 / work_features）。供口味画像构建与今晚推荐读取，写入由
 * `WorkFeatureRepository` 在同步 / 详情拉取后 best-effort 落库。
 */
@Dao
@Suppress("TooManyFunctions") // 每个查询一个方法
interface WorkFeatureDao {

    @Query("SELECT * FROM work_features WHERE subjectId = :subjectId")
    suspend fun getById(subjectId: String): WorkFeatureEntity?

    @Query("SELECT * FROM work_features WHERE subjectId = :subjectId")
    fun observeById(subjectId: String): Flow<WorkFeatureEntity?>

    @Query("SELECT * FROM work_features WHERE subjectId IN (:subjectIds)")
    suspend fun getByIds(subjectIds: List<String>): List<WorkFeatureEntity>

    @Query("SELECT subjectId FROM work_features")
    suspend fun getAllIds(): List<String>

    /** 取缓存特征全体（上限 [limit]），供口味画像候选池校准。 */
    @Query("SELECT * FROM work_features LIMIT :limit")
    suspend fun getAll(limit: Int): List<WorkFeatureEntity>

    @Query(
        "SELECT subjectId FROM work_features WHERE completeness != 'EXTENDED' " +
            "ORDER BY baseDataUpdatedAt DESC LIMIT :limit",
    )
    suspend fun getIncompleteIds(limit: Int): List<String>

    @Query(
        "SELECT subjectId FROM work_features WHERE completeness != 'EXTENDED' " +
            "AND subjectId IN (:subjectIds) ORDER BY baseDataUpdatedAt DESC LIMIT :limit",
    )
    suspend fun getIncompleteIds(subjectIds: List<String>, limit: Int): List<String>

    /** 本地作品池里（`works` 表的 Bangumi 动画）还没有作品资料的条目，最近更新的在前。 */
    @Query(
        "SELECT w.id FROM works w LEFT JOIN work_features f ON f.subjectId = w.id " +
            "WHERE w.mediaType = :mediaType AND f.subjectId IS NULL AND w.id NOT GLOB '*[^0-9]*' " +
            "ORDER BY w.updatedAt DESC LIMIT :limit",
    )
    suspend fun getPoolIdsWithoutFeature(mediaType: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM work_features")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM work_features WHERE completeness != 'EMBEDDED'")
    suspend fun countVerified(): Int

    @Upsert
    suspend fun upsert(entity: WorkFeatureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<WorkFeatureEntity>)
}

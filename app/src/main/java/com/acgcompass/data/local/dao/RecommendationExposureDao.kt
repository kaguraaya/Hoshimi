package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RecommendationExposureEntity

/**
 * DAO：推荐曝光记录（v7 / recommendation_exposure）。支撑今晚推荐的「重复推荐冷却」与点击统计。
 */
@Dao
interface RecommendationExposureDao {

    /** 取最近 [since]（毫秒时间戳）之后被曝光过的作品 id（去重），用于重复推荐冷却。 */
    @Query("SELECT DISTINCT subjectId FROM recommendation_exposure WHERE exposedAt >= :since")
    suspend fun exposedSince(since: Long): List<String>

    @Query("SELECT * FROM recommendation_exposure WHERE subjectId = :subjectId ORDER BY exposedAt DESC LIMIT 1")
    suspend fun latestForSubject(subjectId: String): RecommendationExposureEntity?

    @Upsert
    suspend fun upsert(entity: RecommendationExposureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RecommendationExposureEntity>)

    /** 标记某作品在某场景被点击（详情打开）；无曝光记录时忽略。 */
    @Query("UPDATE recommendation_exposure SET clickedAt = :at WHERE id = :id")
    suspend fun markClicked(id: String, at: Long)

    /** 标记某作品在某场景被略过。 */
    @Query("UPDATE recommendation_exposure SET dismissedAt = :at WHERE id = :id")
    suspend fun markDismissed(id: String, at: Long)
}

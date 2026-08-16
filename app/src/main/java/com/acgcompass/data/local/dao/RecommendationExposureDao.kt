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

    /**
     * 指定场景（`tonight` / `explore`）在 [since] 之后的曝光记录。
     * 冷却与反馈学习必须按场景取：探索队列里右滑加入待补池的作品，不应因此在「今晚看什么」里被冷却。
     */
    @Query("SELECT * FROM recommendation_exposure WHERE context = :context AND exposedAt >= :since")
    suspend fun exposuresInContextSince(context: String, since: Long): List<RecommendationExposureEntity>

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

package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 推荐曝光记录表（数据库 v7 起）。
 *
 * 记录某作品在某场景（今晚看什么 / 发现页等）被推荐曝光、点击、略过的时间，支撑：
 * - **重复推荐冷却**（最近 N 天推荐过的条目降权 / 剔除，今晚推荐硬过滤）；
 * - 后续点击率 / 曝光统计与离线评测。
 *
 * 主键 [id] = `"$context:$subjectId"`，使「同一场景对同一作品」只保留最近一条曝光（upsert 覆盖）。
 */
@Entity(tableName = "recommendation_exposure")
data class RecommendationExposureEntity(
    @PrimaryKey
    val id: String,
    val subjectId: String,
    val context: String,
    val exposedAt: Long,
    val clickedAt: Long?,
    val dismissedAt: Long?,
)

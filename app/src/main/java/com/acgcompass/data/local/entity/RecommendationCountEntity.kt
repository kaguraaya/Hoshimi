package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * "被安利次数" counter for a work. The primary key is also the foreign key to
 * [WorkEntity]. [recommendedCount] increments on every import hit of the same
 * work .
 */
@Entity(tableName = "recommendation_counts")
data class RecommendationCountEntity(
    @PrimaryKey
    val workId: String,
    val recommendedCount: Int,
    val lastRecommendedAt: Long,
)

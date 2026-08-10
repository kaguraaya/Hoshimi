package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One rating row per source for a work. Missing data is recorded explicitly via
 * [missing] = true and is never back-filled from other sources ( * ). Aggregated into a RatingAggregate at the domain layer.
 */
@Entity(
    tableName = "ratings",
    indices = [Index(value = ["workId"])],
)
data class RatingEntity(
    @PrimaryKey
    val id: String,
    val workId: String,
    val sourceId: String,
    val score: Float,
    val voteCount: Int,
    val rank: Int?,
    val fetchedAt: Long,
    val missing: Boolean,
)

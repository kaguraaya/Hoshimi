package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Computed taste profile (口味画像). Low sample sizes are reflected by a low
 * [confidence] . Per-tag statistics live in [TasteTagStatEntity].
 *
 * titles is a serialized List<String> via Converters.
 */
@Entity(tableName = "taste_profiles")
data class TasteProfileEntity(
    @PrimaryKey
    val id: String,
    val strictness: Float,
    val avgScore: Float,
    val highScoreRarity: Float,
    val commonScoreBand: String?,
    val titles: List<String>,
    val confidence: Float,
    val generatedAt: Long,
)

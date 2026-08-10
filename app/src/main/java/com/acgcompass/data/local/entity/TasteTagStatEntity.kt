package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-tag statistic belonging to a [TasteProfileEntity] . [bucket]
 * distinguishes high-score vs low-score buckets; [count] is the tag frequency.
 */
@Entity(
    tableName = "taste_tag_stats",
    indices = [Index(value = ["profileId"])],
)
data class TasteTagStatEntity(
    @PrimaryKey
    val id: String,
    val profileId: String,
    val tagName: String,
    val bucket: String,
    val count: Int,
)

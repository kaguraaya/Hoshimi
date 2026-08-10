package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links a canonical [WorkEntity] to an external source item (Bangumi / AniList /
 * Jikan / MAL / VNDB). Carries the match confidence and a manual-override flag
 * so re-syncs never rewrite a user-confirmed link .
 *
 * Indexed on workId for relationship lookups (no @ForeignKey constraint per task
 * scope — plain column + index to avoid cascade complexity).
 */
@Entity(
    tableName = "source_links",
    indices = [Index(value = ["workId"]), Index(value = ["sourceId", "sourceItemId"])],
)
data class SourceLinkEntity(
    @PrimaryKey
    val id: String,
    val workId: String,
    val sourceId: String,
    val sourceItemId: String,
    val matchConfidence: Float,
    val userOverridden: Boolean,
    val linkedAt: Long,
)

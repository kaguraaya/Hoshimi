package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Versioned, section-level detail payload for stale-while-revalidate reads. */
@Entity(
    tableName = "detail_cache",
    primaryKeys = ["workId", "source", "section"],
    indices = [Index(value = ["workId"]), Index(value = ["expiresAt"])],
)
data class DetailCacheEntity(
    val workId: String,
    val source: String,
    val section: String,
    val payloadJson: String,
    val contentHash: String,
    val fetchedAt: Long,
    val expiresAt: Long,
    val schemaVersion: Int,
)

package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Expendable public-data cache membership, kept separate from personal records. */
@Entity(
    tableName = "public_cache_entries",
    indices = [
        Index(value = ["originSource"]),
        Index(value = ["lastAccessedAt"]),
        Index(value = ["protected"]),
    ],
)
data class PublicCacheEntryEntity(
    @PrimaryKey val workId: String,
    val originSource: String,
    val qualityScore: Float,
    val estimatedBytes: Long,
    val cachedAt: Long,
    val lastAccessedAt: Long,
    val protected: Boolean,
)

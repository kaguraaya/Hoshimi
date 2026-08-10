package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Index of managed cover and screenshot files; actual bytes remain in the image disk cache. */
@Entity(
    tableName = "media_cache_entries",
    indices = [
        Index(value = ["workId"]),
        Index(value = ["source"]),
        Index(value = ["kind"]),
        Index(value = ["lastAccessedAt"]),
    ],
)
data class MediaCacheEntryEntity(
    @PrimaryKey val cacheKey: String,
    val workId: String?,
    val source: String,
    val kind: String,
    val url: String,
    val byteSize: Long,
    val adult: Boolean,
    val cachedAt: Long,
    val lastAccessedAt: Long,
)

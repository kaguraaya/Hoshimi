package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "vndb_ranking_cache",
    primaryKeys = ["scopeKey", "page", "position"],
    indices = [Index(value = ["workId"]), Index(value = ["cachedAt"])],
)
data class VndbRankingCacheEntity(
    val scopeKey: String,
    val page: Int,
    val position: Int,
    val workId: String,
    val previewUrl: String? = null,
    val originalUrl: String? = null,
    val rating: Float?,
    val voteCount: Int,
    val adult: Boolean,
    val cachedAt: Long,
    val contentHash: String,
    val hasMore: Boolean,
)

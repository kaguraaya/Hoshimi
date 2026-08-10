package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable continuation point and observable result for automatic cache filling. */
@Entity(tableName = "cache_fill_state")
data class CacheFillStateEntity(
    @PrimaryKey val source: String,
    val nextPage: Int,
    val exhausted: Boolean,
    val lastRunAt: Long?,
    val lastResult: String?,
    val failureReason: String?,
    @ColumnInfo(defaultValue = "0") val consecutiveFailures: Int = 0,
    val cooldownUntil: Long? = null,
    val lastSuccessAt: Long? = null,
    val updatedAt: Long,
    /** Comma-separated candidate pools already exhausted for this source. */
    @ColumnInfo(defaultValue = "''") val exhaustedPools: String = "",
)

package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Durable accounting for tags seen in the local profile and terminal AI outcomes. */
@Entity(
    tableName = "taste_tag_processing",
    indices = [Index(value = ["aiTerminal"])],
)
data class TasteTagProcessingEntity(
    @androidx.room.PrimaryKey val tag: String,
    @ColumnInfo(defaultValue = "1") val localSeen: Boolean = true,
    @ColumnInfo(defaultValue = "0") val aiTerminal: Boolean = false,
    val lastPlanId: String? = null,
    val updatedAt: Long,
)

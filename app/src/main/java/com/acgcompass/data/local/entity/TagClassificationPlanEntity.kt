package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Immutable candidate snapshot and per-tag outcome for one manual AI run. */
@Entity(
    tableName = "tag_classification_plan",
    primaryKeys = ["planId", "tag"],
    indices = [Index("planId"), Index("status")],
)
data class TagClassificationPlanEntity(
    val tag: String,
    val rawTag: String,
    val planId: String,
    val position: Int,
    val status: String,
    val dimension: String?,
    val confidence: Float?,
    val error: String?,
    @ColumnInfo(defaultValue = "1")
    val classifierVersion: Int = 1,
    @ColumnInfo(defaultValue = "''")
    val candidateFingerprint: String = "",
    val updatedAt: Long,
)

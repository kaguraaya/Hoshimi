package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable progress for resumable application-level work. */
@Entity(tableName = "background_task_state")
data class BackgroundTaskStateEntity(
    @PrimaryKey val taskKey: String,
    val inputFingerprint: String,
    val phase: String,
    val completed: Int,
    val total: Int,
    val batchIndex: Int,
    val batchCount: Int,
    val automaticCount: Int,
    val reviewCount: Int,
    val rejectedCount: Int,
    val failedCount: Int,
    val remaining: Int,
    val startedAt: Long?,
    val completedAt: Long?,
    val updatedAt: Long,
    val error: String?,
    val retryAt: Long?,
    val lastResult: String?,
    /** Non-null only after the user has seen the terminal run message. */
    @androidx.room.ColumnInfo(defaultValue = "0") val notificationAcknowledged: Boolean = false,
    /** Candidate snapshot identity; separate from the mutable task phase. */
    @androidx.room.ColumnInfo(defaultValue = "''") val candidateFingerprint: String = "",
    /** Personal ratings, collection and backlog identity used to keep one AI plan stable. */
    @androidx.room.ColumnInfo(defaultValue = "''") val personalInputFingerprint: String = "",
)

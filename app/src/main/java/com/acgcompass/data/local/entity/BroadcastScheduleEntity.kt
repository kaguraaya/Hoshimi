package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "broadcast_schedule",
    primaryKeys = ["scopeKey", "scheduleId"],
    indices = [
        Index("workId"),
        Index("airingAtUtcMillis"),
        Index("dayOfWeek"),
        Index("cachedAt"),
    ],
)
data class BroadcastScheduleEntity(
    val scopeKey: String,
    val scheduleId: String,
    val source: String,
    val sourceItemId: String,
    val workId: String,
    val title: String,
    val titleCn: String?,
    val titleVariants: List<String>,
    val coverUrl: String?,
    val airingAtUtcMillis: Long?,
    val dayOfWeek: Int,
    val episode: Int?,
    val timeToBeDetermined: Boolean,
    val contentHash: String,
    val cachedAt: Long,
)

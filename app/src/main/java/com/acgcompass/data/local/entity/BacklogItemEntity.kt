package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Backlog (待补池) record for a work. The primary key is also the foreign key to
 * [WorkEntity], guaranteeing at most one backlog entry per work (dedupe
 *). [dustDays] and [inDustMuseum] back the "吃灰博物馆" feature.
 *
 * moodTags / riskTags are serialized List<String> via Converters.
 *
 * [prevStatus]（数据库 v6 起）保存归档进吃灰馆之前的 Bangumi 收藏状态。
 * 吃灰归档会把状态置为「搁置」，此字段用于记住归档前状态，
 * 以便移出吃灰馆时还原。非吃灰条目为 `null`。
 */
@Entity(tableName = "backlog_items")
data class BacklogItemEntity(
    @PrimaryKey
    val workId: String,
    val priority: String,
    val moodTags: List<String>,
    val riskTags: List<String>,
    val note: String?,
    val addedAt: Long,
    val dustDays: Int,
    val inDustMuseum: Boolean,
    val prevStatus: String? = null,
)

package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A node in a series watch route (补番路线图). [confirmed] = false marks a
 * "路线待确认" state where order is not fabricated .
 * [watchRecommendation] maps to MUST / OPTIONAL / SKIP / RECAP.
 */
@Entity(
    tableName = "route_nodes",
    indices = [Index(value = ["seriesId"]), Index(value = ["workId"])],
)
data class RouteNodeEntity(
    @PrimaryKey
    val id: String,
    val seriesId: String,
    val workId: String,
    val relationType: String,
    val watchRecommendation: String,
    val orderIndex: Int,
    val confirmed: Boolean,
)

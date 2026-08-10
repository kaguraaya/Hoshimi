package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Join table for the many-to-many relation between [WorkEntity] and [TagEntity].
 * Composite primary key (workId, tagId); both columns indexed for lookups.
 */
@Entity(
    tableName = "work_tags",
    primaryKeys = ["workId", "tagId"],
    indices = [Index(value = ["workId"]), Index(value = ["tagId"])],
)
data class WorkTagEntity(
    val workId: String,
    val tagId: String,
)

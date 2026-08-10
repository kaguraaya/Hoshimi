package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single change detected between two snapshots for a work: new entry,
 * status / rating / review / progress change. Indexed on snapshotId and workId.
 */
@Entity(
    tableName = "change_logs",
    indices = [Index(value = ["snapshotId"]), Index(value = ["workId"])],
)
data class ChangeLogEntity(
    @PrimaryKey
    val id: String,
    val snapshotId: String,
    val workId: String,
    val changeType: String,
    val field: String?,
    val oldValue: String?,
    val newValue: String?,
    val changedAt: Long,
)

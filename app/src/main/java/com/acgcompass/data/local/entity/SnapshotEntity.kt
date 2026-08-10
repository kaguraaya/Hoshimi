package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Time-machine snapshot . The first import creates an INITIAL snapshot;
 * subsequent syncs diff against the previous snapshot to produce [ChangeLogEntity]
 * rows. [kind] (e.g. INITIAL / SYNC) is stored as String.
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey
    val id: String,
    val takenAt: Long,
    val kind: String,
    val payloadHash: String,
)

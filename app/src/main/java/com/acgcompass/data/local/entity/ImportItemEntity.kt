package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One parsed line within an [ImportBatchEntity]. [workId] is nullable because an
 * item may not yet be matched to a canonical work (ER: IMPORT_ITEM }o--o| WORK).
 * Low-confidence matches require user confirmation .
 */
@Entity(
    tableName = "import_items",
    indices = [Index(value = ["batchId"]), Index(value = ["workId"])],
)
data class ImportItemEntity(
    @PrimaryKey
    val id: String,
    val batchId: String,
    val rawText: String,
    val parsedTitle: String?,
    val workId: String?,
    val matchConfidence: Float,
    val status: String,
)

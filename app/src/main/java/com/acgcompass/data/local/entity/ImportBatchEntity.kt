package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A batch import session. Aggregates recognition, success, and failure counts;
 * line-level detail lives in [ImportItemEntity].
 */
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val createdAt: Long,
    val source: String?,
    val recognizedCount: Int,
    val successCount: Int,
    val failureCount: Int,
)

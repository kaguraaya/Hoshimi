package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for batch import sessions : [ImportBatchEntity] aggregates plus the
 * line-level [ImportItemEntity] rows. Low-confidence items remain unmatched
 * (workId == null) until user confirmation .
 */
@Dao
interface ImportDao {

    // --- Batches -----------------------------------------------------------

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    fun observeBatches(): Flow<List<ImportBatchEntity>>

    /** One-shot snapshot of every import batch — used by backup export . */
    @Query("SELECT * FROM import_batches")
    suspend fun getAllBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM import_batches WHERE id = :id")
    fun observeBatch(id: String): Flow<ImportBatchEntity?>

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getBatch(id: String): ImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: ImportBatchEntity)

    @Upsert
    suspend fun upsertBatch(batch: ImportBatchEntity)

    @Update
    suspend fun updateBatch(batch: ImportBatchEntity)

    @Delete
    suspend fun deleteBatch(batch: ImportBatchEntity)

    // --- Items -------------------------------------------------------------

    @Query("SELECT * FROM import_items WHERE batchId = :batchId")
    fun observeItems(batchId: String): Flow<List<ImportItemEntity>>

    /** One-shot snapshot of every import item — used by backup export . */
    @Query("SELECT * FROM import_items")
    suspend fun getAllItems(): List<ImportItemEntity>

    @Query("SELECT * FROM import_items WHERE batchId = :batchId")
    suspend fun getItems(batchId: String): List<ImportItemEntity>

    @Query("SELECT * FROM import_items WHERE id = :id")
    suspend fun getItem(id: String): ImportItemEntity?

    @Query("SELECT * FROM import_items WHERE status = :status")
    fun observeItemsByStatus(status: String): Flow<List<ImportItemEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: ImportItemEntity)

    @Upsert
    suspend fun upsertItem(item: ImportItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<ImportItemEntity>)

    @Update
    suspend fun updateItem(item: ImportItemEntity)

    @Delete
    suspend fun deleteItem(item: ImportItemEntity)

    @Query("DELETE FROM import_items WHERE batchId = :batchId")
    suspend fun deleteItemsForBatch(batchId: String)
}

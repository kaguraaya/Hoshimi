package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.BacklogItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the backlog (待补池). The primary key is the workId, so at most one
 * backlog entry exists per work (dedupe). [observeDustMuseum]
 * backs the 吃灰博物馆 feature .
 */
@Dao
interface BacklogDao {

    @Query("SELECT * FROM backlog_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BacklogItemEntity>>

    /** One-shot snapshot of every backlog item — used by backup export . */
    @Query("SELECT * FROM backlog_items")
    suspend fun getAll(): List<BacklogItemEntity>

    @Query("SELECT * FROM backlog_items WHERE workId = :workId")
    fun observeByWork(workId: String): Flow<BacklogItemEntity?>

    @Query("SELECT * FROM backlog_items WHERE workId = :workId")
    suspend fun getByWork(workId: String): BacklogItemEntity?

    @Query("SELECT * FROM backlog_items WHERE priority = :priority ORDER BY addedAt DESC")
    fun observeByPriority(priority: String): Flow<List<BacklogItemEntity>>

    @Query("SELECT * FROM backlog_items WHERE inDustMuseum = 1 ORDER BY dustDays DESC")
    fun observeDustMuseum(): Flow<List<BacklogItemEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: BacklogItemEntity)

    @Upsert
    suspend fun upsert(item: BacklogItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<BacklogItemEntity>)

    @Update
    suspend fun update(item: BacklogItemEntity)

    @Delete
    suspend fun delete(item: BacklogItemEntity)

    @Query("DELETE FROM backlog_items WHERE workId = :workId")
    suspend fun deleteByWork(workId: String)
}

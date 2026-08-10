package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RatingEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for per-source [RatingEntity] rows. Missing data is stored explicitly with
 * [RatingEntity.missing] = true and is never back-filled across sources
 * (); aggregation happens at the domain layer.
 */
@Dao
interface RatingDao {

    @Query("SELECT * FROM ratings WHERE workId = :workId")
    fun observeByWork(workId: String): Flow<List<RatingEntity>>

    /** One-shot snapshot of every rating row — used by backup export . */
    @Query("SELECT * FROM ratings")
    suspend fun getAll(): List<RatingEntity>

    @Query("SELECT * FROM ratings WHERE workId = :workId")
    suspend fun getByWork(workId: String): List<RatingEntity>

    @Query("SELECT * FROM ratings WHERE workId = :workId AND sourceId = :sourceId LIMIT 1")
    suspend fun getByWorkAndSource(workId: String, sourceId: String): RatingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rating: RatingEntity)

    @Upsert
    suspend fun upsert(rating: RatingEntity)

    @Upsert
    suspend fun upsertAll(ratings: List<RatingEntity>)

    @Update
    suspend fun update(rating: RatingEntity)

    @Delete
    suspend fun delete(rating: RatingEntity)

    @Query("DELETE FROM ratings WHERE workId = :workId")
    suspend fun deleteByWork(workId: String)
}

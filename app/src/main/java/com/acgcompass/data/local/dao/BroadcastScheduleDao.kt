package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.BroadcastScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BroadcastScheduleDao {
    @Query(
        "SELECT * FROM broadcast_schedule WHERE scopeKey = :scopeKey " +
            "AND source = 'BANGUMI' AND (airingAtUtcMillis IS NULL OR airingAtUtcMillis >= :fromMillis) " +
            "ORDER BY airingAtUtcMillis IS NULL ASC, airingAtUtcMillis ASC, dayOfWeek ASC LIMIT :limit",
    )
    fun observeUpcoming(scopeKey: String, fromMillis: Long, limit: Int): Flow<List<BroadcastScheduleEntity>>

    @Query(
        "SELECT * FROM broadcast_schedule WHERE scopeKey = :scopeKey " +
            "ORDER BY dayOfWeek ASC, timeToBeDetermined ASC, airingAtUtcMillis ASC, title ASC",
    )
    fun observeScope(scopeKey: String): Flow<List<BroadcastScheduleEntity>>

    @Query("SELECT * FROM broadcast_schedule WHERE scopeKey = :scopeKey")
    suspend fun getScope(scopeKey: String): List<BroadcastScheduleEntity>

    @Query("SELECT MAX(cachedAt) FROM broadcast_schedule WHERE scopeKey = :scopeKey")
    suspend fun latestCacheTime(scopeKey: String): Long?

    @Upsert
    suspend fun upsertAll(entries: List<BroadcastScheduleEntity>)

    @Query("DELETE FROM broadcast_schedule WHERE scopeKey = :scopeKey")
    suspend fun deleteScope(scopeKey: String)

    @Query("UPDATE broadcast_schedule SET cachedAt = :cachedAt WHERE scopeKey = :scopeKey")
    suspend fun touchScope(scopeKey: String, cachedAt: Long)
}

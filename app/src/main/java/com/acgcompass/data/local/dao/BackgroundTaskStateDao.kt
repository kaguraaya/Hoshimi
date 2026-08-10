package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackgroundTaskStateDao {
    @Query("SELECT * FROM background_task_state WHERE taskKey = :taskKey LIMIT 1")
    fun observe(taskKey: String): Flow<BackgroundTaskStateEntity?>

    @Query("SELECT * FROM background_task_state WHERE taskKey = :taskKey LIMIT 1")
    suspend fun get(taskKey: String): BackgroundTaskStateEntity?

    @Upsert
    suspend fun upsert(state: BackgroundTaskStateEntity)
}

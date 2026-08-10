package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.AiResultEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cached AI / rule-engine results . Results are keyed by work
 * and [AiResultEntity.taskType]; [observeLatestForWorkAndTask] returns the most
 * recent cached card so the UI can show generator/confidence/timestamp.
 */
@Dao
interface AiResultDao {

    @Query("SELECT * FROM ai_results WHERE workId = :workId ORDER BY generatedAt DESC")
    fun observeByWork(workId: String): Flow<List<AiResultEntity>>

    @Query(
        "SELECT * FROM ai_results WHERE workId = :workId AND taskType = :taskType " +
            "ORDER BY generatedAt DESC LIMIT 1",
    )
    fun observeLatestForWorkAndTask(workId: String, taskType: String): Flow<AiResultEntity?>

    @Query(
        "SELECT * FROM ai_results WHERE workId = :workId AND taskType = :taskType " +
            "ORDER BY generatedAt DESC LIMIT 1",
    )
    suspend fun getLatestForWorkAndTask(workId: String, taskType: String): AiResultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(result: AiResultEntity)

    @Upsert
    suspend fun upsert(result: AiResultEntity)

    @Delete
    suspend fun delete(result: AiResultEntity)

    @Query("DELETE FROM ai_results WHERE workId = :workId")
    suspend fun deleteByWork(workId: String)

    @Query("DELETE FROM ai_results WHERE workId = :workId AND taskType = :taskType")
    suspend fun deleteByWorkAndTask(workId: String, taskType: String)
}

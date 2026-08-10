package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TasteTagProcessingEntity

@Dao
interface TasteTagProcessingDao {
    @Query("SELECT COUNT(*) FROM taste_tag_processing WHERE localSeen = 1")
    suspend fun countLocalSeen(): Int

    @Query("SELECT COUNT(*) FROM taste_tag_processing WHERE aiTerminal = 1")
    suspend fun countAiTerminal(): Int

    @Query("SELECT COUNT(*) FROM taste_tag_processing WHERE localSeen = 1 OR aiTerminal = 1")
    suspend fun countCumulative(): Int

    @Query("SELECT tag FROM taste_tag_processing WHERE aiTerminal = 1")
    suspend fun terminalTags(): List<String>

    @Query("SELECT * FROM taste_tag_processing")
    suspend fun getAll(): List<TasteTagProcessingEntity>

    @Query("UPDATE taste_tag_processing SET localSeen = 0")
    suspend fun clearLocalSnapshot()

    @Upsert
    suspend fun upsertAll(rows: List<TasteTagProcessingEntity>)

    @Transaction
    suspend fun replaceLocalSnapshot(tags: Set<String>, updatedAt: Long) {
        val existing = getAll().associateBy { it.tag }
        clearLocalSnapshot()
        upsertAll(
            tags.map { tag ->
                existing[tag]?.copy(localSeen = true, updatedAt = updatedAt)
                    ?: TasteTagProcessingEntity(tag = tag, localSeen = true, updatedAt = updatedAt)
            },
        )
    }

    @Transaction
    suspend fun markAiTerminal(tags: Set<String>, planId: String, updatedAt: Long) {
        if (tags.isEmpty()) return
        val existing = getAll().associateBy { it.tag }
        upsertAll(
            tags.map { tag ->
                existing[tag]?.copy(aiTerminal = true, lastPlanId = planId, updatedAt = updatedAt)
                    ?: TasteTagProcessingEntity(
                        tag = tag,
                        localSeen = false,
                        aiTerminal = true,
                        lastPlanId = planId,
                        updatedAt = updatedAt,
                    )
            },
        )
    }
}

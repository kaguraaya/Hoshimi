package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.SourceLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [SourceLinkEntity] — links between a canonical work and external source
 * items. User-overridden links must never be silently rewritten on re-sync
 * ; repositories enforce that policy on top of these ops.
 */
@Dao
interface SourceLinkDao {

    @Query("SELECT * FROM source_links WHERE workId = :workId")
    fun observeByWork(workId: String): Flow<List<SourceLinkEntity>>

    /** One-shot snapshot of every source link — used by backup export . */
    @Query("SELECT * FROM source_links")
    suspend fun getAll(): List<SourceLinkEntity>

    @Query("SELECT * FROM source_links WHERE workId = :workId")
    suspend fun getByWork(workId: String): List<SourceLinkEntity>

    @Query("SELECT * FROM source_links WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId LIMIT 1")
    suspend fun getBySourceItem(sourceId: String, sourceItemId: String): SourceLinkEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: SourceLinkEntity)

    @Upsert
    suspend fun upsert(link: SourceLinkEntity)

    @Upsert
    suspend fun upsertAll(links: List<SourceLinkEntity>)

    @Update
    suspend fun update(link: SourceLinkEntity)

    @Delete
    suspend fun delete(link: SourceLinkEntity)

    @Query("DELETE FROM source_links WHERE workId = :workId")
    suspend fun deleteByWork(workId: String)
}

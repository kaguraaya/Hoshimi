package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.UserCollectionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用户个人收藏 DAO，供个人统计、详情记录、口味画像和时光机读取，
 * 由 BangumiSyncManager 写入。
 */
@Dao
interface UserCollectionDao {

    @Query("SELECT * FROM user_collections")
    fun observeAll(): Flow<List<UserCollectionEntity>>

    @Query("SELECT * FROM user_collections")
    suspend fun getAll(): List<UserCollectionEntity>

    @Query("SELECT * FROM user_collections WHERE localWorkId = :workId LIMIT 1")
    fun observeByWork(workId: String): Flow<UserCollectionEntity?>

    @Query("SELECT * FROM user_collections WHERE localWorkId = :workId LIMIT 1")
    suspend fun getByWork(workId: String): UserCollectionEntity?

    @Query("SELECT COUNT(*) FROM user_collections")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(item: UserCollectionEntity)

    @Upsert
    suspend fun upsertAll(items: List<UserCollectionEntity>)

    @Query("DELETE FROM user_collections WHERE source = :source")
    suspend fun clearBySource(source: String)

    /** 删除某作品的个人收藏行（用于「全字段清空」时移除，而非残留一条空记录）。 */
    @Query("DELETE FROM user_collections WHERE localWorkId = :workId")
    suspend fun deleteByWork(workId: String)
}

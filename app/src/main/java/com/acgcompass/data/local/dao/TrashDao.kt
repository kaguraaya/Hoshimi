package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // 每个查询一个函数
interface TrashDao {
    /** 回收站里可见的记录（未过保留期），最近移除的在前。 */
    @Query("SELECT * FROM trash_items WHERE purged = 0 ORDER BY removedAt DESC")
    fun observeVisible(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_items WHERE workId = :workId AND purged = 0")
    fun observeVisibleByWork(workId: String): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_items")
    suspend fun getAll(): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE id = :id")
    suspend fun getById(id: String): TrashItemEntity?

    @Query("SELECT * FROM trash_items WHERE batchId = :batchId")
    suspend fun getByBatch(batchId: String): List<TrashItemEntity>

    /** 某一类型的全部记录（含已过保留期的「已移除」标记），同步时用来判断要不要把远端记录加回来。 */
    @Query("SELECT * FROM trash_items WHERE kind = :kind")
    suspend fun getByKind(kind: String): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE purged = 0 AND removedAt < :before")
    suspend fun getExpired(before: Long): List<TrashItemEntity>

    @Upsert
    suspend fun upsert(item: TrashItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<TrashItemEntity>)

    @Query("DELETE FROM trash_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trash_items WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)
}

package com.acgcompass.domain.repository

import com.acgcompass.domain.model.TrashEntry
import kotlinx.coroutines.flow.Flow

/**
 * 本地回收站。被移除的待补池条目和收藏先进这里，保留 [RETENTION_DAYS] 天，期间可以原样恢复
 * （加入时间、更新时间都保持原值）。回收站只在本地，不同步到 Bangumi。
 */
interface TrashRepository {
    /** 回收站里的全部记录，最近移除的在前。 */
    fun observeEntries(): Flow<List<TrashEntry>>

    /** 某部作品在回收站里的记录（待补池条目和 / 或收藏）。 */
    fun observeWork(workId: String): Flow<List<TrashEntry>>

    /** 恢复一条记录。作品已经重新加入（待补池或收藏里已有新记录）时保留新记录、丢弃回收站里的旧快照。 */
    suspend fun restore(id: String): Boolean

    /** 恢复同一次操作移除的全部记录（撤销用），返回恢复的条数。 */
    suspend fun restoreBatch(batchId: String): Int

    /** 从回收站彻底删除。 */
    suspend fun deleteForever(id: String)

    /** 清空回收站。 */
    suspend fun emptyTrash()

    /** 清理超过保留期的记录。 */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis())

    companion object {
        const val RETENTION_DAYS: Int = 30
    }
}

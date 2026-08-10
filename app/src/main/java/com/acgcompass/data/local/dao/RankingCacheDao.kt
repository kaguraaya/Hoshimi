package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RankingCacheEntity

/**
 * 榜单结果本地缓存 DAO。发现页通过 [getByScope] 读取指定范围的有序作品 id，
 * 由 `WorkRepositoryImpl` 回查作品 + 评分重建卡片；写入走 [replaceScope] 整范围覆盖。
 */
@Dao
abstract class RankingCacheDao {

    /** 读取某范围已缓存的排名行，按 [RankingCacheEntity.position] 升序（即真实榜单顺序）。 */
    @Query("SELECT * FROM ranking_cache WHERE scopeKey = :scopeKey ORDER BY position ASC")
    abstract suspend fun getByScope(scopeKey: String): List<RankingCacheEntity>

    @Upsert
    abstract suspend fun upsertAll(rows: List<RankingCacheEntity>)

    /** 清空某范围的全部缓存行（覆盖写前调用）。 */
    @Query("DELETE FROM ranking_cache WHERE scopeKey = :scopeKey")
    abstract suspend fun clearScope(scopeKey: String)

    /**
     * 覆盖写某范围的有序排名（先清空再写入，单事务保证原子性）。
     * 传入空列表即等价于清空该范围（与旧 DataStore「保存空 = 无缓存」语义一致）。
     * 采用 Room 抽象类 DAO + 具体 `@Transaction` 方法的规范写法，保证两步写入同事务。
     */
    @Transaction
    open suspend fun replaceScope(scopeKey: String, rows: List<RankingCacheEntity>) {
        clearScope(scopeKey)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}

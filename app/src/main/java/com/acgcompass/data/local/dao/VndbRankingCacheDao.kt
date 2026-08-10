package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.data.local.entity.VndbRankingCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VndbRankingCacheDao {
    @Query(
        "SELECT * FROM vndb_ranking_cache WHERE scopeKey = :scopeKey AND page = :page " +
            "ORDER BY position ASC",
    )
    abstract suspend fun getPage(scopeKey: String, page: Int): List<VndbRankingCacheEntity>

    @Query("DELETE FROM vndb_ranking_cache WHERE scopeKey = :scopeKey AND page = :page")
    abstract suspend fun clearPage(scopeKey: String, page: Int)

    @Upsert
    abstract suspend fun upsertRows(rows: List<VndbRankingCacheEntity>)

    @Query(
        "UPDATE vndb_ranking_cache SET cachedAt = :cachedAt " +
            "WHERE scopeKey = :scopeKey AND page = :page",
    )
    abstract suspend fun touchPage(scopeKey: String, page: Int, cachedAt: Long)

    @Transaction
    open suspend fun replacePageIfChanged(
        scopeKey: String,
        page: Int,
        rows: List<VndbRankingCacheEntity>,
        contentHash: String,
        cachedAt: Long,
    ): Boolean {
        val existing = getPage(scopeKey, page)
        if (existing.isNotEmpty() && existing.first().contentHash == contentHash) {
            touchPage(scopeKey, page, cachedAt)
            return false
        }
        clearPage(scopeKey, page)
        if (rows.isNotEmpty()) upsertRows(rows)
        return true
    }

    @Query("SELECT * FROM cache_fill_state WHERE source = :source LIMIT 1")
    abstract suspend fun getFillState(source: String): CacheFillStateEntity?

    @Query("SELECT * FROM cache_fill_state ORDER BY source")
    abstract suspend fun getFillStates(): List<CacheFillStateEntity>

    @Query("SELECT * FROM cache_fill_state ORDER BY updatedAt DESC")
    abstract fun observeFillStates(): Flow<List<CacheFillStateEntity>>

    @Upsert
    abstract suspend fun upsertFillState(state: CacheFillStateEntity)
}

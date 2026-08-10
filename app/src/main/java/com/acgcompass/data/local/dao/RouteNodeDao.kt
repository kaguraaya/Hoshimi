package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RouteNodeEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for series watch-route nodes (补番路线图). Nodes belonging to a series
 * are observed ordered by [RouteNodeEntity.orderIndex]; an unconfirmed node
 * ([confirmed] = false) marks a 路线待确认 state .
 */
@Dao
interface RouteNodeDao {

    @Query("SELECT * FROM route_nodes WHERE seriesId = :seriesId ORDER BY orderIndex ASC")
    fun observeBySeries(seriesId: String): Flow<List<RouteNodeEntity>>

    @Query("SELECT * FROM route_nodes WHERE seriesId = :seriesId ORDER BY orderIndex ASC")
    suspend fun getBySeries(seriesId: String): List<RouteNodeEntity>

    @Query("SELECT * FROM route_nodes WHERE workId = :workId")
    fun observeByWork(workId: String): Flow<List<RouteNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(node: RouteNodeEntity)

    @Upsert
    suspend fun upsert(node: RouteNodeEntity)

    @Upsert
    suspend fun upsertAll(nodes: List<RouteNodeEntity>)

    @Update
    suspend fun update(node: RouteNodeEntity)

    @Delete
    suspend fun delete(node: RouteNodeEntity)

    @Query("DELETE FROM route_nodes WHERE seriesId = :seriesId")
    suspend fun deleteBySeries(seriesId: String)
}

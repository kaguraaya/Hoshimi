package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the time-machine : [SnapshotEntity] captures plus the
 * [ChangeLogEntity] diffs produced when a sync is compared against the previous
 * snapshot.
 */
@Dao
interface SnapshotDao {

    // --- Snapshots ---------------------------------------------------------

    @Query("SELECT * FROM snapshots ORDER BY takenAt DESC")
    fun observeSnapshots(): Flow<List<SnapshotEntity>>

    /** One-shot snapshot of every time-machine snapshot — used by backup export . */
    @Query("SELECT * FROM snapshots")
    suspend fun getAllSnapshots(): List<SnapshotEntity>

    @Query("SELECT * FROM snapshots WHERE id = :id")
    suspend fun getSnapshot(id: String): SnapshotEntity?

    @Query("SELECT * FROM snapshots ORDER BY takenAt DESC LIMIT 1")
    suspend fun getLatestSnapshot(): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: SnapshotEntity)

    @Upsert
    suspend fun upsertSnapshot(snapshot: SnapshotEntity)

    @Delete
    suspend fun deleteSnapshot(snapshot: SnapshotEntity)

    // --- Change logs -------------------------------------------------------

    @Query("SELECT * FROM change_logs ORDER BY changedAt DESC")
    fun observeAllChangeLogs(): Flow<List<ChangeLogEntity>>

    /** One-shot snapshot of every change-log row — used by backup export . */
    @Query("SELECT * FROM change_logs")
    suspend fun getAllChangeLogs(): List<ChangeLogEntity>

    @Query("SELECT * FROM change_logs WHERE snapshotId = :snapshotId ORDER BY changedAt DESC")
    fun observeChangeLogs(snapshotId: String): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_logs WHERE snapshotId = :snapshotId")
    suspend fun getChangeLogsForSnapshot(snapshotId: String): List<ChangeLogEntity>

    @Query("SELECT * FROM change_logs WHERE workId = :workId ORDER BY changedAt DESC")
    fun observeChangeLogsForWork(workId: String): Flow<List<ChangeLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChangeLog(log: ChangeLogEntity)

    @Upsert
    suspend fun upsertChangeLogs(logs: List<ChangeLogEntity>)

    @Delete
    suspend fun deleteChangeLog(log: ChangeLogEntity)

    @Query("DELETE FROM change_logs WHERE snapshotId = :snapshotId")
    suspend fun deleteChangeLogsForSnapshot(snapshotId: String)
}

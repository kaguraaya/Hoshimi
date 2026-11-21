package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.acgcompass.data.local.entity.PredictionSnapshotEntity

/** 预测账本（prediction_snapshots）。 */
@Dao
interface PredictionSnapshotDao {
    /** 同一作品、同一模型版本已经记过就保留旧的：账本只要「第一次」的事前预测。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<PredictionSnapshotEntity>)

    @Query("SELECT * FROM prediction_snapshots ORDER BY createdAt")
    suspend fun getAll(): List<PredictionSnapshotEntity>

    @Query("SELECT COUNT(*) FROM prediction_snapshots")
    suspend fun count(): Int

    /** 这些作品里哪些已经记过当前版本（写入前先查，免得每次曝光都去算一遍）。 */
    @Query("SELECT workId FROM prediction_snapshots WHERE modelVersion = :modelVersion AND workId IN (:workIds)")
    suspend fun recordedAmong(modelVersion: String, workIds: List<String>): List<String>

    /** 有了实际评分的记录（Bangumi 收藏）；评分是否晚于预测由调用方按收藏时间再核一次。 */
    @Query(
        "SELECT p.*, c.rating AS actualRating, c.updatedAt AS ratedAt, c.sourceUpdatedAt AS ratedAtIso " +
            "FROM prediction_snapshots p JOIN user_collections c ON c.sourceItemId = p.workId " +
            "WHERE c.rating IS NOT NULL AND c.source = 'BANGUMI'",
    )
    suspend fun reconciled(): List<ReconciledPredictionRow>

    /** 只留最近 [keep] 条，超出的按时间从旧到新删除。 */
    @Query(
        "DELETE FROM prediction_snapshots WHERE id NOT IN " +
            "(SELECT id FROM prediction_snapshots ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM prediction_snapshots")
    suspend fun clear()
}

/** 账本记录 + 用户后来的实际评分。 */
data class ReconciledPredictionRow(
    val id: Long,
    val workId: String,
    val modelVersion: String,
    val context: String,
    val createdAt: Long,
    val predictedRating: Double?,
    val lowRating: Double?,
    val highRating: Double?,
    val matchScore: Int,
    val satisfaction: Int?,
    val profileSize: Int,
    val actualRating: Int,
    val ratedAt: Long,
    val ratedAtIso: String?,
)

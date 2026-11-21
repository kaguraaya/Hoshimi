package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 预测账本：用户还没评分时，App 对一部作品给出的预计评分、区间和匹配度。
 *
 * 每部作品、每个模型版本只记第一次（`workId + modelVersion` 唯一），之后用户真的打了分，
 * 就能拿事前的预测和实际评分对账：这是唯一不受「拿答案调参」影响的检验。
 * 同一时刻可以写两个版本（当前模型和对照模型），比较两版在同一批作品上的事前表现。
 *
 * - [workId]：口味画像里的作品编号（Bangumi 条目 id），和 `user_collections.sourceItemId` 对应。
 * - [context]：记录场景，见 [com.acgcompass.domain.taste.PredictionContext] 的名字。
 * - [profileSize]：当时画像用到的评分数，用来分组看「评分多 / 少的时候准不准」。
 */
@Entity(
    tableName = "prediction_snapshots",
    indices = [
        Index(value = ["workId", "modelVersion"], unique = true),
        Index(value = ["createdAt"]),
    ],
)
data class PredictionSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
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
)

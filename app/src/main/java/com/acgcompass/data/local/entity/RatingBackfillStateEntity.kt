package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 后台补评分的逐部记录：最近一次尝试、最近一次真正查完的时间、连续暂时失败次数和下一次可以再试的时间。
 * 结果见 [com.acgcompass.domain.model.RatingBackfillOutcome] 的名字。
 */
@Entity(
    tableName = "rating_backfill_state",
    indices = [Index(value = ["nextAttemptAt"])],
)
data class RatingBackfillStateEntity(
    @PrimaryKey val workId: String,
    val lastAttemptAt: Long,
    val lastCheckedAt: Long?,
    val failureCount: Int,
    val nextAttemptAt: Long,
    val lastOutcome: String,
)

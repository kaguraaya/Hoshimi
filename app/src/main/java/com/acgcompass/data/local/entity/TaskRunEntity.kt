package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一类后台任务最近的运行情况，给设置里的「后台任务」页看：是否在运行、上次成功 / 失败的时间、失败原因、
 * 上次结果的一句话说明。
 */
@Entity(tableName = "task_runs")
data class TaskRunEntity(
    @PrimaryKey val taskKey: String,
    val running: Boolean,
    val lastStartedAt: Long?,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?,
    val lastSummary: String?,
    val updatedAt: Long,
)

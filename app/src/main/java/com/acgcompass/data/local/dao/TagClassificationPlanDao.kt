package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TagClassificationPlanEntity

@Dao
interface TagClassificationPlanDao {
    @Query("SELECT * FROM tag_classification_plan ORDER BY position")
    suspend fun getAll(): List<TagClassificationPlanEntity>

    @Query("SELECT * FROM tag_classification_plan WHERE status = :status ORDER BY position")
    suspend fun getByStatus(status: String): List<TagClassificationPlanEntity>

    @Query("SELECT * FROM tag_classification_plan WHERE planId = :planId ORDER BY position")
    suspend fun getByPlanId(planId: String): List<TagClassificationPlanEntity>

    /** 处于给定状态的计划行的标签名（只取标签列，不把整张计划表读进内存）。 */
    @Query("SELECT tag FROM tag_classification_plan WHERE status IN (:statuses)")
    suspend fun getTagsByStatuses(statuses: List<String>): List<String>

    @Upsert
    suspend fun upsertAll(entries: List<TagClassificationPlanEntity>)

    @Query("DELETE FROM tag_classification_plan")
    suspend fun clear()
}

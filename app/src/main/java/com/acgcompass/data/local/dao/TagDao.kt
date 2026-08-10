package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [TagEntity] taxonomy. The (category, name) pair is
 * unique; [getByCategoryAndName] supports idempotent taxonomy seeding.
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY category, name")
    fun observeAll(): Flow<List<TagEntity>>

    /** One-shot snapshot of every tag — used by backup export . */
    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE category = :category ORDER BY name")
    fun observeByCategory(category: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("SELECT * FROM tags WHERE category = :category AND name = :name LIMIT 1")
    suspend fun getByCategoryAndName(category: String, name: String): TagEntity?

    @Query(
        "SELECT t.* FROM tags t INNER JOIN work_tags wt ON t.id = wt.tagId " +
            "WHERE wt.workId = :workId ORDER BY t.category, t.name",
    )
    fun observeTagsForWork(workId: String): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 批量读取多部作品的社区标签（join tags×work_tags），按 [workIds] 过滤。
     * 供 `observeWorks` 一次性回填作品标签（候选池/题材筛选/今晚看什么需真实 tag）；
     * 调用方需分批（≤999）以避开 SQLite 变量上限。
     */
    @Query(
        "SELECT wt.workId AS workId, t.category AS category, t.name AS name " +
            "FROM work_tags wt INNER JOIN tags t ON t.id = wt.tagId WHERE wt.workId IN (:workIds)",
    )
    suspend fun getTagsForWorks(workIds: List<String>): List<WorkTagWithCategory>
}

/** `getTagsForWorks` 的投影行——某作品的一条社区标签（含分类，供重建领域 `Tag`）。 */
data class WorkTagWithCategory(
    val workId: String,
    val category: String,
    val name: String,
)

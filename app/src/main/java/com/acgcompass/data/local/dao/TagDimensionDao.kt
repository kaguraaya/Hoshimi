package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TagDimensionEntity

/**
 * 按维度分组的计数结果（画像页「AI 升维效果」展示用）。[dimension] 为 TasteCategory.key，
 * [count] 为该维度下 AI 升维缓存的标签数。
 */
data class TagDimensionCount(val dimension: String, val count: Int)

/**
 * `tag_dimensions` 标签升维分类缓存 DAO。
 *
 * 读：口味画像构建与评分前一次性读全表映射为维度覆盖表（[getAll]）；后台批量分类前读已缓存标签集去重
 * 避免重复调用 AI（[getCachedTags]）。写：AI 分批分类成功后 best-effort 落库（[upsertAll]）。
 */
@Dao
interface TagDimensionDao {

    /** 取全部缓存升维结果（画像/评分前一次性加载为覆盖表）。表规模有限（未知标签去重），一次读入内存即可。 */
    @Query("SELECT * FROM tag_dimensions WHERE valid = 1")
    suspend fun getAll(): List<TagDimensionEntity>

    /** 取已缓存的标签键集合（后台批量分类时排除已分类者，避免重复调用 AI）。 */
    @Query("SELECT tag FROM tag_dimensions WHERE valid = 1 AND classifierVersion = :classifierVersion")
    suspend fun getCachedTags(classifierVersion: Int): List<String>

    /** 按维度分组统计已缓存升维数（画像页展示「AI 升维效果」：各精确维度各细化了多少标签）。 */
    @Query("SELECT dimension AS dimension, COUNT(*) AS count FROM tag_dimensions WHERE valid = 1 GROUP BY dimension")
    suspend fun getDimensionCounts(): List<TagDimensionCount>

    /** 已缓存条数（UI 展示 / 判断是否需要分类）。 */
    @Query("SELECT COUNT(*) FROM tag_dimensions WHERE valid = 1")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entities: List<TagDimensionEntity>)

    @Query("UPDATE tag_dimensions SET valid = 0 WHERE classifierVersion < :classifierVersion")
    suspend fun invalidateOlderVersions(classifierVersion: Int): Int

    /** 清空缓存（隐私数据管理 / 重新分类用）。 */
    @Query("DELETE FROM tag_dimensions")
    suspend fun clear()
}

package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 作品结构化特征缓存表（数据库 v7 起）。
 *
 * 缓存某 Bangumi 条目的社区标签计数、staff/角色/CV、社区评分/票数、集数/时长/平台，供口味画像构建与
 * 今晚推荐复用，避免逐次联网导致候选池暂时缺少可评分特征。主键 [subjectId] = 作品 id。
 *
 * - [tagCountsJson]：社区标签 + 标注人数的 JSON 数组（`[{"name":..,"count":..}]`），由仓库层 (de)serialize。
 * - [staff]/[characters]/[cv]：结构化真实名，经 `Converters` 以 `List<String>` <-> TEXT 持久化。
 * - [bangumiScore] 为 0 表示未知（不伪造）；[eps]/[durationMin] 为 0 表示未知（按 [platform] 兜底估计）。
 */
@Entity(tableName = "work_features")
data class WorkFeatureEntity(
    @PrimaryKey
    val subjectId: String,
    val tagCountsJson: String,
    val staff: List<String>,
    val characters: List<String>,
    val cv: List<String>,
    val bangumiScore: Float,
    val bangumiVotes: Int,
    val eps: Int,
    val durationMin: Int,
    val platform: String?,
    val mediaType: String?,
    val titles: List<String>,
    @ColumnInfo(defaultValue = "0")
    val baseDataUpdatedAt: Long = 0L,
    val extendedDataUpdatedAt: Long? = null,
    @ColumnInfo(defaultValue = "'EXTENDED'")
    val completeness: String = "EXTENDED",
    val updatedAt: Long,
    /** 封面地址与放送日期：和标签、评分存在一起，排行、列表不用再查别的表。 */
    val coverUrl: String? = null,
    val airDate: String? = null,
)

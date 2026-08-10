package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签升维分类缓存表（数据库 v10 起），保存社区标签到口味维度的映射。
 *
 * 只缓存本地规则「其余视为题材」兜底的**未知标签**的 AI 升维结果，把它们从笼统 `topic` 细化到
 * `device`/`xp`/`meme`/`source`/`time`/`noise` 等更精确维度；已被本地词典 / 交叉验证命中的标签不入表。
 * 画像构建与评分时把本表读成 `Map<清洗后标签, TasteCategory>` 覆盖兜底分类；表为空 / AI 未配置时完全回退
 * 本地规则，确保分类不阻塞且不伪造。
 *
 * - 主键 [tag]：清洗后小写标签（与 `TagClassifier.clean` 口径一致，作稳定缓存键）。
 * - [dimension]：维度 key；[source]：`AI` / `RULE`（预留）；[confidence]：AI 置信度；[updatedAt]：写入时刻。
 */
@Entity(tableName = "tag_dimensions")
data class TagDimensionEntity(
    @PrimaryKey
    val tag: String,
    val dimension: String,
    val source: String,
    val confidence: Float,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1")
    val classifierVersion: Int = 1,
    @ColumnInfo(defaultValue = "1")
    val valid: Boolean = true,
)

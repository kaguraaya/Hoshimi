package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 回收站里的一条记录：被移除的待补池条目或收藏的完整快照，只存在本地，不同步到 Bangumi。
 *
 * - [id] = `"$kind:$workId"`，同一作品同一类型最多一条。
 * - [batchId]：同一次操作移除的多条记录共用一个批次，撤销时一起恢复。
 * - [payloadJson]：原记录的完整快照（含加入时间、更新时间），恢复时原样写回。
 * - [purged]：超过保留期后清空快照、从回收站隐藏，但收藏的记录仍作为「已移除」标记保留，
 *   防止 Bangumi 上没删的收藏在同步时又被加回来（Bangumi 公开接口不能删除收藏）。
 */
@Entity(
    tableName = "trash_items",
    indices = [Index(value = ["workId"]), Index(value = ["batchId"])],
)
data class TrashItemEntity(
    @PrimaryKey
    val id: String,
    val kind: String,
    val workId: String,
    val batchId: String,
    val title: String,
    val coverUrl: String?,
    val payloadJson: String?,
    val reason: String,
    val removedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val purged: Boolean = false,
)

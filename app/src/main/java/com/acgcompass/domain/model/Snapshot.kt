package com.acgcompass.domain.model

/**
 * 快照类型。首次导入建立 [INITIAL]，后续同步建立 [SYNC]。
 */
enum class SnapshotKind {
    INITIAL,
    SYNC,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [SYNC]。 */
        fun fromStorage(raw: String?): SnapshotKind = entries.firstOrNull { it.name == raw } ?: SYNC
    }
}

/**
 * 时光机快照。[payloadHash] 用于快速判定两次快照是否有变化。
 */
data class Snapshot(
    val id: String,
    val takenAt: Long,
    val kind: SnapshotKind,
    val payloadHash: String,
)

/**
 * 变更类型：新增条目 / 状态 / 评分 / 短评 / 进度变化等。
 * 包含 [UNKNOWN] 以兜底未知持久化值。
 */
enum class ChangeType {
    ADDED,
    STATUS,
    RATING,
    REVIEW,
    PROGRESS,
    UNKNOWN,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [UNKNOWN]。 */
        fun fromStorage(raw: String?): ChangeType = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * 两次快照之间针对某作品的单条变更记录。[field] / [oldValue] / [newValue]
 * 描述具体字段变化，可为 `null`（如「新增条目」无前值）。
 */
data class ChangeLog(
    val id: String,
    val snapshotId: String,
    val workId: String,
    val changeType: ChangeType,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val changedAt: Long,
)

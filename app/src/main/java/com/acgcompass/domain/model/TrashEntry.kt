package com.acgcompass.domain.model

/** 回收站里的记录类型。 */
enum class TrashKind { BACKLOG, COLLECTION }

/** 为什么进了回收站。 */
enum class TrashReason {
    /** 在应用里移除的（移出待补池、清空收藏记录）。 */
    LOCAL,

    /** Bangumi 上已经删除了这条收藏，同步时移入。 */
    REMOTE_REMOVED,

    /** 切换 Bangumi 账号时，旧账号的收藏移入。 */
    ACCOUNT_SWITCH,
    ;

    companion object {
        fun fromStorage(raw: String?): TrashReason = entries.firstOrNull { it.name == raw } ?: LOCAL
    }
}

/**
 * 回收站里的一条记录（只在本地，不同步到 Bangumi）。
 *
 * @property batchId 同一次操作移除的记录共用一个批次，撤销时一起恢复。
 * @property status 收藏被移除前的状态（想看 / 看过 …）；待补池条目为 null。
 * @property rating 收藏被移除前的评分。
 * @property addedAt 待补池条目原来的加入时间；收藏为原来的更新时间。恢复后保持不变。
 */
data class TrashEntry(
    val id: String,
    val kind: TrashKind,
    val workId: String,
    val batchId: String,
    val title: String,
    val coverUrl: String?,
    val reason: TrashReason,
    val removedAt: Long,
    val status: String? = null,
    val rating: Int? = null,
    val addedAt: Long? = null,
)

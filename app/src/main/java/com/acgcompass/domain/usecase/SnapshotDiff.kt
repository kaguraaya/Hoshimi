package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.ChangeType
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.SnapshotChange

/**
 * 时光机差异计算——**纯函数**，便于单元 / 属性测试，不触碰 IO / Android / 持久化。
 *
 * 给定上一次快照状态 [previous] 与本次状态 [current]，按作品逐一比较，产出语义变更列表：
 * - 仅在 [current] 出现的作品 → [ChangeType.ADDED]（新增收藏）。
 * - 两侧都存在的作品 → 逐字段比较：状态 / 评分 / 短评 / 进度变化各产出一条 [SnapshotChange]。
 *
 * 说明：
 * - 不处理「移除收藏」——仅列举 新增 / 状态 / 评分 / 短评 / 进度变化，故移除不计入。
 * - 输出顺序稳定：按 [current] 的遍历顺序、字段固定顺序（状态→评分→短评→进度），便于断言。
 * - 同 `workId` 在 [current] 内出现多次时，仅以首次出现参与比较（去重，避免重复变更）。
 */
object SnapshotDiff {

    /** [SnapshotChange.field] 取值：收藏 / 观看状态。 */
    const val FIELD_STATUS: String = "status"

    /** [SnapshotChange.field] 取值：个人评分。 */
    const val FIELD_RATING: String = "rating"

    /** [SnapshotChange.field] 取值：个人短评。 */
    const val FIELD_REVIEW: String = "shortReview"

    /** [SnapshotChange.field] 取值：进度。 */
    const val FIELD_PROGRESS: String = "progress"

    /**
     * 计算 [previous] → [current] 的语义变更。纯函数：相同输入恒得相同输出。
     */
    fun diff(previous: List<CollectionState>, current: List<CollectionState>): List<SnapshotChange> {
        val previousByWork: Map<String, CollectionState> =
            previous.associateBy { it.workId }
        val changes = mutableListOf<SnapshotChange>()
        val seen = mutableSetOf<String>()

        for (now in current) {
            if (!seen.add(now.workId)) continue // 同次去重：仅首次出现参与比较。
            val before = previousByWork[now.workId]
            if (before == null) {
                // 新增收藏：以当前状态作为 newValue，便于时间线展示「新增（状态）」。
                changes += SnapshotChange(
                    workId = now.workId,
                    changeType = ChangeType.ADDED,
                    newValue = now.status,
                )
                continue
            }
            if (before.status != now.status) {
                changes += SnapshotChange(
                    workId = now.workId,
                    changeType = ChangeType.STATUS,
                    field = FIELD_STATUS,
                    oldValue = before.status,
                    newValue = now.status,
                )
            }
            if (before.rating != now.rating) {
                changes += SnapshotChange(
                    workId = now.workId,
                    changeType = ChangeType.RATING,
                    field = FIELD_RATING,
                    oldValue = before.rating?.toString(),
                    newValue = now.rating?.toString(),
                )
            }
            if (before.shortReview != now.shortReview) {
                changes += SnapshotChange(
                    workId = now.workId,
                    changeType = ChangeType.REVIEW,
                    field = FIELD_REVIEW,
                    oldValue = before.shortReview,
                    newValue = now.shortReview,
                )
            }
            if (before.progress != now.progress) {
                changes += SnapshotChange(
                    workId = now.workId,
                    changeType = ChangeType.PROGRESS,
                    field = FIELD_PROGRESS,
                    oldValue = before.progress?.toString(),
                    newValue = now.progress?.toString(),
                )
            }
        }
        return changes
    }
}

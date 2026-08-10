package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.ChangeType
import com.acgcompass.domain.model.CollectionState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * 时光机差异计算纯函数单元测试。
 *
 * 覆盖五类变更（新增收藏 / 状态 / 评分 / 短评 / 进度）、无变化、同次去重与多字段并发变化。
 */
class SnapshotDiffTest : StringSpec({

    fun item(id: String, status: String? = null, rating: Int? = null, review: String? = null, progress: Int? = null) =
        CollectionState(id, status, rating, review, progress)

    "empty previous: every current item is ADDED (新增收藏)" {
        val changes = SnapshotDiff.diff(
            previous = emptyList(),
            current = listOf(item("a", status = "想看"), item("b", status = "在看")),
        )
        changes.map { it.workId } shouldContainExactly listOf("a", "b")
        changes.all { it.changeType == ChangeType.ADDED } shouldBe true
        changes.first().newValue shouldBe "想看"
    }

    "identical states produce no changes" {
        val state = listOf(item("a", status = "在看", rating = 7, review = "好看", progress = 3))
        SnapshotDiff.diff(state, state) shouldBe emptyList()
    }

    "status change (状态变化)" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a", status = "想看")),
            current = listOf(item("a", status = "看过")),
        )
        changes.size shouldBe 1
        changes[0].changeType shouldBe ChangeType.STATUS
        changes[0].field shouldBe SnapshotDiff.FIELD_STATUS
        changes[0].oldValue shouldBe "想看"
        changes[0].newValue shouldBe "看过"
    }

    "rating change (评分变化), null -> value encoded as string" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a", rating = null)),
            current = listOf(item("a", rating = 9)),
        )
        changes.size shouldBe 1
        changes[0].changeType shouldBe ChangeType.RATING
        changes[0].field shouldBe SnapshotDiff.FIELD_RATING
        changes[0].oldValue shouldBe null
        changes[0].newValue shouldBe "9"
    }

    "review change (短评变化)" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a", review = "一般")),
            current = listOf(item("a", review = "封神")),
        )
        changes.single().changeType shouldBe ChangeType.REVIEW
        changes.single().oldValue shouldBe "一般"
        changes.single().newValue shouldBe "封神"
    }

    "progress change (进度变化)" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a", progress = 2)),
            current = listOf(item("a", progress = 12)),
        )
        changes.single().changeType shouldBe ChangeType.PROGRESS
        changes.single().oldValue shouldBe "2"
        changes.single().newValue shouldBe "12"
    }

    "multiple fields change for one work, fixed order status->rating->review->progress" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a", status = "想看", rating = 5, review = "x", progress = 1)),
            current = listOf(item("a", status = "看过", rating = 8, review = "y", progress = 24)),
        )
        changes.map { it.changeType } shouldContainExactly listOf(
            ChangeType.STATUS,
            ChangeType.RATING,
            ChangeType.REVIEW,
            ChangeType.PROGRESS,
        )
    }

    "removed works are ignored (only 5 change kinds tracked)" {
        val changes = SnapshotDiff.diff(
            previous = listOf(item("a"), item("b")),
            current = listOf(item("a")),
        )
        changes shouldBe emptyList()
    }

    "duplicate workId in current is compared only once" {
        val changes = SnapshotDiff.diff(
            previous = emptyList(),
            current = listOf(item("a", status = "想看"), item("a", status = "在看")),
        )
        changes.size shouldBe 1
        changes.single().workId shouldBe "a"
    }
})

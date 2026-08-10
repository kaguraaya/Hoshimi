package com.acgcompass.feature.home

import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 *
 * 覆盖纯 Kotlin 折叠逻辑：待补池概览 [buildBacklogSummary]、卡片折叠 [toWorkCard]
 * 与待补状态文案 [backlogStatusText]。界面行为由 Compose UI 测试覆盖。
 */
class HomeContractTest : StringSpec({

    fun work(id: String, mediaType: MediaType = MediaType.ANIME, completionCost: CompletionCost? = null): Work = Work(
        id = id,
        titles = Titles(canonical = "作品-$id"),
        mediaType = mediaType,
        primarySource = SourceId.BANGUMI,
        completionCost = completionCost,
    )

    fun item(workId: String, addedAt: Long = 0L, dustDays: Int = 0, inDustMuseum: Boolean = false): BacklogItem =
        BacklogItem(
            workId = workId,
            addedAt = addedAt,
            dustDays = dustDays,
            inDustMuseum = inDustMuseum,
        )

    "空待补集合返回空概览（数量为 0、无卡片）" {
        val summary = buildBacklogSummary(emptyList())
        summary.totalCount shouldBe 0
        summary.recentlyAdded.shouldBeNull()
        summary.longestDust.shouldBeNull()
        summary.shortPickable shouldHaveSize 0
        summary.highMatch shouldHaveSize 0
    }

    "最近加入取 addedAt 最大者，吃灰最久取 dustDays 最大者" {
        val entries = listOf(
            HomeBacklogEntry(item("a", addedAt = 10L, dustDays = 1), work("a")),
            HomeBacklogEntry(item("b", addedAt = 30L, dustDays = 5), work("b")),
            HomeBacklogEntry(item("c", addedAt = 20L, dustDays = 9), work("c")),
        )
        val summary = buildBacklogSummary(entries)

        summary.totalCount shouldBe 3
        summary.recentlyAdded?.workId shouldBe "b"
        summary.longestDust?.workId shouldBe "c"
    }

    "全部 dustDays 为 0 时不展示吃灰最久（不伪造吃灰）" {
        val entries = listOf(
            HomeBacklogEntry(item("a", addedAt = 1L), work("a")),
            HomeBacklogEntry(item("b", addedAt = 2L), work("b")),
        )
        buildBacklogSummary(entries).longestDust.shouldBeNull()
    }

    "短篇可补仅包含补完成本为 TONIGHT 的条目" {
        val entries = listOf(
            HomeBacklogEntry(item("a"), work("a", completionCost = CompletionCost.TONIGHT)),
            HomeBacklogEntry(item("b"), work("b", completionCost = CompletionCost.LONG_HAUL)),
            HomeBacklogEntry(item("c"), work("c", completionCost = CompletionCost.TONIGHT)),
        )
        val summary = buildBacklogSummary(entries)
        summary.shortPickable.map { it.workId } shouldContainExactly listOf("a", "c")
    }

    "高匹配当前无数据源，保持为空占位（绝不伪造）" {
        val entries = listOf(HomeBacklogEntry(item("a"), work("a")))
        buildBacklogSummary(entries).highMatch shouldHaveSize 0
    }

    "作品缺失时卡片标题回退为 workId、评分恒为 null（暂无数据）" {
        val card = HomeBacklogEntry(item("missing"), work = null).toWorkCard()
        card.workId shouldBe "missing"
        card.card.title shouldBe "missing"
        card.card.ratingText.shouldBeNull()
    }

    "待补状态文案：吃灰博物馆 / 吃灰天数 / 想看" {
        backlogStatusText(item("a", inDustMuseum = true)) shouldBe "吃灰博物馆"
        backlogStatusText(item("a", dustDays = 7)) shouldBe "吃灰 7 天"
        backlogStatusText(item("a")) shouldBe "想看"
    }
})

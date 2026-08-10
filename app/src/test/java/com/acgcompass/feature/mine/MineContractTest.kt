package com.acgcompass.feature.mine

import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.SourceId
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId as DomainSourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 *
 * 覆盖纯 Kotlin 逻辑：账号状态行构建 [buildAccountRows] 与数据统计计算 [buildMineStats]。
 * 重点验证「缺失即标记、绝不伪造」：无来源的指标恒为 null（UI 显示「暂无数据」）。
 */
class MineContractTest : StringSpec({

    fun item(workId: String, moodTags: List<String> = emptyList(), riskTags: List<String> = emptyList()): BacklogItem =
        BacklogItem(
            workId = workId,
            addedAt = 0L,
            moodTags = moodTags,
            riskTags = riskTags,
        )

    fun work(id: String, vararg tags: String): Work = Work(
        id = id,
        titles = Titles(canonical = id),
        mediaType = MediaType.ANIME,
        primarySource = DomainSourceId.BANGUMI,
        tags = tags.map { Tag(TagCategory.CONTENT, it) },
    )

    "账号行按固定顺序构建：Bangumi → AniList → MAL → VNDB → AI" {
        val rows = buildAccountRows(emptyMap())
        rows.map { it.sourceId } shouldContainExactly listOf(
            SourceId.BANGUMI,
            SourceId.ANILIST,
            SourceId.MAL,
            SourceId.VNDB,
            SourceId.AI_PROVIDER,
        )
    }

    "未配置账号显示未配置且最后测试时间为空（不伪造）" {
        val row = buildAccountRows(emptyMap()).first { it.sourceId == SourceId.BANGUMI }
        row.configured shouldBe false
        row.statusText shouldBe "未配置"
        row.lastTestedAt.shouldBeNull()
    }

    "已配置 + 测试成功映射为连接正常并保留最后测试时间" {
        val statusMap = mapOf(
            SourceId.BANGUMI to CredentialStatus(
                configured = true,
                lastTestedAt = 1_700_000_000_000L,
                status = CredentialStatus.Status.TEST_SUCCESS,
            ),
        )
        val row = buildAccountRows(statusMap).first { it.sourceId == SourceId.BANGUMI }
        row.configured shouldBe true
        row.statusText shouldBe "连接正常"
        row.lastTestedAt shouldBe 1_700_000_000_000L
    }

    "测试失败映射为连接失败" {
        val statusMap = mapOf(
            SourceId.VNDB to CredentialStatus(
                configured = true,
                status = CredentialStatus.Status.TEST_FAILED,
            ),
        )
        buildAccountRows(statusMap).first { it.sourceId == SourceId.VNDB }
            .statusText shouldBe "连接失败"
    }

    "想看数等于待补池条目数；其余个人状态与个人评分为 null（暂无数据）" {
        val stats = buildMineStats(
            works = emptyList(),
            backlog = listOf(item("a"), item("b"), item("c")),
        )
        stats.wantToWatch shouldBe 3
        stats.watched.shouldBeNull()
        stats.watching.shouldBeNull()
        stats.onHold.shouldBeNull()
        stats.dropped.shouldBeNull()
        stats.averageRating.shouldBeNull()
        stats.highestRating.shouldBeNull()
    }

    "空待补池：想看为 0、常见标签为空" {
        val stats = buildMineStats(works = emptyList(), backlog = emptyList())
        stats.wantToWatch shouldBe 0
        stats.commonTags shouldHaveSize 0
    }

    "常见番剧标签按覆盖作品数排序且单部作品只计一次" {
        val works = listOf(
            work("a", "校园", "治愈", "校园"),
            work("b", "校园", "治愈"),
            work("c", "校园", "悬疑"),
        )
        val backlog = listOf(item("a"), item("b"), item("c"))
        val tags = buildMineStats(works = works, backlog = backlog).commonTags
        tags.map { it.name } shouldContainExactly listOf("校园", "治愈", "悬疑")
        tags.first().count shouldBe 3
    }

    "同步收藏只统计被收藏作品的社区标签" {
        val works = listOf(work("a", "热血"), work("b", "治愈"), work("c", "未收藏"))
        val collections = listOf(
            UserCollectionStat(workId = "a", status = "看过", rating = 9),
            UserCollectionStat(workId = "b", status = "想看", rating = null),
        )
        val tags = buildMineStats(works = works, backlog = emptyList(), collections = collections).commonTags
        tags.map { it.name } shouldContainExactly listOf("治愈", "热血")
    }
})

package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class TonightRankerTest : StringSpec({

    val year = 2026

    @Suppress("LongParameterList")
    fun c(
        id: String,
        taste: Double,
        tags: Set<String> = emptySet(),
        franchise: String = id,
        intent: Double? = null,
        exposedDaysAgo: Double? = null,
    ) = TonightRanker.Candidate(
        id = id,
        taste = taste,
        intentFit = intent,
        daysSinceExposure = exposedDaysAgo,
        franchiseKey = franchise,
        tags = tags,
    )

    "稳妥档永远是今晚分最高的一部，三档互不重复" {
        val picks = TonightRanker.pick(
            listOf(c("a", 0.9), c("b", 0.8), c("c", 0.7), c("d", 0.6)),
            count = 3,
            currentYear = year,
            random = Random(1),
        )
        picks.first().candidate.id shouldBe "a"
        picks.map { it.slot } shouldContainExactly
            listOf(TonightRanker.Slot.SAFE, TonightRanker.Slot.EXPLORE, TonightRanker.Slot.WILDCARD)
        picks.map { it.candidate.id }.toSet().size shouldBe 3
    }

    "单一推荐模式只出稳妥档" {
        val picks = TonightRanker.pick(listOf(c("a", 0.6), c("b", 0.9)), count = 1, currentYear = year)
        picks.map { it.candidate.id } shouldContainExactly listOf("b")
    }

    "同一系列最多占一个名额" {
        val picks = TonightRanker.pick(
            listOf(
                c("s1", 0.95, franchise = "saga"),
                c("s2", 0.94, franchise = "saga"),
                c("s3", 0.93, franchise = "saga"),
                c("other", 0.5, franchise = "other"),
            ),
            count = 3,
            currentYear = year,
            random = Random(7),
        )
        picks.count { it.candidate.franchiseKey == "saga" } shouldBe 1
        picks.map { it.candidate.id }.toSet() shouldBe setOf("s1", "other")
    }

    "探索档优先选与稳妥档题材拉开的高分候选" {
        val isekai = setOf("异世界", "奇幻", "冒险", "战斗")
        val picks = TonightRanker.pick(
            listOf(
                c("safe", 0.92, isekai),
                c("clone", 0.90, isekai),
                c("different", 0.88, setOf("悬疑", "推理", "科幻")),
            ),
            count = 2,
            currentYear = year,
        )
        picks[0].candidate.id shouldBe "safe"
        picks[1].candidate.id shouldBe "different"
    }

    "随机档在同一随机种子下可复现，且只从高分段抽取" {
        val pool = (1..30).map { c("w$it", 1.0 - it * 0.02) }
        val a = TonightRanker.pick(pool, 3, year, Random(42)).map { it.candidate.id }
        val b = TonightRanker.pick(pool, 3, year, Random(42)).map { it.candidate.id }
        a shouldContainExactly b
        val wildcardRank = a.last().removePrefix("w").toInt()
        (wildcardRank <= 16) shouldBe true
    }

    "意图契合度是今晚分的第二大项；意图不符封顶压住高口味分" {
        val fits = TonightRanker.score(c("f", 0.6, intent = 0.9), year)
        val misses = TonightRanker.score(c("m", 0.95, intent = 0.2), year)
        fits shouldBeGreaterThan misses
        val capped = TonightRanker.score(c("x", 1.0, intent = 0.4).copy(scoreCap = 55), year)
        (capped <= 0.55) shouldBe true
        val conflicted = TonightRanker.score(c("y", 0.8, intent = 0.7).copy(dominantConflict = 0.6), year)
        conflicted shouldBeLessThan TonightRanker.score(c("y", 0.8, intent = 0.7), year)
    }

    "刚推荐过的作品被降权，惩罚随时间衰减" {
        val fresh = TonightRanker.score(c("x", 0.8), year)
        val yesterday = TonightRanker.score(c("x", 0.8, exposedDaysAgo = 1.0), year)
        val twoWeeks = TonightRanker.score(c("x", 0.8, exposedDaysAgo = 14.0), year)
        yesterday shouldBeLessThan twoWeeks
        twoWeeks shouldBeLessThan fresh
    }

    "未选标签时意图权重并入口味：口味满分的今晚分不低于选了标签且全中" {
        val noIntent = TonightRanker.score(c("a", 1.0), year)
        val fullIntent = TonightRanker.score(c("a", 1.0, intent = 1.0), year)
        noIntent shouldBe fullIntent
    }

    "时间拟合：能看完 > 只能开个头 > 单集都放不下；未知为中性" {
        val finishable = TonightRanker.sessionFit(episodes = 6, episodeMinutes = 24, budgetMinutes = 180)
        val startOnly = TonightRanker.sessionFit(episodes = 24, episodeMinutes = 24, budgetMinutes = 180)
        val movieIn20 = TonightRanker.sessionFit(episodes = 1, episodeMinutes = 110, budgetMinutes = 20)
        finishable shouldBeGreaterThan startOnly
        startOnly shouldBeGreaterThan movieIn20
        TonightRanker.sessionFit(null, null, 60) shouldBe 0.5
        // 3 小时：刚好填满的一季短篇优于只有一集的短片。
        TonightRanker.sessionFit(7, 24, 180) shouldBeGreaterThan TonightRanker.sessionFit(1, 5, 180)
    }

    "硬过滤只在单集时长已知且明显超出预算时生效" {
        TonightRanker.episodeFitsBudget(110, 20) shouldBe false
        TonightRanker.episodeFitsBudget(24, 20) shouldBe true
        TonightRanker.episodeFitsBudget(null, 20) shouldBe true
        TonightRanker.episodeFitsBudget(110, null) shouldBe true
    }

    "补完轻松度：已完结短篇最高，未完结长篇最低" {
        TonightRanker.completionEase(12, true) shouldBeGreaterThan TonightRanker.completionEase(12, false)
        TonightRanker.completionEase(12, true) shouldBeGreaterThan TonightRanker.completionEase(100, true)
    }
})

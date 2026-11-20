package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

class ExploreRankerTest : StringSpec({

    val isekai = setOf("异世界", "奇幻", "冒险")
    val romance = setOf("恋爱", "校园", "日常")
    val empty = ExploreRanker.feedbackModel(emptyList())

    @Suppress("LongParameterList")
    fun c(
        id: String,
        score: Int,
        tags: Set<String> = emptySet(),
        franchise: String = id,
        familiarity: Double = 1.0,
        community: Double? = null,
        votes: Int = 0,
    ) = ExploreRanker.Candidate(id, score, familiarity, community, votes, franchise, tags)

    "反馈太少时不做任何修正" {
        val model = ExploreRanker.feedbackModel(listOf(ExploreRanker.Feedback(isekai, liked = false)))
        model.isEmpty shouldBe true
        model.adjust(isekai) shouldBe 0.0
    }

    "反复划走的题材被下调，常加入的题材被上调，幅度封顶" {
        val feedback = List(6) { ExploreRanker.Feedback(isekai, liked = false) } +
            List(6) { ExploreRanker.Feedback(romance, liked = true) }
        val model = ExploreRanker.feedbackModel(feedback)
        model.adjust(isekai) shouldBeLessThan 0.0
        model.adjust(romance) shouldBeGreaterThan 0.0
        (model.adjust(isekai) >= -ExploreRanker.MAX_FEEDBACK_ADJUST) shouldBe true
        (model.adjust(romance) <= ExploreRanker.MAX_FEEDBACK_ADJUST) shouldBe true
        model.adjust(setOf("从未出现过的题材")) shouldBe 0.0
    }

    "同一系列在一批里最多出现一次" {
        val batch = ExploreRanker.buildBatch(
            listOf(
                c("s1", 90, isekai, franchise = "saga"),
                c("s2", 89, isekai, franchise = "saga"),
                c("s3", 88, isekai, franchise = "saga"),
                c("x", 60, romance),
            ),
            empty,
            batchSize = 3,
        )
        batch.map { it.candidate.id } shouldContainExactly listOf("s1", "x")
    }

    "多样性：分数接近时不让同题材占满整批" {
        val clones = (1..8).map { c("iso$it", 90 - it, isekai) }
        val others = listOf(c("rom", 80, romance), c("mys", 79, setOf("悬疑", "推理")))
        val batch = ExploreRanker.buildBatch(clones + others, empty, batchSize = 4)
        val ids = batch.map { it.candidate.id }
        ids.first() shouldBe "iso1"
        (ids.contains("rom") || ids.contains("mys")) shouldBe true
    }

    "跨界名额：口碑扎实且画像不熟的作品进入批次并被标记，但不会排在第一张" {
        val main = (1..12).map { c("m$it", 92 - it, setOf("题材$it")) }
        val discovery = c("d1", 55, setOf("纪实", "职人"), familiarity = 0.1, community = 8.4, votes = 5000)
        val weakCommunity = c("d2", 55, setOf("纪实2"), familiarity = 0.1, community = 6.5, votes = 5000)
        val batch = ExploreRanker.buildBatch(main + discovery + weakCommunity, empty, batchSize = 10)
        batch.size shouldBe 10
        val picked = batch.first { it.candidate.id == "d1" }
        picked.discovery shouldBe true
        batch.first().discovery shouldBe false
        batch.none { it.candidate.id == "d2" } shouldBe true
    }

    "没有够格的跨界候选时用主力补满，不留空位" {
        val batch = ExploreRanker.buildBatch((1..20).map { c("m$it", 90 - it, setOf("t$it")) }, empty, 10)
        batch.size shouldBe 10
        batch.none { it.discovery } shouldBe true
    }

    "滑动后只重排尚未看到的卡片，当前与下一张保持不动" {
        val feedback = List(5) { ExploreRanker.Feedback(isekai, liked = false) } +
            List(5) { ExploreRanker.Feedback(romance, liked = true) }
        val model = ExploreRanker.feedbackModel(feedback)
        val cards = listOf(
            c("seen", 90, isekai),
            c("next", 85, isekai),
            c("iso", 80, isekai),
            c("rom", 78, romance),
        )
        val reranked = ExploreRanker.rerankRemaining(cards, keepUntil = 1, model = model) { it }
        reranked.map { it.id } shouldContainExactly listOf("seen", "next", "rom", "iso")
        // 无反馈时原样返回。
        ExploreRanker.rerankRemaining(cards, keepUntil = 1, model = empty) { it } shouldBe cards
    }

    "random batches vary between visits but still favour high-scoring works" {
        val pool = (1..40).map { c("m$it", 91 - it, setOf("t$it")) }
        val fixed = ExploreRanker.buildBatch(pool, empty, 10).map { it.candidate.id }
        val runs = (1..50).map { seed ->
            ExploreRanker.buildBatch(pool, empty, 10, kotlin.random.Random(seed)).map { it.candidate.id }
        }
        // 至少有一部分批次的顺序和确定版本不同，且彼此之间也不全一样。
        (runs.count { it != fixed } > 40) shouldBe true
        (runs.toSet().size > 30) shouldBe true
        // 最高分的作品绝大多数时候仍然在这一批里，排在很后面的几乎不会出现。
        (runs.count { "m1" in it } >= 45) shouldBe true
        (runs.count { "m40" in it } <= 2) shouldBe true
    }
})

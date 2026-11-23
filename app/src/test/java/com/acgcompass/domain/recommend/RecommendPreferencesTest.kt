package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class RecommendPreferencesTest : StringSpec({

    "存储文本能原样读回，缺的键用默认值，越界的收回范围，没有版本号的旧格式按默认处理" {
        val custom = RecommendPreferences(
            taste = 3,
            community = RecommendPreferences.UNDERRATED,
            underratedBelow = 65,
            freshness = 1,
            obscurity = 3,
            discovery = 0,
        )
        RecommendPreferences.decode(custom.encode()) shouldBe custom
        RecommendPreferences.decode(null) shouldBe RecommendPreferences.DEFAULT
        RecommendPreferences.decode("v=3;taste=9;community=-5;oops;obscurity=7;underratedBelow=68") shouldBe
            RecommendPreferences(
                taste = 3,
                community = RecommendPreferences.UNDERRATED,
                obscurity = 3,
                underratedBelow = 70,
            )
        RecommendPreferences.decode("taste=2;community=2;niche=4;upside=2;nicheOnly=1") shouldBe
            RecommendPreferences.DEFAULT
        RecommendPreferences.decode("v=2;taste=2;popularity=-1") shouldBe RecommendPreferences(taste = 2, obscurity = 2)
        RecommendPreferences.decode("v=2;popularity=2") shouldBe RecommendPreferences.DEFAULT
    }

    "档位权重：不看 0、低 0.25、中 0.5、高 1；冷门减半；被低估时口碑本身不加权" {
        RecommendPreferences.weightOf(0) shouldBe 0.0
        RecommendPreferences.weightOf(1) shouldBe 0.25
        RecommendPreferences.weightOf(2) shouldBe 0.5
        RecommendPreferences.weightOf(3) shouldBe 1.0
        RecommendPreferences(obscurity = 3).popularityWeight shouldBe -0.5
        RecommendPreferences(obscurity = 1).popularityWeight shouldBe -0.125
        RecommendPreferences(community = RecommendPreferences.UNDERRATED).communityWeight shouldBe 0.0
        RecommendPreferences(community = RecommendPreferences.UNDERRATED).reranks shouldBe false
        RecommendPreferences.DEFAULT.reranks shouldBe false
        RecommendPreferences(freshness = 1).reranks shouldBe true
    }

    "被低估：社区分已知、评分人数够、低于上限才通过；不开时一律通过" {
        val prefs = RecommendPreferences(community = RecommendPreferences.UNDERRATED, underratedBelow = 70)
        prefs.passesUnderrated(6.9, 500) shouldBe true
        prefs.passesUnderrated(7.0, 500) shouldBe false
        prefs.passesUnderrated(6.0, 50) shouldBe false
        prefs.passesUnderrated(null, 500) shouldBe false
        RecommendPreferences.DEFAULT.passesUnderrated(null, 0) shouldBe true
    }

    "默认偏好下探索队列的排序分原样不动" {
        val random = Random(3)
        val items = (0 until 60).map {
            PreferenceRanking.Factors(
                id = "w$it",
                base = random.nextInt(5, 96).toDouble(),
                match = random.nextInt(100).toDouble(),
                community = 5.0 + random.nextDouble() * 4,
                freshness = random.nextInt(20_000).toDouble(),
                popularity = random.nextDouble(),
            )
        }
        PreferenceRanking.rerank(items, RecommendPreferences.DEFAULT) shouldBe items.associate { it.id to it.base }
    }

    "跨界名额可以关掉" {
        val model = ExploreRanker.feedbackModel(emptyList())
        val main = (1..20).map { ExploreRanker.Candidate("m$it", 90 - it, tags = setOf("t$it")) }
        val discovery = (1..5).map {
            ExploreRanker.Candidate(
                "d$it",
                50,
                familiarity = 0.2,
                community10 = 8.5,
                votes = 5000,
                tags = setOf("x$it"),
            )
        }
        val withDefault = ExploreRanker.buildBatch(main + discovery, model, batchSize = 10)
        withDefault.count { it.discovery } shouldBe 3
        val none = ExploreRanker.buildBatch(
            main + discovery,
            model,
            batchSize = 10,
            discoveryShare = RecommendPreferences(discovery = 0).discoveryShare,
        )
        none.count { it.discovery } shouldBe 0
    }

    "今晚看什么：默认偏好的今晚分不变；看重口味契合时匹配度高的作品往前，分数还是输入的那一组" {
        val a = TonightRanker.Candidate(id = "a", taste = 0.80, community10 = 7.5, releaseYear = 2020, match = 60.0)
        val b = TonightRanker.Candidate(id = "b", taste = 0.78, community10 = 7.5, releaseYear = 2020, match = 95.0)
        val c = TonightRanker.Candidate(id = "c", taste = 0.40, community10 = 7.5, releaseYear = 2020, match = 70.0)
        val scored = listOf(a, b, c).map { it to TonightRanker.score(it, 2026) }
        TonightRanker.withPreferences(scored, RecommendPreferences.DEFAULT) shouldBe scored
        val adjusted = TonightRanker.withPreferences(scored, RecommendPreferences(taste = 3)).toMap()
        (adjusted.getValue(b) > adjusted.getValue(a)) shouldBe true
        adjusted.values.sorted() shouldBe scored.map { it.second }.sorted()
    }
})

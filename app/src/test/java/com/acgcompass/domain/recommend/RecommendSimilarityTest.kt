package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RecommendSimilarityTest : StringSpec({

    "同系列不同季度归并到同一个键" {
        val base = RecommendSimilarity.franchiseKey("进击的巨人")
        RecommendSimilarity.franchiseKey("进击的巨人 第二季") shouldBe base
        RecommendSimilarity.franchiseKey("进击的巨人 最终季") shouldBe base
        RecommendSimilarity.franchiseKey("進撃の巨人 Season 3") shouldBe RecommendSimilarity.franchiseKey("進撃の巨人")
        RecommendSimilarity.franchiseKey("Mushoku Tensei 2nd Season") shouldBe
            RecommendSimilarity.franchiseKey("Mushoku Tensei")
        RecommendSimilarity.franchiseKey("K-ON!! 2") shouldBe RecommendSimilarity.franchiseKey("K-ON!")
        RecommendSimilarity.franchiseKey("响け！ユーフォニアム 劇場版") shouldBe
            RecommendSimilarity.franchiseKey("响け！ユーフォニアム")
    }

    "不同作品不会被误并，剥离后为空时回退原标题" {
        RecommendSimilarity.franchiseKey("命运石之门") shouldNotBe RecommendSimilarity.franchiseKey("命运石之门0的世界线")
        RecommendSimilarity.franchiseKey("剧场版") shouldBe "剧场版"
        RecommendSimilarity.franchiseKey("86") shouldBe "86"
    }

    "题材指纹剔除年份 / 媒介格式并限长" {
        val fp = RecommendSimilarity.genreFingerprint(
            listOf("2023年10月", "TV", "奇幻", "冒险", "治愈", "漫画改", "奇幻"),
        )
        fp shouldBe setOf("奇幻", "冒险", "治愈")
        RecommendSimilarity.genreFingerprint((1..20).map { "题材$it" }).size shouldBe
            RecommendSimilarity.MAX_SIMILARITY_TAGS
    }

    "Jaccard：空集不算相似" {
        RecommendSimilarity.jaccard(setOf("a", "b"), setOf("b", "c")) shouldBe (1.0 / 3.0 plusOrMinus 1e-9)
        RecommendSimilarity.jaccard(emptySet(), setOf("a")) shouldBe 0.0
    }
})

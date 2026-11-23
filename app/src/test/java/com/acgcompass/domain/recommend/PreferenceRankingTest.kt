package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * 推荐偏好的重排：正态分数、按档位相加、按名次发回输入的那组分数。
 */
class PreferenceRankingTest : StringSpec({

    "标准正态分位函数和已知值一致" {
        PreferenceRanking.inverseNormal(0.5) shouldBe (0.0 plusOrMinus 1e-9)
        PreferenceRanking.inverseNormal(0.975) shouldBe (1.959964 plusOrMinus 1e-5)
        PreferenceRanking.inverseNormal(0.01) shouldBe (-2.326348 plusOrMinus 1e-5)
        PreferenceRanking.inverseNormal(0.9999) shouldBe (3.719016 plusOrMinus 1e-4)
    }

    "正态分数：按名次换算，并列取平均名次，缺数据为 0，已知值不足两个时全为 0" {
        val z = PreferenceRanking.normalScores(listOf(1.0, 3.0, 3.0, null, 10.0))
        z[3] shouldBe 0.0
        z[1] shouldBe z[2]
        z[1] shouldBe (0.0 plusOrMinus 1e-12) // 四个已知值里并列第 2、3 名 → 平均 2.5 → 分位 0.5
        (z[4] > z[1] && z[1] > z[0]) shouldBe true
        z[0] shouldBe (PreferenceRanking.inverseNormal(0.5 / 4) plusOrMinus 1e-12)
        PreferenceRanking.normalScores(listOf(null, 2.0)).toList() shouldBe listOf(0.0, 0.0)
    }

    "重排只换顺序：发回的分数和输入的那组完全一样" {
        val random = Random(7)
        val items = (0 until 200).map {
            PreferenceRanking.Factors(
                id = "w$it",
                base = random.nextInt(5, 96).toDouble(),
                match = random.nextInt(100).toDouble(),
                freshness = random.nextInt(20_000).toDouble(),
            )
        }
        val out = PreferenceRanking.rerank(items, RecommendPreferences(taste = 2, freshness = 1))
        out.values.sorted() shouldBe items.map { it.base }.sorted()
        out.keys shouldBe items.map { it.id }.toSet()
    }

    "权重是渐进的：稍微只让匹配度明显更高、满意度略低的作品超过，满意度差很多的超不过" {
        // 200 部候选里满意度第 1 名匹配度一般；第 3 名匹配度最高；第 50 名匹配度也最高档。
        val random = Random(11)
        val filler = (0 until 197).map {
            PreferenceRanking.Factors("f$it", 20.0 + random.nextDouble() * 50, match = random.nextDouble() * 80)
        }
        val top = PreferenceRanking.Factors("top", 95.0, match = 40.0)
        val near = PreferenceRanking.Factors("near", 90.0, match = 99.0)
        val far = PreferenceRanking.Factors("far", 45.0, match = 99.5)
        val items = filler + listOf(top, near, far)
        fun order(prefs: RecommendPreferences): List<String> {
            val out = PreferenceRanking.rerank(items, prefs)
            return out.entries.sortedByDescending { it.value }.map { it.key }
        }
        order(RecommendPreferences.DEFAULT).take(2) shouldBe listOf("top", "near")
        order(RecommendPreferences(taste = 1)).first() shouldBe "near"
        (order(RecommendPreferences(taste = 1)).indexOf("far") > 10) shouldBe true
        val farAtMost = order(RecommendPreferences(taste = 3)).indexOf("far")
        (farAtMost < order(RecommendPreferences(taste = 1)).indexOf("far")) shouldBe true
    }

    "看重冷门时冷门作品往前，档位越高越靠前" {
        val items = listOf(
            PreferenceRanking.Factors("hot", 80.0, popularity = 0.98),
            PreferenceRanking.Factors("cold", 79.0, popularity = 0.30),
            PreferenceRanking.Factors("mid", 50.0, popularity = 0.70),
            PreferenceRanking.Factors("low", 30.0, popularity = 0.60),
        )
        fun first(prefs: RecommendPreferences) = PreferenceRanking.rerank(items, prefs).maxBy { it.value }.key
        first(RecommendPreferences.DEFAULT) shouldBe "hot"
        first(RecommendPreferences(obscurity = 3)) shouldBe "cold"
    }

    "口碑用收缩后的社区分：人数少的高分往 6.5 拉" {
        PreferenceRanking.shrunkCommunity(9.0, 30)!! shouldBe ((30 * 9.0 + 300 * 6.5) / 330 plusOrMinus 1e-12)
        PreferenceRanking.shrunkCommunity(null, 1000) shouldBe null
        PreferenceRanking.shrunkCommunity(0.0, 1000) shouldBe null
    }
})

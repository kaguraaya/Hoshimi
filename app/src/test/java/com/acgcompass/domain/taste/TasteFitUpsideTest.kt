package com.acgcompass.domain.taste

import com.acgcompass.domain.model.MediaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.ln

/**
 * 匹配度的「上尾」：打 9 分以上的可能比作品库一般作品高时往上加（激进最多、常规少量、保守不加）；
 * 作品库样本按编号散列固定挑选，作品库增减几部时其余样本不变。
 */
class TasteFitUpsideTest : StringSpec({
    fun sample(quality: Double, chance: Double) = doubleArrayOf(quality, 0.0, 50.0, 0.5, ln(chance / (1 - chance)))
    val pool = (0 until 120).map { i -> sample(6.0 + (i % 30) / 10.0, 0.02 + (i % 20) / 100.0) }
    val watched = (0 until 20).map { i -> sample(7.0 + (i % 10) / 10.0, 0.1 + (i % 10) / 50.0) }
    val scale = TasteFit.scaleOf(pool, watched)!!

    "a work more likely to be a nine or ten scores higher in aggressive and standard, not in conservative" {
        val plain = sample(7.5, 0.10)
        val likely = sample(7.5, 0.60)
        fun gain(branch: TasteMatchBranch) = TasteFit.raw(likely, scale, branch) - TasteFit.raw(plain, scale, branch)
        gain(TasteMatchBranch.AGGRESSIVE) shouldBeGreaterThan gain(TasteMatchBranch.STANDARD)
        gain(TasteMatchBranch.STANDARD) shouldBeGreaterThan 0.0
        gain(TasteMatchBranch.CONSERVATIVE) shouldBe 0.0
        TasteFit.score(likely, scale, TasteMatchBranch.AGGRESSIVE) shouldBeGreaterThanOrEqual
            TasteFit.score(plain, scale, TasteMatchBranch.AGGRESSIVE)
    }

    "the upside only adds: a below-average chance does not pull the score down" {
        val unlikely = sample(7.5, 0.01)
        val average = sample(7.5, scale.upside!!.mean.let { 1.0 / (1.0 + kotlin.math.exp(-it)) })
        val aggressive = TasteMatchBranch.AGGRESSIVE
        TasteFit.raw(unlikely, scale, aggressive) shouldBe TasteFit.raw(average, scale, aggressive)
    }

    "a missing chance counts as no upside" {
        val missing = sample(7.5, 0.5).also { it[4] = Double.NaN }
        val unlikely = sample(7.5, 0.01)
        val aggressive = TasteMatchBranch.AGGRESSIVE
        TasteFit.raw(missing, scale, aggressive) shouldBe TasteFit.raw(unlikely, scale, aggressive)
    }

    "the pool sample is stable when a few works are added or removed" {
        val works = (1..3000).map { WorkFeature(subjectId = it.toString(), tagCounts = emptyList()) }
        val before = TasteFit.poolSample(works).map { it.subjectId }.toSet()
        val removed = works.filterIndexed { i, _ -> i % 100 != 7 } // 去掉 30 部
        val after = TasteFit.poolSample(removed).map { it.subjectId }.toSet()
        before shouldHaveSize TasteFit.SAMPLE_SIZE
        (before intersect after).size shouldBeGreaterThanOrEqual TasteFit.SAMPLE_SIZE - 30
    }

    "only anime enters the taste model; works without a type count as anime" {
        WorkFeature(subjectId = "1", mediaType = MediaType.ANIME).isAnimeForTaste shouldBe true
        WorkFeature(subjectId = "2").isAnimeForTaste shouldBe true
        WorkFeature(subjectId = "3", mediaType = MediaType.GAME).isAnimeForTaste shouldBe false
        WorkFeature(subjectId = "4", mediaType = MediaType.MANGA).isAnimeForTaste shouldBe false
    }
})

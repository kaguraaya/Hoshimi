package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class PopularityReferenceTest : StringSpec({
    val p = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98)
    val table = PopularityReference.Table(
        percentiles = p,
        years = mapOf(
            2010 to intArrayOf(5, 10, 30, 80, 150, 300, 600, 1200, 2500, 4000, 7000),
            2024 to intArrayOf(20, 60, 150, 300, 600, 1000, 1500, 2200, 3200, 5000, 9000),
        ),
    )
    val today = LocalDate.of(2026, 11, 22)

    "落在分位点上时就是那个分位，中间按评分人数的对数插值" {
        val day = PopularityReference.airDay("2024-04-06", null)
        PopularityReference.percentile(1000, day, today, table)!! shouldBe (0.6 plusOrMinus 1e-9)
        PopularityReference.percentile(3200, day, today, table)!! shouldBe (0.9 plusOrMinus 1e-9)
        val mid = PopularityReference.percentile(1225, day, today, table)!!
        (mid > 0.6 && mid < 0.7) shouldBe true
    }

    "同样的评分人数，老作品比新作品更「热门」（同年作品整体人数少）" {
        val old = PopularityReference.percentile(1200, PopularityReference.airDay("2010-04-01", null), today, table)!!
        val new = PopularityReference.percentile(1200, PopularityReference.airDay("2024-04-01", null), today, table)!!
        (old > new) shouldBe true
    }

    "表外年份用最近的一年；开播不满 150 天、没有评分人数、没有日期时不知道" {
        val future = PopularityReference.percentile(1000, PopularityReference.airDay("2026-01-10", null), today, table)
        future!! shouldBe (0.6 plusOrMinus 1e-9)
        PopularityReference.percentile(1000, PopularityReference.airDay("2026-10-01", null), today, table) shouldBe null
        PopularityReference.percentile(0, PopularityReference.airDay("2020-01-01", null), today, table) shouldBe null
        PopularityReference.percentile(1000, null, today, table) shouldBe null
    }

    "热度档：前 15% 霸权、前 25% 热门、前 45% 普通" {
        PopularityReference.tierOf(0.95) shouldBe PopularityReference.Tier.TOP
        PopularityReference.tierOf(0.8) shouldBe PopularityReference.Tier.HOT
        PopularityReference.tierOf(0.65) shouldBe PopularityReference.Tier.NORMAL
        PopularityReference.tierOf(0.3) shouldBe PopularityReference.Tier.COLD
    }

    "开播日：年-月-日、年-月、只有年份都能换算" {
        PopularityReference.airDay("2024-04-06", null) shouldBe LocalDate.of(2024, 4, 6).toEpochDay()
        PopularityReference.airDay("2024-04", null) shouldBe LocalDate.of(2024, 4, 1).toEpochDay()
        PopularityReference.airDay(null, 2019) shouldBe LocalDate.of(2019, 7, 1).toEpochDay()
        PopularityReference.airDay("未知", null) shouldBe null
    }

    "内置参照表每年的分位点都不递减，并且覆盖到最近的年份" {
        val years = PopularityReference.BANGUMI.years
        (years.keys.max() >= 2025) shouldBe true
        years.values.forEach { knots -> knots.toList().zipWithNext().all { (a, b) -> a <= b } shouldBe true }
    }
})

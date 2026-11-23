package com.acgcompass.domain.recommend

import com.acgcompass.domain.recommend.intent.TagRepresentativeness
import com.acgcompass.domain.taste.TagCount
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class TonightModesTest : StringSpec({

    fun rep(vararg tags: Pair<String, Int>) = TagRepresentativeness(tags.map { TagCount(it.first, it.second) })

    // 和「86 -不存在的战区- 第2部分」差不多的标签：战争是主打，催泪、科幻在后面。
    val warLike = rep("战争" to 900, "机战" to 700, "科幻" to 600, "催泪" to 300, "轻小说改" to 250, "致郁" to 120)

    "深夜模式排除主打战争、致郁的作品" {
        val verdict = TonightModes.verdict(lowBurden = false, lateNight = true, rep = warLike)
        verdict.excludedBy shouldBe "主打「战争」"
    }

    "低负担模式不管情绪，只管投入：标签上没有烧脑就不按标签排除" {
        TonightModes.verdict(lowBurden = true, lateNight = false, rep = warLike).excludedBy shouldBe null
    }

    "低负担模式排除没看过前作的续作，看过前作就放行" {
        val titles = listOf("86 -不存在的战区- 第2部分", "86―エイティシックス― 第2クール")
        val sequel = TonightModes.isSequel(titles)
        sequel shouldBe true
        TonightModes.lowBurdenBlocker(finished = true, episodes = 11, sequel = sequel, prequelWatched = false) shouldBe
            "续作，要先看前作"
        TonightModes.lowBurdenBlocker(finished = true, episodes = 11, sequel = sequel, prequelWatched = true) shouldBe
            null
    }

    "低负担模式排除未完结和超过一季的作品" {
        TonightModes.lowBurdenBlocker(finished = false, episodes = 12, sequel = false, prequelWatched = false) shouldBe
            "还没完结"
        TonightModes.lowBurdenBlocker(finished = true, episodes = 24, sequel = false, prequelWatched = false) shouldBe
            "超过 13 集"
        TonightModes.lowBurdenBlocker(finished = true, episodes = null, sequel = false, prequelWatched = false) shouldBe
            null
    }

    "主打烧脑的作品被低负担排除" {
        val brainy = rep("烧脑" to 800, "悬疑" to 600, "推理" to 400)
        TonightModes.verdict(lowBurden = true, lateNight = false, rep = brainy).excludedBy shouldBe "主打「烧脑」"
    }

    "只沾一点的气质不排除，只往后排" {
        // 「致郁」排第 8、标注人数是最多的 6%：代表性约 0.27，在沾边（0.2）和主打（0.4）之间。
        val mild = rep(
            "日常" to 1000, "校园" to 900, "搞笑" to 800, "恋爱" to 700, "后宫" to 600,
            "百合" to 500, "音乐" to 400, "致郁" to 60, "运动" to 50, "偶像" to 40,
        )
        val verdict = TonightModes.verdict(lowBurden = false, lateNight = true, rep = mild)
        verdict.excludedBy shouldBe null
        verdict.penalty shouldBeGreaterThan 0.0
    }

    "用户这次主动选了的标签不算冲突" {
        val mystery = rep("悬疑" to 900, "推理" to 700, "校园" to 300)
        TonightModes.verdict(lowBurden = false, lateNight = true, rep = mystery).excludedBy shouldNotBe null
        TonightModes.verdict(
            lowBurden = false,
            lateNight = true,
            rep = mystery,
            selectedTags = setOf("悬疑", "推理"),
        ).excludedBy shouldBe null
    }

    "深夜模式给轻松日常的作品加分，关掉模式就没有" {
        val calm = rep("日常" to 900, "治愈" to 800, "搞笑" to 500)
        TonightModes.verdict(lowBurden = false, lateNight = true, rep = calm).calm shouldBeGreaterThan
            TonightModes.STRONG
        TonightModes.verdict(lowBurden = false, lateNight = false, rep = calm).calm shouldBe 0.0
    }

    "续作判定不把标题里的普通数字当成季数" {
        TonightModes.isSequel(listOf("22/7")) shouldBe false
        TonightModes.isSequel(listOf("86 -不存在的战区-")) shouldBe false
        TonightModes.isSequel(listOf("摇曳露营△ 第二季")) shouldBe true
        TonightModes.isSequel(listOf("Overlord II")) shouldBe true
        TonightModes.isSequel(listOf("Kaguya-sama 2nd Season")) shouldBe true
    }
})

package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** 圈子分：你常收藏的那类排在前面，从不碰的类型排在后面（「长期不碰」本身就是信号）。 */
class TasteCircleTest : StringSpec({
    fun work(id: String, vararg tags: String) =
        WorkFeature(subjectId = id, tagCounts = tags.mapIndexed { i, t -> TagCount(t, 100 - i * 10) }, platform = "TV")

    val collected = (0 until 20).associate { i ->
        "c$i" to (work("c$i", "校园", "恋爱", if (i % 2 == 0) "日常" else "搞笑") to 1.0)
    }
    val pool = (0 until 40).map { work("p$it", "国产", "儿童", "冒险") } +
        (0 until 40).map { work("q$it", "校园", "日常", "治愈") } +
        (0 until 40).map { work("r$it", "科幻", "战斗", "机战") }

    "works like the ones you collect score above the types you never touch" {
        val circle = TasteCircle.fit(collected, pool)
        circle shouldNotBe null
        val familiar = circle!!.z(work("x1", "校园", "日常", "治愈"))
        val avoided = circle.z(work("x2", "国产", "儿童", "冒险"))
        (familiar > 1.0) shouldBe true
        (avoided < 0.0) shouldBe true
    }

    "too few collected works gives no circle" {
        TasteCircle.fit(collected.entries.take(3).associate { it.toPair() }, pool) shouldBe null
    }
})

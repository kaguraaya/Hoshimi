package com.acgcompass.domain.usecase

import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * [TasteStatsCalculator] 的单元与属性测试。
 *
 * - 示例用例覆盖：高/低分阈值切分、单作品内标签去重、常用短评词统计、常见搁置类型、空输入。
 * - 属性测试覆盖 「口味统计守恒」的守恒部分（计数非负、桶样本数之和不超过样本总数、
 *   桶内标签计数不超过该桶样本数）。低样本置信由 `TasteProfileRepositoryImpl` 承担。
 */
@OptIn(ExperimentalKotest::class)
class TasteStatsCalculatorTest : StringSpec({

    val calculator = TasteStatsCalculator()

    // --- 示例用例 ---------------------------------------------------------

    "空输入产出全零统计" {
        val stats = calculator(emptyList())
        stats.sampleSize shouldBe 0
        stats.highScoreSampleCount shouldBe 0
        stats.lowScoreSampleCount shouldBe 0
        stats.droppedSampleCount shouldBe 0
        stats.highScoreTags shouldBe emptyList()
        stats.lowScoreTags shouldBe emptyList()
        stats.commonReviewWords shouldBe emptyList()
        stats.droppedTypes shouldBe emptyList()
    }

    "按分数阈值切分高分与低分标签" {
        val records = listOf(
            TasteInputRecord(rating = 9, tags = listOf("科幻", "硬核")),
            TasteInputRecord(rating = 8, tags = listOf("科幻", "战斗")),
            TasteInputRecord(rating = 3, tags = listOf("恋爱", "日常")),
            TasteInputRecord(rating = 6, tags = listOf("中庸")), // 中间分：两桶都不计入
        )
        val stats = calculator(records)

        stats.sampleSize shouldBe 4
        stats.highScoreSampleCount shouldBe 2
        stats.lowScoreSampleCount shouldBe 1

        // 高分桶：科幻出现于两条高分记录 → 计数 2，居首。
        stats.highScoreTags.first() shouldBe NameCount("科幻", 2)
        stats.lowScoreTags.map { it.name } shouldContainExactlyInAnyOrder listOf("日常", "恋爱")
    }

    "单条记录内重复标签只计一次" {
        val records = listOf(
            TasteInputRecord(rating = 10, tags = listOf("治愈", "治愈", "治愈")),
        )
        val stats = calculator(records)
        stats.highScoreTags shouldContainExactly listOf(NameCount("治愈", 1))
    }

    "统计常用短评词并过滤停用词与过短 token" {
        val records = listOf(
            TasteInputRecord(rating = 9, reviewText = "节奏 很好 the 节奏"),
            TasteInputRecord(rating = 7, reviewText = "节奏 不错"),
        )
        val stats = calculator(records)
        // "节奏" 出现 3 次居首；"很好"/"不错" 各 1；"the" 为停用词被过滤。
        stats.commonReviewWords.first() shouldBe NameCount("节奏", 3)
        stats.commonReviewWords.map { it.name } shouldContainExactlyInAnyOrder listOf("节奏", "不错", "很好")
    }

    "统计常见搁置/抛弃类型" {
        val records = listOf(
            TasteInputRecord(rating = null, tags = listOf("长篇", "热血"), status = "dropped"),
            TasteInputRecord(rating = null, tags = listOf("长篇"), status = "on_hold"),
            TasteInputRecord(rating = 8, tags = listOf("长篇"), status = "collect"), // 非搁置不计
        )
        val stats = calculator(records)
        stats.droppedSampleCount shouldBe 2
        stats.droppedTypes.first() shouldBe NameCount("长篇", 2)
    }

    // --- 近因加权 ----------------------------------------------------------

    "时间建权：近期评分的标签排序优先于更高频次的早期标签" {
        val halfLife = TasteStatsCalculator.RECENCY_HALF_LIFE_MILLIS
        val now = 10L * halfLife
        val old = now - 3L * halfLife // 约 3 个半衰期前 → 权重衰减到地板 0.3
        val records = listOf(
            // 早期高分：标签 A 出现两条（原始计数 2），但久远。
            TasteInputRecord(rating = 9, tags = listOf("A"), updatedAt = old),
            TasteInputRecord(rating = 9, tags = listOf("A"), updatedAt = old),
            // 近期高分：标签 B 仅一条（原始计数 1），但为最新（权重 1.0）。
            TasteInputRecord(rating = 9, tags = listOf("B"), updatedAt = now),
        )
        val stats = calculator(records)
        // A 加权 ≈ 0.3 + 0.3 = 0.6 < B 加权 1.0 → B 居首，尽管 A 原始计数更高。
        stats.highScoreTags.first().name shouldBe "B"
        // 展示计数仍为原始出现次数：A=2、B=1（直观且满足守恒）。
        stats.highScoreTags.first { it.name == "A" }.count shouldBe 2
        stats.highScoreTags.first { it.name == "B" }.count shouldBe 1
    }

    "无时间戳时退化为未加权（向后兼容）：仍按原始频次排序" {
        val records = listOf(
            TasteInputRecord(rating = 9, tags = listOf("科幻")),
            TasteInputRecord(rating = 9, tags = listOf("科幻")),
            TasteInputRecord(rating = 9, tags = listOf("日常")),
        )
        val stats = calculator(records)
        stats.highScoreTags.first() shouldBe NameCount("科幻", 2)
    }

    // --- : 口味统计守恒 ----------------------------------------

    val recordArb: Arb<TasteInputRecord> = Arb.bind(
        Arb.int(1, 10).orNull(0.2),
        Arb.list(Arb.string(1, 6), 0..4),
        Arb.string(0, 12).orNull(0.3),
        Arb.of("dropped", "on_hold", "collect", "do", "wish", null),
    ) { rating, tags, review, status ->
        TasteInputRecord(rating = rating, tags = tags, reviewText = review, status = status)
    }

    ": 计数守恒——非负、桶样本数之和不超过样本总数、桶内标签计数不超过桶样本数" {
        checkAll(PropTestConfig(iterations = 200), Arb.list(recordArb, 0..40)) { records ->
            val stats = calculator(records)

            stats.sampleSize shouldBe records.size

            // 所有桶样本数非负。
            stats.highScoreSampleCount shouldBeGreaterThanOrEqualTo 0
            stats.lowScoreSampleCount shouldBeGreaterThanOrEqualTo 0
            stats.droppedSampleCount shouldBeGreaterThanOrEqualTo 0

            // 高/低分阈值不重叠 → 一条记录至多落入一个分数桶 → 两桶样本数之和不超过样本总数。
            (stats.highScoreSampleCount + stats.lowScoreSampleCount) shouldBeLessThanOrEqualTo stats.sampleSize
            stats.droppedSampleCount shouldBeLessThanOrEqualTo stats.sampleSize

            // 桶内任一标签计数 >= 0 且不超过该桶样本数（单作品内标签去重的直接推论）。
            stats.highScoreTags.forEach {
                it.count shouldBeGreaterThanOrEqualTo 0
                it.count shouldBeLessThanOrEqualTo stats.highScoreSampleCount
            }
            stats.lowScoreTags.forEach {
                it.count shouldBeGreaterThanOrEqualTo 0
                it.count shouldBeLessThanOrEqualTo stats.lowScoreSampleCount
            }
            stats.droppedTypes.forEach {
                it.count shouldBeGreaterThanOrEqualTo 0
                it.count shouldBeLessThanOrEqualTo stats.droppedSampleCount
            }

            // 短评词可在单条短评内重复出现，故计数仅保证非负（不受样本总数约束）。
            stats.commonReviewWords.forEach {
                it.count shouldBeGreaterThanOrEqualTo 1
            }
        }
    }
})

package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * 候选池校准回归（大样本场景）：模拟 ~40 样本真实画像 + 200 部未评分候选池，验证
 * **combo 归一化 + 候选池校准**修复后，未评分作品出分按契合度单调拉开、区分度回归——
 * 修复前 combo 项未归一化爆炸主导 rawZ、且 μ 用训练样本（自我重合）偏高，导致「完美命中仅 45、
 * 部分契合 / 中性 / 反口味全挤在 26 同分」；修复后完美命中→95、部分契合→53、反口味→5。
 */
class TasteCalibrationDiagnosticTest : StringSpec({

    val build = BuildTasteProfileUseCase()
    val compute = ComputeTasteMatchUseCase()
    val now = 1_700_000_000_000L
    val day = 24L * 3600 * 1000

    fun feature(id: String, tags: List<Pair<String, Int>>, score: Float? = 7.5f, votes: Int = 3000) = WorkFeature(
        subjectId = id,
        tagCounts = tags.map { TagCount(it.first, it.second) },
        bangumiScore = score,
        bangumiVotes = votes,
        titleVariants = listOf(id),
    )

    fun sample(id: String, rating: Int, ageDays: Long, tags: List<Pair<String, Int>>) = TasteSample(
        subjectId = id,
        rating = rating,
        comment = null,
        updatedAtMillis = now - ageDays * day,
        feature = feature(id, tags),
    )

    val liked = listOf("奇幻", "异世界", "战斗", "冒险", "智斗", "魔法", "热血", "超能力", "机战", "龙")
    val disliked = listOf("恋爱", "日常", "校园", "治愈", "百合")

    val training = buildList {
        for (i in 0 until 30) {
            val tags = liked.shuffled(Random(i.toLong())).take(4 + i % 4)
                .mapIndexed { j, g -> g to (50 - j * 8) }
            add(sample("h$i", 8 + i % 3, (i * 10).toLong(), tags))
        }
        for (i in 0 until 10) {
            val tags = disliked.shuffled(Random(100L + i)).take(2 + i % 3)
                .mapIndexed { j, g -> g to (45 - j * 10) }
            add(sample("l$i", 2 + i % 3, (i * 15).toLong(), tags))
        }
    }

    // 模拟「未评分候选池」（work_features 缓存全体）：200 个标签多样的作品，多数与画像重合少。
    val poolTags = liked + disliked + listOf(
        "科幻", "悬疑", "推理", "音乐", "运动", "历史", "职场", "美食", "机器人", "赛博朋克",
        "末世", "吸血鬼", "精灵", "料理", "旅行", "军事", "校园喜剧", "泡面番", "群像", "复仇",
    )
    val pool = (0 until 200).map { i ->
        val tags = poolTags.shuffled(Random(1000L + i)).take(3 + i % 6)
            .mapIndexed { j, g -> g to (40 - j * 5) }
        feature("pool$i", tags, score = 5.5f + (i % 40) / 10f, votes = 500 + i * 20)
    }
    val profile = build(training, now, pool)

    "候选池校准 + combo 归一化：未评分作品按契合度单调拉开、区分度回归" {
        // 这里验证的是 12 维画像的题材相似度（主分数现在来自预计评分，另有测试覆盖）。
        fun sc(vararg t: Pair<String, Int>) = compute(feature("d", t.toList()), profile).tasteSimilarity
        val perfect = sc("奇幻" to 60, "异世界" to 50, "战斗" to 40, "智斗" to 30)
        val good = sc("奇幻" to 40, "冒险" to 30, "热血" to 20)
        val partial = sc("奇幻" to 30, "科幻" to 20)
        val weak = sc("科幻" to 30, "悬疑" to 20)
        val anti = sc("恋爱" to 60, "日常" to 50)
        // 单调：完美 ≥ 良好 ≥ 部分契合 ≥ 中性 ≥ 反口味
        (perfect >= good) shouldBe true
        (good >= partial) shouldBe true
        (partial >= weak) shouldBe true
        (weak >= anti) shouldBe true
        // 完美命中高分、部分契合（命中强 liked 单标签）脉离「十几分」回到中段、反口味低分
        (perfect >= 80) shouldBe true
        (partial >= 45) shouldBe true
        (anti <= 25) shouldBe true
        // 关键回归：命中 liked 单标签的「部分契合」明显高于中性、远高于反口味（区分度不再坡塌）
        (partial - weak >= 5) shouldBe true
        (partial - anti >= 25) shouldBe true
    }
})

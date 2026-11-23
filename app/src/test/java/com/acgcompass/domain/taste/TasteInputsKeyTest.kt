package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/**
 * 画像输入指纹：同样的输入（顺序不同也算同样）给出同一个指纹；评分、短评、作品资料更新时间、校准池、AI 标签分类、
 * 兴趣信号任何一项变了，指纹都要变。为你排行和探索队列靠它判断能不能沿用上次的结果。
 * 另外检查轻量分（排序用）和完整打分的各项完全相同。
 */
class TasteInputsKeyTest : StringSpec({
    val random = Random(11)
    val tags = listOf("日常", "校园", "悬疑", "科幻", "治愈", "运动")
    fun feature(id: String, updatedAt: Long = 100L) = WorkFeature(
        subjectId = id,
        tagCounts = tags.shuffled(random).take(3).mapIndexed { k, t -> TagCount(t, 90 - k * 20) },
        bangumiScore = 6f + random.nextInt(30) / 10f,
        bangumiVotes = 200 + random.nextInt(5000),
        updatedAt = updatedAt,
    )
    val samples = (0 until 40).map { i ->
        val f = feature("s$i")
        TasteSample("s$i", 5 + random.nextInt(5), if (i % 7 == 0) "不错" else null, 1_000L * i, f)
    }
    val pool = (0 until 30).map { feature("p$it") }
    val base = TasteBuildInputs(
        samples = samples,
        now = 5_000L,
        calibrationPool = pool,
        tagOverrides = mapOf("校园" to TasteCategory.TOPIC),
        implicitSignals = listOf(ImplicitSignal(feature("i1"), ImplicitSignal.Kind.WISH)),
        options = DivergenceOptions.DEFAULT,
    )
    val key = TasteInputsKey.of(base)

    "同样的输入给同一个指纹，和顺序、建画像的时间无关" {
        val reordered = base.copy(samples = samples.reversed(), calibrationPool = pool.shuffled(Random(2)))
        TasteInputsKey.of(reordered) shouldBe key
        TasteInputsKey.of(base.copy(now = 9_999_999L)) shouldBe key
    }

    "评分、短评、资料更新、校准池、标签分类、兴趣信号、模型选项变了，指纹都变" {
        val changedRating = samples.mapIndexed { i, s -> if (i == 3) s.copy(rating = (s.rating ?: 5) % 10 + 1) else s }
        TasteInputsKey.of(base.copy(samples = changedRating)) shouldNotBe key
        val changedComment = samples.mapIndexed { i, s -> if (i == 4) s.copy(comment = "看哭了") else s }
        TasteInputsKey.of(base.copy(samples = changedComment)) shouldNotBe key
        val refreshed = pool.mapIndexed { i, f -> if (i == 0) f.copy(updatedAt = 200L) else f }
        TasteInputsKey.of(base.copy(calibrationPool = refreshed)) shouldNotBe key
        TasteInputsKey.of(base.copy(calibrationPool = pool.drop(1))) shouldNotBe key
        TasteInputsKey.of(base.copy(tagOverrides = emptyMap())) shouldNotBe key
        TasteInputsKey.of(base.copy(implicitSignals = emptyList())) shouldNotBe key
        TasteInputsKey.of(base.copy(options = DivergenceOptions.LEGACY)) shouldNotBe key
    }

    "轻量分和完整打分的各项完全一样" {
        val profile = BuildTasteProfileUseCase()(samples, 5_000L, pool)
        val compute = ComputeTasteMatchUseCase()
        for (f in (0 until 20).map { feature("c$it") } + samples.take(5).map { it.feature }) {
            val full = compute(f, profile)
            val light = compute(f, profile, tagOverrides = emptyMap(), withNeighbors = false, withReasons = false)
            LightScore.of(light) shouldBe LightScore.of(full)
        }
    }
})

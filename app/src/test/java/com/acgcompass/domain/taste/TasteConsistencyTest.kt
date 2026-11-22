package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/**
 * 评测走的路径和 App 展示走的路径必须给出同一个数：
 * - 离线评测（`TasteEvalHarnessTest`）、算法自检、预测账本都用「不算相似作品、不生成理由」的快速打分，
 *   详情页用完整打分；两者的预计评分、区间、匹配度、个人分位必须一致；
 * - 兴趣信号（收藏状态、探索滑动）只进十二维画像，不改变预计评分（口味匹配度里的题材画像分会受它影响）；
 * - 画像里的对照模型（预测账本用）和单独用旧配置建的模型给出同样的预测；
 * - 口味匹配度的三个分支给出不同的分，口碑权重越高越贴近社区分；已评分作品和未评分作品用同一个公式，
 *   详情页显示的回测值来自 [TasteBacktest]（建画像时不知道这部作品），不是评分 ×10。
 */
class TasteConsistencyTest : StringSpec({
    val tags = listOf("日常", "校园", "悬疑", "后宫", "科幻", "运动", "奇幻", "治愈")
    val random = Random(5)
    fun feature(id: String): WorkFeature {
        val chosen = tags.shuffled(random).take(3)
        return WorkFeature(
            subjectId = id,
            tagCounts = chosen.mapIndexed { k, t -> TagCount(t, 80 - k * 15) },
            bangumiScore = 5.5f + random.nextInt(35) / 10f,
            bangumiVotes = listOf(30, 400, 3000)[random.nextInt(3)],
        )
    }
    val samples = (0 until 70).map { i ->
        val f = feature("s$i")
        val bias = if (f.tagCounts.any { it.name == "悬疑" }) 1.5 else 0.0
        TasteSample(
            "s$i",
            ((f.bangumiScore ?: 7f) + bias + random.nextInt(3) - 1).toInt().coerceIn(1, 10),
            null,
            1_000L * i,
            f,
        )
    }
    val candidates = (0 until 25).map { feature("c$it") }
    val pool = (0 until 60).map { feature("p$it") }
    val build = BuildTasteProfileUseCase()
    val compute = ComputeTasteMatchUseCase()
    val gated = DivergenceOptions(perUserGate = true)

    "fast scoring (evaluation, self-check, ledger) equals full scoring (detail page)" {
        for (options in listOf(DivergenceOptions.LEGACY, gated, DivergenceOptions.DEFAULT)) {
            val profile = build(samples, 100_000L, pool, divergenceOptions = options)
            for (c in candidates) {
                val full = compute(c, profile)
                val fast = compute(c, profile, withNeighbors = false, withReasons = false)
                fast.prediction shouldBe full.prediction
                fast.score shouldBe full.score
                fast.satisfaction shouldBe full.satisfaction
            }
        }
    }

    "interest signals never change the predicted rating" {
        val signals = candidates.take(10).mapIndexed { i, f ->
            ImplicitSignal(f, if (i % 2 == 0) ImplicitSignal.Kind.DROPPED else ImplicitSignal.Kind.EXPLORE_LIKED)
        }
        val plain = build(samples, 100_000L, pool, divergenceOptions = gated)
        val withInterest = build(samples, 100_000L, pool, implicitSignals = signals, divergenceOptions = gated)
        for (c in candidates.drop(10)) {
            val a = compute(c, plain, withNeighbors = false, withReasons = false)
            val b = compute(c, withInterest, withNeighbors = false, withReasons = false)
            b.prediction shouldBe a.prediction
        }
    }

    "the shadow model inside a profile predicts exactly like a profile built with that configuration" {
        val main = build(samples, 100_000L, pool, divergenceOptions = gated, shadowOptions = DivergenceOptions.LEGACY)
        val legacy = build(samples, 100_000L, pool, divergenceOptions = DivergenceOptions.LEGACY)
        val shadow = main.copy(divergence = main.shadowDivergence)
        main.shadowDivergence!!.modelVersion shouldBe TasteModelVersion.of(DivergenceOptions.LEGACY)
        main.divergence!!.modelVersion shouldBe TasteModelVersion.of(gated)
        for (c in candidates) {
            compute(c, shadow, withNeighbors = false, withReasons = false).prediction shouldBe
                compute(c, legacy, withNeighbors = false, withReasons = false).prediction
        }
    }

    "match: the three branches give different scores, and aggressive spreads them the widest" {
        val base = build(samples, 100_000L, pool, divergenceOptions = gated)
        base.tasteFit shouldNotBe null
        fun scores(branch: TasteMatchBranch): List<Int> {
            val profile = base.copy(
                matchBranch = branch,
                headlineFormula = TasteAlgorithmConfig.DEFAULT.formula(branch),
            )
            return candidates.map { compute(it, profile, withNeighbors = false, withReasons = false).score }
        }
        val standard = scores(TasteMatchBranch.STANDARD)
        val conservative = scores(TasteMatchBranch.CONSERVATIVE)
        val aggressive = scores(TasteMatchBranch.AGGRESSIVE)
        // 换分支必须真的换分数：大多数作品的分数不同。
        (standard.indices.count { standard[it] != conservative[it] } >= candidates.size / 2) shouldBe true
        (standard.indices.count { standard[it] != aggressive[it] } >= candidates.size / 2) shouldBe true
        // 激进「敢给」：两端放得最开，分数的离散程度大于保守（保守往中间收）。
        fun spread(s: List<Int>): Double {
            val m = s.average()
            return kotlin.math.sqrt(s.sumOf { (it - m) * (it - m) } / s.size)
        }
        (spread(aggressive) > spread(conservative)) shouldBe true
    }

    "match puts a typical library work near the middle of the scale" {
        val profile = build(samples, 100_000L, pool, divergenceOptions = gated)
        val scores = pool.map { compute(it, profile, withNeighbors = false, withReasons = false).score }
        val median = scores.sorted()[pool.size / 2]
        (median in 40..65) shouldBe true
    }

    "a rated work is scored by the match formula, and the backtest scores every rated work without it" {
        val profile = build(samples, 100_000L, pool, divergenceOptions = gated)
        val rated = samples.first()
        val r = compute(rated.feature, profile, rated.rating, withNeighbors = false, withReasons = false)
        r.score shouldBe TasteFit.score(TasteFit.components(r)!!, profile.tasteFit!!, profile.matchBranch)
        val backtest = TasteBacktest.run(
            TasteBuildInputs(samples, 100_000L, pool, emptyMap(), emptyList(), gated),
        )
        backtest.entries.size shouldBe samples.size
        backtest.entries.values.all { e ->
            TasteMatchBranch.entries.all {
                e.match(it) != null
            } && e.predicted != null
        } shouldBe true
        backtest.coupling(TasteMatchBranch.STANDARD) shouldNotBe null
    }

    "one more rating barely moves the other works' backtest (folds keyed by work id)" {
        val extra = TasteSample("extra", 9, null, 999_000L, feature("extra"))
        fun shift(folds: (List<TasteSample>) -> List<TasteSelfCheck.Fold>, options: DivergenceOptions): Double {
            val before = TasteBacktest.run(
                TasteBuildInputs(samples, 100_000L, pool, emptyMap(), emptyList(), options),
                folds = folds,
            )
            val after = TasteBacktest.run(
                TasteBuildInputs(samples + extra, 100_000L, pool, emptyMap(), emptyList(), options),
                folds = folds,
            )
            return samples.map { s ->
                val a = before.entries.getValue(s.subjectId).match(TasteMatchBranch.STANDARD)!!
                val b = after.entries.getValue(s.subjectId).match(TasteMatchBranch.STANDARD)!!
                kotlin.math.abs(a - b).toDouble()
            }.average()
        }
        val shuffled: (List<TasteSample>) -> List<TasteSelfCheck.Fold> = { rated ->
            val order = rated.shuffled(Random(1))
            (0 until 5).map { f ->
                TasteSelfCheck.Fold(
                    order.filterIndexed { i, _ -> i % 5 != f },
                    order.filterIndexed { i, _ -> i % 5 == f },
                )
            }
        }
        val stable = shift({ TasteSelfCheck.folds(it) }, DivergenceOptions.DEFAULT)
        val reshuffled = shift(shuffled, DivergenceOptions.DEFAULT.copy(stableFolds = false))
        println("backtest shift after one more rating: stable $stable, reshuffled $reshuffled")
        (stable < reshuffled) shouldBe true
    }

    "folds keyed by work id: a new rating never moves another work to a different fold" {
        fun foldOf(rated: List<TasteSample>) =
            TasteSelfCheck.folds(rated).flatMapIndexed { k, f -> f.test.map { it.subjectId to k } }.toMap()
        val before = foldOf(samples)
        val after = foldOf(samples + TasteSample("extra", 9, null, 999_000L, feature("extra")))
        samples.all { before[it.subjectId] == after[it.subjectId] } shouldBe true
    }

    "the chance of a 9 or 10 rises with the predicted rating and stays a probability" {
        val profile = build(samples, 100_000L, pool, divergenceOptions = gated)
        val predictions = candidates.mapNotNull {
            compute(
                it,
                profile,
                withNeighbors = false,
                withReasons = false,
            ).prediction
        }
            .sortedBy { it.rating }
        predictions.all { (it.highChance ?: -1.0) in 0.0..1.0 } shouldBe true
        (predictions.last().highChance!! > predictions.first().highChance!!) shouldBe true
    }

    "the legacy model keeps the old match formula" {
        val profile = build(samples, 100_000L, pool)
        profile.tasteFit shouldBe null
    }
})

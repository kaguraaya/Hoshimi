package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 合成用户留一盲测（LOO）：在没有真实数据集的环境（CI / 他机）也能对算法改动做方向性回归。
 *
 * 生成三类用户——**共识型**（评分主要跟着作品品质走）、**口味型**（主要跟着题材偏好走）、
 * **逆主流型**（题材偏好 + 与社区口碑反向）——各若干个随机种子，对每部已评分作品做留一预测，
 * 统计「预测分 vs 真实评分」的 Spearman 排序相关，并写报告到 `build/loo/synthetic.txt`。
 *
 * 注意：合成数据的评分模型是人为假设，**只用于防止明显退化**，不能替代真实数据盲测
 * （[TasteLooValidationTest]）。断言只卡很宽的下限。
 */
class TasteSyntheticLooTest : StringSpec({

    val genres = (1..30).map { "题材$it" }

    data class Persona(val name: String, val tasteWeight: Double, val qualityWeight: Double)

    val personas = listOf(
        Persona("共识型", tasteWeight = 0.5, qualityWeight = 1.0),
        Persona("口味型", tasteWeight = 1.2, qualityWeight = 0.3),
        Persona("逆主流型", tasteWeight = 1.0, qualityWeight = -0.5),
    )

    fun gaussian(r: Random): Double {
        // Box–Muller
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    fun spearman(a: List<Double>, b: List<Double>): Double {
        fun ranks(v: List<Double>): DoubleArray {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
                for (k in i..j) r[idx[k]] = (i + j) / 2.0 + 1.0
                i = j + 1
            }
            return r
        }
        val ra = ranks(a)
        val rb = ranks(b)
        val ma = ra.average()
        val mb = rb.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in ra.indices) {
            cov += (ra[i] - ma) * (rb[i] - mb)
            va += (ra[i] - ma) * (ra[i] - ma)
            vb += (rb[i] - mb) * (rb[i] - mb)
        }
        return if (va > 0 && vb > 0) cov / sqrt(va * vb) else 0.0
    }

    fun looSpearman(persona: Persona, seed: Int, rated: Int = 60, poolSize: Int = 60): Double {
        val r = Random(seed)
        val affinity = genres.associateWith { gaussian(r) }
        data class Item(val feature: WorkFeature, val rating: Int)

        fun work(id: String): Pair<WorkFeature, Double> {
            val tags = genres.shuffled(r).take(3 + r.nextInt(4))
            val quality = gaussian(r)
            val community = (6.8 + 0.7 * quality + 0.25 * gaussian(r)).coerceIn(3.0, 9.5)
            val feature = WorkFeature(
                subjectId = id,
                tagCounts = tags.mapIndexed { j, g -> TagCount(g, 60 - j * 8 + r.nextInt(6)) },
                bangumiScore = community.toFloat(),
                bangumiVotes = 500 + r.nextInt(19500),
                titleVariants = listOf(id),
            )
            val taste = tags.map { affinity.getValue(it) }.average() * 1.6
            return feature to (persona.tasteWeight * taste + persona.qualityWeight * quality + 0.4 * gaussian(r))
        }

        val items = (0 until rated).map { i ->
            val (f, utility) = work("r$i")
            Item(f, (7.0 + 1.3 * utility).roundToInt().coerceIn(1, 10))
        }
        val pool = (0 until poolSize).map { work("p$it").first }
        val build = BuildTasteProfileUseCase()
        val compute = ComputeTasteMatchUseCase()
        val now = 1_700_000_000_000L
        val preds = items.map { target ->
            val training = items.filter { it !== target }
                .map { TasteSample(it.feature.subjectId, it.rating, null, null, it.feature) }
            compute(target.feature, build(training, now, pool)).score.toDouble()
        }
        return spearman(preds, items.map { it.rating.toDouble() })
    }

    "合成用户 LOO：三类用户的排序相关都为正，共识型与口味型不低于宽松下限" {
        val seeds = 1..6
        val report = StringBuilder("=== 合成用户 LOO（算法 v${TasteScoringParams.ALGORITHM_VERSION}）===\n")
        val means = personas.associate { p ->
            val values = seeds.map { looSpearman(p, it) }
            report.appendLine(
                "${p.name}  mean=${"%.3f".format(values.average())}  " +
                    values.joinToString(" ") { "%.2f".format(it) },
            )
            p.name to values.average()
        }
        File("build/loo").mkdirs()
        File("build/loo/synthetic.txt").writeText(report.toString())
        println(report)

        (means.getValue("共识型") > 0.35) shouldBe true
        (means.getValue("口味型") > 0.30) shouldBe true
        (means.getValue("逆主流型") > 0.0) shouldBe true
    }
})

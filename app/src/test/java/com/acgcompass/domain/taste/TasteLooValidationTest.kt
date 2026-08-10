package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 真实数据留一交叉验证（LOO 盲测）。
 *
 * 读取本地 gitignored 数据集 `build/loo/dataset.json`（用户 Bangumi 全量动画评分 + 社区 tags，**不进仓库**），
 * 对每部已评分作品做留一验证：
 *  - 训练集 = 其余全部已评分作品（该作品不在训练集 → `ratedSubjectScores` 不含它 → 不走已评分锚定，无真实分泄漏）；
 *  - 校准池 = 全部作品（排除自身）的 [WorkFeature]（≥ CALIB_MIN_POOL 走 候选池校准）；
 *  - 把该作品当「未评分候选」打分，比对 **预测分 vs 真实评分**。
 *
 * 输出 Spearman 排序相关 + 分档单调性 + 逐部明细到 `build/loo/report.txt`（在断言前写盘，便于无论断言结果都可查看）。
 * 数据文件不存在时自动跳过（CI / 他机无隐私数据）。
 */
class TasteLooValidationTest : StringSpec({

    val dataFile = File("build/loo/dataset.json")

    "LOO 真实数据盲测：预测分 vs 真实评分排序一致性".config(enabled = dataFile.exists()) {
        val root = Json.parseToJsonElement(dataFile.readText().removePrefix("\uFEFF")).jsonArray

        data class Item(val id: String, val name: String, val rate: Int, val feature: WorkFeature)

        fun tagsOf(o: JsonObject): List<TagCount> {
            val el = o["tags"]
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> JsonArray(listOf(el)) // PS5.1 单元素数组会退化为对象，兼容之
                else -> return emptyList()
            }
            return arr.mapNotNull { t ->
                val to = t.jsonObject
                val name = to["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val count = to["count"]?.jsonPrimitive?.intOrNull ?: 0
                TagCount(name, count)
            }
        }

        val items = root.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val rate = o["rate"]?.jsonPrimitive?.intOrNull ?: 0
            if (rate <= 0) return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
            Item(
                id = id,
                name = name,
                rate = rate,
                feature = WorkFeature(
                    subjectId = id,
                    tagCounts = tagsOf(o),
                    bangumiScore = o["score"]?.jsonPrimitive?.floatOrNull,
                    bangumiVotes = o["ctotal"]?.jsonPrimitive?.intOrNull ?: 0,
                    titleVariants = listOf(name, id),
                ),
            )
        }

        val build = BuildTasteProfileUseCase()
        val compute = ComputeTasteMatchUseCase()
        val now = 1_700_000_000_000L
        val allFeatures = items.map { it.feature }

        // 留一：对每部作品，用其余作品重建画像并把它当未评分候选打分。
        val results = items.map { target ->
            val training = items.filter { it.id != target.id }
                .map { TasteSample(it.id, it.rate, null, null, it.feature) }
            val pool = allFeatures.filter { it.subjectId != target.id }
            val profile = build(training, now, pool)
            val pred = compute(target.feature, profile).score.toDouble()
            Triple(target, pred, profile.calibration)
        }

        // Spearman 排序相关（含并列平均秩）。
        fun ranks(v: List<Double>): List<Double> {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
                val avg = (i + j) / 2.0 + 1.0
                for (k in i..j) r[idx[k]] = avg
                i = j + 1
            }
            return r.toList()
        }

        val preds = results.map { it.second }
        val rates = results.map { it.first.rate.toDouble() }
        val rp = ranks(preds)
        val rr = ranks(rates)
        val n = results.size
        val mp = rp.average()
        val mr = rr.average()
        var cov = 0.0
        var vp = 0.0
        var vr = 0.0
        for (i in 0 until n) {
            val dp = rp[i] - mp
            val dr = rr[i] - mr
            cov += dp * dr
            vp += dp * dp
            vr += dr * dr
        }
        val spearman = if (vp > 0 && vr > 0) cov / sqrt(vp * vr) else 0.0

        fun band(r: Int) = when {
            r <= 5 -> "低档(<=5)"
            r <= 7 -> "中档(6-7)"
            else -> "高档(8-10)"
        }
        val byBand = results.groupBy { band(it.first.rate) }
        val bandOrder = listOf("低档(<=5)", "中档(6-7)", "高档(8-10)")

        val sb = StringBuilder()
        sb.appendLine("=== LOO 真实数据盲测报告 ===")
        sb.appendLine("样本数(rated>0): $n")
        sb.appendLine("Spearman 排序相关(预测分 vs 真实评分): ${"%.3f".format(spearman)}")
        sb.appendLine()
        sb.appendLine("--- 按真实评分分档的 LOO 预测分 ---")
        for (b in bandOrder) {
            val g = byBand[b] ?: continue
            val ps = g.map { it.second }
            sb.appendLine(
                "$b  n=${g.size}  pred mean=${"%.1f".format(ps.average())}" +
                    "  min=${"%.0f".format(ps.min())}  max=${"%.0f".format(ps.max())}",
            )
        }
        sb.appendLine()
        val cal = results.firstOrNull()?.third
        if (cal != null) sb.appendLine("校准示例 μ=${"%.4f".format(cal.mu)}  τ=${"%.4f".format(cal.tau)}")
        sb.appendLine()
        sb.appendLine("--- 逐部明细（按真实评分降序、同分按预测分降序）---")
        for (r in results.sortedWith(
            compareByDescending<Triple<Item, Double, TasteCalibration>> {
                it.first.rate
            }.thenByDescending { it.second },
        )) {
            sb.appendLine("rate=${r.first.rate}  pred=${"%.0f".format(r.second)}  ${r.first.name}")
        }

        File("build/loo/report.txt").writeText(sb.toString())

        // 主要产出是报告；此处仅保证测试确有执行。
        (n > 0) shouldBe true
    }
})

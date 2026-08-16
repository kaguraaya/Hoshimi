package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Bangumi15M 真实用户盲测（5 折交叉验证）。
 *
 * 读取本地 `build/loo/bangumi15m-eval.json`（由公开数据集 Bangumi15M 抽样的匿名用户评分 + 条目标签，
 * **不进仓库**；抽样脚本见 `docs/algorithm-eval.md`）。对每位用户把已评分作品分成 5 折，轮流用其余 4 折
 * 建画像、给留出的 1 折当「未评分候选」打分，保证被预测作品不在训练集内（无真实分泄漏）。
 *
 * 输出逐条预测分量到 `build/loo/b15m-components.tsv`（匹配度 / 画像分 / 预计满意度 / 社区分 / 票数 / 证据量），
 * 供离线脚本统计 Spearman、命中率并对融合参数做网格对比。数据文件不存在时自动跳过。
 */
class TasteBangumi15mEvalTest : StringSpec({

    val dataFile = File("build/loo/bangumi15m-eval.json")

    "Bangumi15M 5 折盲测：导出逐条预测分量".config(enabled = dataFile.exists()) {
        val root = Json.parseToJsonElement(dataFile.readText()).jsonObject
        val features = root.getValue("subjects").jsonObject.mapValues { (id, el) ->
            val o = el.jsonObject
            WorkFeature(
                subjectId = id,
                tagCounts = o.getValue("tags").jsonArray.map {
                    TagCount(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.int)
                },
                bangumiScore = o.getValue("score").jsonPrimitive.double.toFloat().takeIf { it > 0f },
                bangumiVotes = o.getValue("votes").jsonPrimitive.int,
                eps = o.getValue("eps").jsonPrimitive.int,
                titleVariants = listOf(
                    o.getValue("name").jsonPrimitive.content,
                    o.getValue("name_ja").jsonPrimitive.content,
                ).filter { it.isNotBlank() },
            )
        }
        val pool = root.getValue("pool").jsonArray.map { it.jsonPrimitive.content }
        val maxUsers = System.getProperty("b15m.users")?.toIntOrNull() ?: Int.MAX_VALUE
        val build = BuildTasteProfileUseCase()
        val compute = ComputeTasteMatchUseCase()

        val out = File("build/loo/b15m-components.tsv").bufferedWriter()
        out.appendLine(
            "user\tn\tsubject\trating\tscore\tprofile\tsatisfaction\tcommunity\tvotes\tevidence\tfamiliarity\tz",
        )
        var rows = 0
        for ((userIndex, userEl) in root.getValue("users").jsonArray.take(maxUsers).withIndex()) {
            val items = userEl.jsonObject.getValue("items").jsonArray.mapNotNull { item ->
                val arr = item.jsonArray
                val id = arr[0].jsonPrimitive.content
                val feature = features[id] ?: return@mapNotNull null
                TasteSample(id, arr[1].jsonPrimitive.int, null, arr[2].jsonPrimitive.long, feature)
            }.shuffled(Random(userIndex))
            val ratedIds = items.mapTo(HashSet()) { it.subjectId }
            val userPool = pool.asSequence().filter { it !in ratedIds }.take(POOL_SIZE)
                .mapNotNull { features[it] }.toList()
            val now = items.maxOf { it.updatedAtMillis ?: 0L }
            for (fold in 0 until FOLDS) {
                val test = items.filterIndexed { i, _ -> i % FOLDS == fold }
                val train = items.filterIndexed { i, _ -> i % FOLDS != fold }
                val profile = build(train, now, userPool)
                for (t in test) {
                    val r = compute(t.feature, profile)
                    out.appendLine(
                        listOf(
                            userIndex, items.size, t.subjectId, t.rating, r.score, r.profileScore, r.satisfaction,
                            t.feature.bangumiScore ?: 0f, t.feature.bangumiVotes, r.evidence, r.familiarity,
                            (r.rawZ - profile.calibration.mu) / profile.calibration.tau,
                        ).joinToString("\t"),
                    )
                    rows++
                }
            }
        }
        out.close()
        (rows > 0) shouldBe true
    }
}) {
    private companion object {
        const val FOLDS = 5
        const val POOL_SIZE = 200
    }
}

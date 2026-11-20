package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.random.Random

/**
 * 口味算法自检：用你自己的评分做 K 折盲测（每次把一折当成「没看过的作品」去预测，模型里没有它们的真实分），
 * 同时单独统计输入数据本身的质量。推荐不准时先看数据质量：资料缺得多、社区评分人数太少，算法再好也会偏。
 *
 * 纯计算、无 IO；输入与口味引擎重建画像时用的完全一样。
 */
object TasteSelfCheck {

    /** 预测质量（各指标都在被留出、没参与建模的作品上计算）。 */
    data class Accuracy(
        /** 参与预测的作品数（需要能建出评分模型）。 */
        val predicted: Int,
        /** 预计评分与实际评分的平均绝对误差（分）。 */
        val meanAbsError: Double,
        /** 预计评分与实际相差 1 分以内的比例。 */
        val withinOne: Double,
        /** 预计评分与实际评分的排序一致性（Spearman，−1~1）。 */
        val spearman: Double,
        /** 只看社区评分时的排序一致性：算法应当比它高。 */
        val communitySpearman: Double,
        /** 匹配度最高的 10 部里，你实际打了高分（≥ 你的 80 分位且 ≥ 8）的比例。 */
        val top10Hit: Double,
        /** 评分人数少于 [LOW_VOTES] 的作品里，预计评分比你的均分高 1 分以上、实际却没到均分的比例（被高估的冷门作）。 */
        val lowVoteOverrated: Double,
    )

    /** 数据质量：和算法无关，只描述输入本身。 */
    data class DataQuality(
        /** 已评分作品数（有资料的）。 */
        val rated: Int,
        /** 有社区标签的比例。 */
        val tagCoverage: Double,
        /** 有社区评分的比例。 */
        val communityCoverage: Double,
        /** 社区评分人数少于 [LOW_VOTES] 的比例。 */
        val lowVoteShare: Double,
        /** 校准池（本地未评分作品）规模。 */
        val poolSize: Int,
        /** 按媒介类型的已评分作品数。 */
        val byMedia: Map<String, Int>,
    )

    data class Report(val accuracy: Accuracy?, val quality: DataQuality)

    /** 评分人数低于这个数，社区评分就不太可信。 */
    const val LOW_VOTES: Int = 50
    private const val DEFAULT_FOLDS = 5
    private const val TOP_N = 10
    private const val HIGH_RATING_FLOOR = 8.0
    private const val HIGH_QUANTILE = 0.8
    private const val OVERRATE_MARGIN = 1.0
    private const val MIN_RATED_FOR_EVAL = PersonalDivergenceModel.MIN_SAMPLES + 2

    @Suppress("LongParameterList") // 与画像重建同一组输入，逐个传入
    fun run(
        samples: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        communityVotePrior: Double = PersonalDivergenceModel.COMMUNITY_VOTE_PRIOR,
        folds: Int = DEFAULT_FOLDS,
        seed: Int = 1,
    ): Report {
        val rated = samples.filter { it.rating != null }
        return Report(accuracy(rated, pool, now, tagOverrides, communityVotePrior, folds, seed), quality(rated, pool))
    }

    fun quality(rated: List<TasteSample>, pool: List<WorkFeature>): DataQuality {
        val n = rated.size.coerceAtLeast(1).toDouble()
        val withCommunity = rated.filter { (it.feature.bangumiScore ?: 0f) > 0f }
        return DataQuality(
            rated = rated.size,
            tagCoverage = rated.count { it.feature.tagCounts.isNotEmpty() } / n,
            communityCoverage = withCommunity.size / n,
            lowVoteShare = withCommunity.count { it.feature.bangumiVotes in 1 until LOW_VOTES } / n,
            poolSize = pool.size,
            byMedia = rated.groupingBy { it.feature.mediaType?.name ?: "未知" }.eachCount(),
        )
    }

    private class Row(val rating: Double, val predicted: Double, val score: Int, val community: Double?, val votes: Int)

    @Suppress("LongParameterList", "ReturnCount")
    private fun accuracy(
        rated: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        tagOverrides: Map<String, TasteCategory>,
        communityVotePrior: Double,
        folds: Int,
        seed: Int,
    ): Accuracy? {
        if (rated.size < MIN_RATED_FOR_EVAL) return null
        val shuffled = rated.shuffled(Random(seed))
        val ratedIds = rated.mapTo(HashSet()) { it.subjectId }
        val calibration = pool.filter { it.subjectId !in ratedIds }
        val build = BuildTasteProfileUseCase()
        val compute = ComputeTasteMatchUseCase()
        val rows = ArrayList<Row>()
        for (fold in 0 until folds) {
            val test = shuffled.filterIndexed { i, _ -> i % folds == fold }
            val train = shuffled.filterIndexed { i, _ -> i % folds != fold }
            val profile = build(train, now, calibration, tagOverrides, communityVotePrior = communityVotePrior)
            for (t in test) {
                val result =
                    compute(t.feature, profile, tagOverrides = tagOverrides, withNeighbors = false, withReasons = false)
                val predicted = result.prediction?.rating ?: continue
                rows += Row(
                    rating = t.rating!!.toDouble(),
                    predicted = predicted,
                    score = result.score,
                    community = t.feature.bangumiScore?.toDouble()?.takeIf { it > 0.0 },
                    votes = t.feature.bangumiVotes,
                )
            }
        }
        if (rows.size < 2) return null
        val ratings = rows.map { it.rating }
        val mean = ratings.average()
        val highThreshold = maxOf(HIGH_RATING_FLOOR, quantile(ratings, HIGH_QUANTILE))
        val top = rows.sortedByDescending { it.score }.take(TOP_N)
        val lowVote = rows.filter { it.votes in 1 until LOW_VOTES }
        val withCommunity = rows.filter { it.community != null }
        return Accuracy(
            predicted = rows.size,
            meanAbsError = rows.map { abs(it.predicted - it.rating) }.average(),
            withinOne = rows.count { abs(it.predicted - it.rating) <= 1.0 }.toDouble() / rows.size,
            spearman = spearman(rows.map { it.predicted }, ratings),
            communitySpearman = if (withCommunity.size >= 2) {
                spearman(withCommunity.map { it.community!! }, withCommunity.map { it.rating })
            } else {
                0.0
            },
            top10Hit = top.count { it.rating >= highThreshold }.toDouble() / top.size,
            lowVoteOverrated = if (lowVote.isEmpty()) {
                0.0
            } else {
                lowVote.count { it.predicted >= mean + OVERRATE_MARGIN && it.rating < mean }.toDouble() / lowVote.size
            },
        )
    }

    private fun quantile(values: List<Double>, q: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
    }

    /** Spearman 秩相关；并列值取平均秩。 */
    internal fun spearman(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2) return 0.0
        val rx = ranks(x)
        val ry = ranks(y)
        val mx = rx.average()
        val my = ry.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in rx.indices) {
            num += (rx[i] - mx) * (ry[i] - my)
            dx += (rx[i] - mx) * (rx[i] - mx)
            dy += (ry[i] - my) * (ry[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / kotlin.math.sqrt(dx * dy)
    }

    private fun ranks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedBy { values[it] }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val avg = (i + j) / 2.0
            for (k in i..j) out[order[k]] = avg
            i = j + 1
        }
        return out
    }
}

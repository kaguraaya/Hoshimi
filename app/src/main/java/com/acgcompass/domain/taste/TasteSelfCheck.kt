package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 口味算法自检：用你自己的评分做 K 折盲测（每次把一折当成「没看过的作品」去预测，模型里没有它们的真实分），
 * 同时单独统计输入数据本身的质量。推荐不准时先看数据质量：资料缺得多、社区评分人数太少，算法再好也会偏。
 *
 * - 排序一致性在**每一折内部**计算再取平均：不同折的模型基线不同，把各折的预测混在一起排序会低估排序能力；
 * - 两个模型配置用同一组折比较（[compare]），差值附按作品重抽样的 95% 区间；
 * - 某项没有可算的样本时给 `null`（界面显示「无样本」），不当成 0。
 *
 * 纯计算、无 IO；输入与口味引擎重建画像时用的完全一样。
 */
@Suppress("TooManyFunctions") // 统计小工具（秩、相关、重抽样、分位数）只给自检用，放在一起
object TasteSelfCheck {

    /** 预测质量（各指标都在被留出、没参与建模的作品上计算）。 */
    data class Accuracy(
        /** 参与预测的作品数（需要能建出评分模型）。 */
        val predicted: Int,
        /** 预计评分与实际评分的平均绝对误差（分）。 */
        val meanAbsError: Double,
        /** 预计评分与实际相差 1 分以内的比例。 */
        val withinOne: Double,
        /** 预计评分与实际评分的排序一致性（Spearman，−1~1，折内计算再平均）。 */
        val spearman: Double,
        /** 只看社区评分时的排序一致性（同样折内平均）：算法应当比它高。 */
        val communitySpearman: Double,
        /** 匹配度最高的 10 部里，你实际打了高分（≥ 你的 80 分位且 ≥ 8）的比例。 */
        val top10Hit: Double,
        /** 评分人数少于 [LOW_VOTES] 的作品里，预计评分比你的均分高 1 分以上、实际却没到均分的比例；没有这类作品时为 `null`。 */
        val lowVoteOverrated: Double?,
        /** 实际评分落在预测区间里的比例；模型不给区间时为 `null`。 */
        val intervalCoverage: Double? = null,
        /** 预测区间平均宽度（分）。 */
        val intervalWidth: Double? = null,
        /** 匹配度与实际评分的排序一致性（折内平均）。 */
        val matchSpearman: Double = spearman,
        /** 匹配度和社区分的排序一致性（折内平均）：越高说明匹配度越像社区榜，个性化越少。 */
        val matchCommunityOverlap: Double? = null,
        /** 匹配度能否把「你比大众更喜欢」的作品（评分 − 社区分比你的平均差高 1 分以上）排在前面（AUC，0.5 = 随机）。 */
        val matchLikedMoreAuc: Double? = null,
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
        /** 你和社区的差别（「异端指数」）；有社区评分的作品不到 [MIN_HERESY_SAMPLES] 部时为 `null`。 */
        val heresy: Heresy? = null,
    )

    /**
     * 你的评分和社区评分差多少：
     * - [meanDiff]：你的平均分 − 这些作品的社区平均分（正数 = 你打分偏松）；
     * - [meanAbsDiff]：逐部 |你的评分 − 社区评分| 的平均；
     * - [correlation]：两者的相关（越低说明越不随大流，社区分对你的参考价值越小）。
     */
    data class Heresy(val count: Int, val meanDiff: Double, val meanAbsDiff: Double, val correlation: Double)

    data class Report(val accuracy: Accuracy?, val quality: DataQuality)

    /** 两个模型配置在同一组折上的配对比较（other − base）。 */
    data class Comparison(
        val base: Accuracy?,
        val other: Accuracy?,
        /** 两边都给出预测的作品数。 */
        val paired: Int,
        val maeDiff: Interval?,
        val spearmanDiff: Interval?,
    )

    /** 点估计和 95% 区间。 */
    data class Interval(val estimate: Double, val low: Double, val high: Double)

    /** 一折：训练集和留出集。 */
    data class Fold(val train: List<TasteSample>, val test: List<TasteSample>)

    /** 评分人数低于这个数，社区评分就不太可信。 */
    const val LOW_VOTES: Int = 50
    const val MIN_HERESY_SAMPLES: Int = 5
    private const val DEFAULT_FOLDS = 5
    private const val TOP_N = 10
    private const val HIGH_RATING_FLOOR = 8.0
    private const val HIGH_QUANTILE = 0.8
    private const val OVERRATE_MARGIN = 1.0
    private const val MIN_RATED_FOR_EVAL = PersonalDivergenceModel.MIN_SAMPLES + 2
    private const val BOOTSTRAP_ROUNDS = 400
    private const val CI_LOW = 0.025
    private const val CI_HIGH = 0.975
    private const val MIN_FOLD_RANK = 3
    private const val SELF_CHECK_POOL = 400
    private const val LIKED_MORE_MARGIN = 1.0

    @Suppress("LongParameterList") // 与画像重建同一组输入，逐个传入
    fun run(
        samples: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        communityVotePrior: Double = PersonalDivergenceModel.COMMUNITY_VOTE_PRIOR,
        folds: Int = DEFAULT_FOLDS,
        seed: Int = 1,
        options: DivergenceOptions = DivergenceOptions.LEGACY,
    ): Report {
        val rated = samples.filter { it.rating != null }
        val rows = predictRows(rated, pool, now, tagOverrides, communityVotePrior, folds, seed, options)
        return Report(rows?.let(::accuracy), quality(rated, pool))
    }

    /** 同一组折上比较两个模型配置。 */
    @Suppress("LongParameterList")
    fun compare(
        samples: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        base: DivergenceOptions,
        other: DivergenceOptions,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        communityVotePrior: Double = PersonalDivergenceModel.COMMUNITY_VOTE_PRIOR,
        folds: Int = DEFAULT_FOLDS,
        seed: Int = 1,
    ): Comparison {
        val rated = samples.filter { it.rating != null }
        val a = predictRows(rated, pool, now, tagOverrides, communityVotePrior, folds, seed, base)
        val b = predictRows(rated, pool, now, tagOverrides, communityVotePrior, folds, seed, other)
        if (a == null || b == null) return Comparison(a?.let(::accuracy), b?.let(::accuracy), 0, null, null)
        val bById = b.associateBy { it.subjectId }
        val pairs = a.mapNotNull { r -> bById[r.subjectId]?.let { r to it } }
        val random = Random(seed)
        return Comparison(
            base = accuracy(a),
            other = accuracy(b),
            paired = pairs.size,
            maeDiff = bootstrap(pairs, random) { sample ->
                sample.map { (x, y) -> abs(y.predicted - y.rating) - abs(x.predicted - x.rating) }.average()
            },
            spearmanDiff = bootstrap(pairs, random) { sample ->
                val otherRank = foldSpearman(sample.map { it.second }) { it.predicted }
                otherRank - foldSpearman(sample.map { it.first }) { it.predicted }
            },
        )
    }

    /** 自检用的折：先按 [seed] 打乱，再按下标轮流分到 [folds] 折。别的对照（例如旧算法）也用这一组折。 */
    fun folds(rated: List<TasteSample>, folds: Int = DEFAULT_FOLDS, seed: Int = 1): List<Fold> {
        val shuffled = rated.shuffled(Random(seed))
        return (0 until folds).map { fold ->
            Fold(
                train = shuffled.filterIndexed { i, _ -> i % folds != fold },
                test = shuffled.filterIndexed { i, _ -> i % folds == fold },
            )
        }
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
            heresy = heresy(withCommunity),
        )
    }

    private fun heresy(withCommunity: List<TasteSample>): Heresy? {
        if (withCommunity.size < MIN_HERESY_SAMPLES) return null
        val mine = withCommunity.map { it.rating!!.toDouble() }
        val community = withCommunity.map { it.feature.bangumiScore!!.toDouble() }
        return Heresy(
            count = withCommunity.size,
            meanDiff = mine.average() - community.average(),
            meanAbsDiff = mine.indices.map { abs(mine[it] - community[it]) }.average(),
            correlation = pearson(mine, community),
        )
    }

    /** 一部留出作品的预测。 */
    @Suppress("LongParameterList") // 只是一行数据
    private class Row(
        val subjectId: String,
        val fold: Int,
        val rating: Double,
        val predicted: Double,
        val score: Int,
        val community: Double?,
        val votes: Int,
        val low: Double?,
        val high: Double?,
    )

    @Suppress("LongParameterList")
    private fun predictRows(
        rated: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        tagOverrides: Map<String, TasteCategory>,
        communityVotePrior: Double,
        folds: Int,
        seed: Int,
        options: DivergenceOptions,
    ): List<Row>? {
        if (rated.size < MIN_RATED_FOR_EVAL) return null
        val ratedIds = rated.mapTo(HashSet()) { it.subjectId }
        // 自检只看预计评分和匹配度准不准，校准池取一部分就够；全部几千部每折都要扫一遍，手机上会算好几分钟。
        val calibration = pool.asSequence().filter { it.subjectId !in ratedIds }.take(SELF_CHECK_POOL).toList()
        val build = BuildTasteProfileUseCase()
        val compute = ComputeTasteMatchUseCase()
        val rows = ArrayList<Row>()
        for ((index, fold) in folds(rated, folds, seed).withIndex()) {
            val profile = build(
                fold.train,
                now,
                calibration,
                tagOverrides,
                communityVotePrior = communityVotePrior,
                divergenceOptions = options,
            )
            for (t in fold.test) {
                val result =
                    compute(t.feature, profile, tagOverrides = tagOverrides, withNeighbors = false, withReasons = false)
                val prediction = result.prediction ?: continue
                rows += Row(
                    subjectId = t.subjectId,
                    fold = index,
                    rating = t.rating!!.toDouble(),
                    predicted = prediction.rating,
                    score = result.score,
                    community = t.feature.bangumiScore?.toDouble()?.takeIf { it > 0.0 },
                    votes = t.feature.bangumiVotes,
                    low = prediction.low,
                    high = prediction.high,
                )
            }
        }
        return rows.takeIf { it.size >= 2 }
    }

    private fun accuracy(rows: List<Row>): Accuracy {
        val ratings = rows.map { it.rating }
        val mean = ratings.average()
        val highThreshold = maxOf(HIGH_RATING_FLOOR, quantile(ratings, HIGH_QUANTILE))
        val top = rows.sortedByDescending { it.score }.take(TOP_N)
        val lowVote = rows.filter { it.votes in 1 until LOW_VOTES }
        val withCommunity = rows.filter { it.community != null }
        val withInterval = rows.filter { it.low != null && it.high != null }
        return Accuracy(
            predicted = rows.size,
            meanAbsError = rows.map { abs(it.predicted - it.rating) }.average(),
            withinOne = rows.count { abs(it.predicted - it.rating) <= 1.0 }.toDouble() / rows.size,
            spearman = foldSpearman(rows) { it.predicted },
            communitySpearman = foldSpearman(withCommunity) { it.community!! },
            top10Hit = top.count { it.rating >= highThreshold }.toDouble() / top.size,
            lowVoteOverrated = lowVote.takeIf { it.isNotEmpty() }?.let { lv ->
                lv.count { it.predicted >= mean + OVERRATE_MARGIN && it.rating < mean }.toDouble() / lv.size
            },
            intervalCoverage = withInterval.takeIf { it.isNotEmpty() }?.let { wi ->
                wi.count { it.rating in it.low!!..it.high!! }.toDouble() / wi.size
            },
            intervalWidth = withInterval.takeIf { it.isNotEmpty() }?.map { it.high!! - it.low!! }?.average(),
            matchSpearman = foldSpearman(rows) { it.score.toDouble() },
            matchCommunityOverlap = withCommunity.takeIf { it.size >= MIN_FOLD_RANK }?.let { wc ->
                foldCorrelation(wc, { it.score.toDouble() }, { it.community!! })
            },
            matchLikedMoreAuc = likedMoreAuc(withCommunity),
        )
    }

    /** 每折内部两个量的 Spearman，按作品数加权平均。 */
    private fun foldCorrelation(rows: List<Row>, a: (Row) -> Double, b: (Row) -> Double): Double {
        var weighted = 0.0
        var total = 0
        for ((_, group) in rows.groupBy { it.fold }) {
            if (group.size < MIN_FOLD_RANK) continue
            weighted += spearman(group.map(a), group.map(b)) * group.size
            total += group.size
        }
        return if (total == 0) 0.0 else weighted / total
    }

    /** 「比大众更喜欢」（个人偏离比本人平均高 [LIKED_MORE_MARGIN] 分以上）的作品排在其它作品前面的概率；只有一类时为 null。 */
    private fun likedMoreAuc(rows: List<Row>): Double? {
        val dev = rows.map { it.rating - it.community!! }
        val mean = if (dev.isEmpty()) 0.0 else dev.average()
        val positive = rows.indices.filter { dev[it] - mean >= LIKED_MORE_MARGIN }.toSet()
        if (positive.isEmpty() || positive.size == rows.size) return null
        val ranks = ranks(rows.map { it.score.toDouble() })
        val n1 = positive.size.toDouble()
        val n0 = (rows.size - positive.size).toDouble()
        // 秩从 0 开始：正例秩和减去正例内部的最小可能秩和，就是正例排在负例前面的对数。
        val rankSum = positive.sumOf { ranks[it] + 1.0 }
        return (rankSum - n1 * (n1 + 1) / 2) / (n1 * n0)
    }

    /** 每折内部的 Spearman 按作品数加权平均；折内作品太少的跳过。 */
    private fun foldSpearman(rows: List<Row>, value: (Row) -> Double): Double {
        var weighted = 0.0
        var total = 0
        for ((_, group) in rows.groupBy { it.fold }) {
            if (group.size < MIN_FOLD_RANK) continue
            weighted += spearman(group.map(value), group.map { it.rating }) * group.size
            total += group.size
        }
        return if (total == 0) 0.0 else weighted / total
    }

    private fun <T> bootstrap(items: List<T>, random: Random, stat: (List<T>) -> Double): Interval? {
        if (items.size < 2) return null
        val estimate = stat(items)
        val draws = DoubleArray(BOOTSTRAP_ROUNDS) { stat(List(items.size) { items[random.nextInt(items.size)] }) }
        draws.sort()
        return Interval(
            estimate,
            draws[(CI_LOW * (BOOTSTRAP_ROUNDS - 1)).toInt()],
            draws[(CI_HIGH * (BOOTSTRAP_ROUNDS - 1)).toInt()],
        )
    }

    private fun quantile(values: List<Double>, q: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in x.indices) {
            num += (x[i] - mx) * (y[i] - my)
            dx += (x[i] - mx) * (x[i] - mx)
            dy += (y[i] - my) * (y[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }

    /** Spearman 秩相关；并列值取平均秩。 */
    internal fun spearman(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2) return 0.0
        return pearson(ranks(x).toList(), ranks(y).toList())
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

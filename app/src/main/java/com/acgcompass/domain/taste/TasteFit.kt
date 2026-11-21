package com.acgcompass.domain.taste

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 口味匹配度：「这部作品对你的口味有多契合」，和预计评分（你大概会打几分）是两个独立的输出。
 *
 * ```
 * 契合度 = w_口碑·大众基线 + w_兜底·min(大众基线, 0) + w_类比上·max(类比, 0) + w_类比下·min(类比, 0)
 *        + w_题材·题材相似 + w_圈子·min(圈子分, 封顶)
 * ```
 *
 * - 类比：你给题材最近的几部打的分（[TasteAnalogy]）；题材相似：十二维画像的相似度；
 * - 圈子分（[TasteCircle]）：是不是你常看的那类。只封顶计入——够「你那类」就满分，再典型也不继续加：
 *   常看的类型里一般的作品不会因为「特别典型」排到前面，从不碰的类型仍然被压下去；
 * - 大众基线：社区分按你的打分尺度换算；分支决定它占多少（保守还会对口碑差的作品额外扣分）。
 *
 * 刻度锚在你自己的作品上（[Anchors]）：作品库中位作品 ≈ 50，你看过的作品的中位 ≈ 75，你看过的前 10% ≈ 90；
 * 分支决定锚点和两端放多开（保守往中间收，激进两端放开）。分支只作用于匹配度，预计评分不分支。
 * 参数在公开数据上选定，评测见 docs/algorithm-eval.md §7.3。
 */
object TasteFit {
    /** 作品库样本量（每次建画像算一遍刻度）。 */
    const val SAMPLE_SIZE = 240

    /**
     * 刻度：作品库中位 → [mid]，你看过的中位 → [watched]，你看过的前 10% → [top]，作品库后 10% → [low]；
     * 超过「你看过的前 10%」的部分按 [stretch] 平滑逼近 99。
     */
    data class Scale(val mid: Double, val watched: Double, val top: Double, val low: Double, val stretch: Double = 1.5)

    /** 各部分权重和刻度。 */
    data class Weights(
        val quality: Double,
        val qualityFloor: Double,
        val analogyUp: Double,
        val analogyDown: Double,
        val similarity: Double,
        val circle: Double,
        val circleCap: Double,
        val scale: Scale,
    )

    @Suppress("MagicNumber") // 三个分支的参数表，逐个写出比拆成常量更好对照（评测见 docs/algorithm-eval.md §7.3）
    fun weights(branch: TasteMatchBranch): Weights = when (branch) {
        TasteMatchBranch.CONSERVATIVE -> Weights(0.6, 0.5, 2.0, 0.3, 0.25, 2.0, 1.0, Scale(50.0, 72.0, 86.0, 35.0))
        TasteMatchBranch.STANDARD -> Weights(0.3, 0.0, 1.5, 0.3, 0.25, 2.0, 1.0, Scale(50.0, 75.0, 90.0, 30.0))
        TasteMatchBranch.AGGRESSIVE -> Weights(0.0, 0.0, 2.0, 0.3, 0.25, 2.0, 1.0, Scale(45.0, 78.0, 94.0, 22.0))
    }

    /** 一部作品的原始部件：大众基线、类比标准分、题材相似分、圈子分；没有评分模型或圈子分时为 null。 */
    fun components(result: TasteMatchResult): DoubleArray? {
        val baseline = result.prediction?.baseline
        val circle = result.circleZ
        return if (baseline == null || circle == null) {
            null
        } else {
            doubleArrayOf(baseline, result.analogy?.z ?: 0.0, result.tasteSimilarity.toDouble(), circle)
        }
    }

    /**
     * 刻度锚点（按人权重的模型才有；旧公式保持原来的匹配度）：作品库部分从校准池等间隔取 [SAMPLE_SIZE] 部
     * （排除已评分作品），「你看过的」部分用全部已评分作品在当前画像下的部件。
     */
    fun scaleFor(
        profile: AdvancedTasteProfile,
        calibrationPool: List<WorkFeature>,
        tagOverrides: Map<String, TasteCategory>,
        rated: List<WorkFeature> = emptyList(),
    ): TasteFitScale? {
        if (profile.divergence?.includesAnalogy != true || profile.circle == null) return null
        val unrated = calibrationPool.filter { it.subjectId !in profile.ratedSubjectScores }
        val step = (unrated.size / SAMPLE_SIZE).coerceAtLeast(1)
        val compute = ComputeTasteMatchUseCase()
        fun parts(f: WorkFeature) =
            components(compute(f, profile, tagOverrides = tagOverrides, withNeighbors = false, withReasons = false))
        val samples = unrated.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE_SIZE).mapNotNull(::parts)
        return scaleOf(samples, rated.mapNotNull(::parts))
    }

    /** 由作品库样本和你看过的作品算出锚点；样本太少或大众基线没有变化时为 null。 */
    fun scaleOf(samples: List<DoubleArray>, watched: List<DoubleArray>): TasteFitScale? {
        val quality = Stat.of(samples.map { it[QUALITY] })
        if (samples.size < MIN_SAMPLES || quality.std <= MIN_STD) return null
        val anchors = TasteMatchBranch.entries.associateWith { branch ->
            val w = weights(branch)
            val pool = samples.map { raw(it, quality, w) }.sorted()
            val mine = watched.map { raw(it, quality, w) }.sorted()
            Anchors.of(pool, mine)
        }
        return TasteFitScale(quality, anchors)
    }

    /** 组合分（换算成百分比之前；越大越对口味）。 */
    fun raw(components: DoubleArray, scale: TasteFitScale, branch: TasteMatchBranch): Double =
        raw(components, scale.quality, weights(branch))

    /** 按分支刻度换成 5–99 的匹配度。 */
    fun score(components: DoubleArray, scale: TasteFitScale, branch: TasteMatchBranch): Int =
        scale.anchors.getValue(branch).map(raw(components, scale, branch), weights(branch).scale)

    private fun raw(x: DoubleArray, quality: Stat, w: Weights): Double {
        val analogy = x[ANALOGY]
        val q = quality.z(x[QUALITY])
        val similarity = ((x[SIMILARITY] - SIMILARITY_CENTER) / SIMILARITY_SPREAD).coerceIn(
            -MAX_SIMILARITY_Z,
            MAX_SIMILARITY_Z,
        )
        return w.quality * q + w.qualityFloor * q.coerceAtMost(0.0) +
            w.analogyUp * analogy.coerceAtLeast(0.0) + w.analogyDown * analogy.coerceAtMost(0.0) +
            w.similarity * similarity + w.circle * x[CIRCLE].coerceAtMost(w.circleCap)
    }

    private const val QUALITY = 0
    private const val ANALOGY = 1
    private const val SIMILARITY = 2
    private const val CIRCLE = 3
    private const val MIN_SAMPLES = 30
    private const val MIN_STD = 1e-6
    private const val SIMILARITY_CENTER = 50.0
    private const val SIMILARITY_SPREAD = 20.0
    private const val MAX_SIMILARITY_Z = 2.0
}

/**
 * 一个分支的刻度锚点（组合分）：作品库后 10% / 中位，你看过的作品的中位 / 前 10%。
 * 你看过的作品太少（少于 [MIN_WATCHED] 部）或整体不比作品库高时，用作品库的分布补出合理的锚点。
 */
data class Anchors(val poolLow: Double, val poolMid: Double, val watchedMid: Double, val watchedTop: Double) {
    /** 组合分 → 匹配度（5–99），分段线性、顶部平滑饱和。 */
    fun map(x: Double, s: TasteFit.Scale): Int {
        val span = watchedMid - poolMid
        val topSpan = watchedTop - watchedMid
        val lowSpan = (poolMid - poolLow).coerceAtLeast(MIN_SPAN)
        val v = when {
            x <= poolMid -> s.mid - (s.mid - s.low) * (poolMid - x) / lowSpan
            x <= watchedMid -> s.mid + (s.watched - s.mid) * (x - poolMid) / span
            x <= watchedTop -> s.watched + (s.top - s.watched) * (x - watchedMid) / topSpan
            else -> s.top + (CEILING - s.top) * (1.0 - exp(-(x - watchedTop) / (s.stretch * topSpan)))
        }
        return v.roundToInt().coerceIn(FLOOR, CEILING.toInt())
    }

    companion object {
        private const val MIN_WATCHED = 5
        private const val MIN_SPAN = 1e-3
        private const val FLOOR = 5
        private const val CEILING = 99.0
        private const val LOW_Q = 0.10
        private const val MID_Q = 0.50
        private const val TOP_Q = 0.90

        /** 你看过的中位至少比作品库中位高这么多（以作品库后 10% 到中位的距离计）。 */
        private const val MIN_LIFT = 0.5

        /** 你看过的前 10% 至少比中位高这么多（同上）。 */
        private const val MIN_TOP_LIFT = 0.3

        /** 没有看过的作品可用时：作品库中位以上多少个「后 10% 到中位的距离」算作「你看过的中位」。 */
        private const val DEFAULT_LIFT = 1.0

        /** [pool]、[watched] 都已升序。 */
        fun of(pool: List<Double>, watched: List<Double>): Anchors {
            val low = quantile(pool, LOW_Q)
            val mid = quantile(pool, MID_Q)
            val unit = (mid - low).coerceAtLeast(MIN_SPAN)
            val hasWatched = watched.size >= MIN_WATCHED
            val wMid = (if (hasWatched) quantile(watched, MID_Q) else mid + DEFAULT_LIFT * unit)
                .coerceAtLeast(mid + MIN_LIFT * unit)
            val wTop = (if (hasWatched) quantile(watched, TOP_Q) else quantile(pool, TOP_Q))
                .coerceAtLeast(wMid + MIN_TOP_LIFT * unit)
            return Anchors(low, mid, wMid, wTop)
        }

        private fun quantile(sorted: List<Double>, q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val pos = q * (sorted.size - 1)
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}

/** 一组数的均值和标准差（标准差不低于一个极小值，避免除以 0）。 */
data class Stat(val mean: Double, val std: Double) {
    fun z(x: Double): Double = (x - mean) / std

    companion object {
        private const val MIN_STD = 1e-9

        fun of(values: List<Double>): Stat {
            val mean = values.average()
            return Stat(mean, sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).coerceAtLeast(MIN_STD))
        }
    }
}

/** 大众基线在作品库样本上的分布，和各分支的刻度锚点。 */
data class TasteFitScale(val quality: Stat, val anchors: Map<TasteMatchBranch, Anchors>)

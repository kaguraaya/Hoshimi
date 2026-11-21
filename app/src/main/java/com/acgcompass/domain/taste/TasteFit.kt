package com.acgcompass.domain.taste

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 口味匹配度（按人权重的模型下）：
 *
 * ```
 * 契合度 = w_口碑·大众基线 + w_类比上·max(类比, 0) + w_类比下·min(类比, 0) + 0.25·题材相似 + w_圈子·圈子分
 * ```
 *
 * - 圈子分（[TasteCircle]）：是不是你常看的那类作品。长期不碰的类型分数低，不会因为篇幅、冷门这类因子排到前面；
 * - 类比：你给题材最近的几部打的分（对口味就往上提，[TasteAnalogy]）；
 * - 题材相似：十二维画像的相似度；
 * - 大众基线：社区分按你的打分尺度换算。分支决定它占多少：保守更信口碑，激进几乎不看。
 *
 * 组合分在作品库的一个均匀样本上标准化，再按分支的刻度换成分数。三个分支在「挑出你常看的那类」和「把同类作品的分数拉开」之间
 * 取不同的点：保守（口碑派）口碑占大头，分数最贴近社区、各分数段拉得最开；常规（平衡）你常看的类型优先、口碑决定同类之间的高低；
 * 激进（口味派）几乎只看圈子和类比，榜单最个性化、高分段区分较弱。已评分作品直接显示你打的分 ×10。
 */
object TasteFit {
    /** 标准化用的作品库样本量（每次建画像算一遍）。 */
    const val SAMPLE_SIZE = 240

    /** 各部分权重和刻度。 */
    data class Weights(
        val quality: Double,
        val analogyUp: Double,
        val analogyDown: Double,
        val similarity: Double,
        val circle: Double,
        val curve: HeadlineFormula,
    )

    @Suppress("MagicNumber") // 三个分支的参数表，逐个写出比拆成常量更好对照（评测见 docs/algorithm-eval.md §7.2）
    fun weights(branch: TasteMatchBranch): Weights = when (branch) {
        TasteMatchBranch.CONSERVATIVE -> Weights(2.0, 1.0, 0.3, 0.25, 0.5, curve(center = 55.0, rise = 0.7, fall = 9.0))
        TasteMatchBranch.STANDARD -> Weights(1.4, 2.0, 0.3, 0.25, 1.0, curve(center = 55.0, rise = 0.65, fall = 12.0))
        TasteMatchBranch.AGGRESSIVE -> Weights(0.6, 2.5, 0.3, 0.25, 1.0, curve(center = 52.0, rise = 0.75, fall = 14.0))
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
     * 各分支组合分在作品库均匀样本上的分布（按人权重的模型才有；旧公式保持原来的匹配度）。
     * 样本从校准池里等间隔取 [SAMPLE_SIZE] 部（排除已评分作品），覆盖热门和冷门。
     */
    fun scaleFor(
        profile: AdvancedTasteProfile,
        calibrationPool: List<WorkFeature>,
        tagOverrides: Map<String, TasteCategory>,
    ): TasteFitScale? {
        if (profile.divergence?.includesAnalogy != true || profile.circle == null) return null
        val unrated = calibrationPool.filter { it.subjectId !in profile.ratedSubjectScores }
        val step = (unrated.size / SAMPLE_SIZE).coerceAtLeast(1)
        val compute = ComputeTasteMatchUseCase()
        val samples = unrated.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE_SIZE).mapNotNull { f ->
            components(compute(f, profile, tagOverrides = tagOverrides, withNeighbors = false, withReasons = false))
        }
        return scaleOf(samples)
    }

    /** 由样本部件算出标准化参数；样本太少或大众基线没有变化时为 null。 */
    fun scaleOf(samples: List<DoubleArray>): TasteFitScale? {
        val quality = Stat.of(samples.map { it[QUALITY] })
        if (samples.size < MIN_SAMPLES || quality.std <= MIN_STD) return null
        val composite = TasteMatchBranch.entries.associateWith { branch ->
            Stat.of(samples.map { raw(it, quality, weights(branch)) })
        }
        return TasteFitScale(quality, composite)
    }

    /** 契合度标准分（作品库里中等作品约为 0）。 */
    fun z(components: DoubleArray, scale: TasteFitScale, branch: TasteMatchBranch): Double =
        scale.composite.getValue(branch).z(raw(components, scale.quality, weights(branch)))

    /** 按分支刻度换成分数。 */
    fun score(z: Double, branch: TasteMatchBranch): Int = weights(branch).curve.score(z)

    /** 已评分作品：直接是你打的分 ×10。 */
    fun ratedScore(rating: Double): Int = (rating * RATED_SCALE).roundToInt().coerceIn(
        HeadlineFormula.DEFAULT_FLOOR.toInt(),
        HeadlineFormula.DEFAULT_CAP.toInt(),
    )

    private fun raw(x: DoubleArray, quality: Stat, w: Weights): Double {
        val analogy = x[ANALOGY]
        val similarity = ((x[SIMILARITY] - SIMILARITY_CENTER) / SIMILARITY_SPREAD).coerceIn(
            -MAX_SIMILARITY_Z,
            MAX_SIMILARITY_Z,
        )
        return w.quality * quality.z(x[QUALITY]) +
            w.analogyUp * analogy.coerceAtLeast(0.0) + w.analogyDown * analogy.coerceAtMost(0.0) +
            w.similarity * similarity + w.circle * x[CIRCLE]
    }

    private fun curve(center: Double, rise: Double, fall: Double) =
        HeadlineFormula(1.0, 0.0, 0.0, 0.0, center = center, rise = rise, fall = fall)

    private const val QUALITY = 0
    private const val ANALOGY = 1
    private const val SIMILARITY = 2
    private const val CIRCLE = 3
    private const val MIN_SAMPLES = 30
    private const val MIN_STD = 1e-6
    private const val RATED_SCALE = 10.0
    private const val SIMILARITY_CENTER = 50.0
    private const val SIMILARITY_SPREAD = 20.0
    private const val MAX_SIMILARITY_Z = 2.0
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

/** 大众基线在作品库样本上的分布，和各分支组合分的分布。 */
data class TasteFitScale(val quality: Stat, val composite: Map<TasteMatchBranch, Stat>)

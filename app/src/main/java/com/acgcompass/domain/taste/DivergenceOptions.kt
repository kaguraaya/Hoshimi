package com.acgcompass.domain.taste

/** 社区分换算成「你的分」的方式。 */
enum class ScaleMode {
    /** 你的均分 + 斜率 ×（社区分 − 你看过作品的平均社区分）。 */
    LINEAR,

    /**
     * 分位映射：社区分在你看过作品里排第几，就换成你自己评分里同一位置的分数，再和线性换算按评分数混合。
     * 适合分数集中在两三档的人（线性换算对他们会给出过宽或过窄的范围）。
     */
    QUANTILE,
}

/**
 * 个人分歧里四个部分的权重（10 分制）：标签、同系列、取向（热门 / 年代 / 篇幅 / 争议度）、相似作品类比。
 * 类比部分的值是「类比标准分 × 你的评分标准差 × 相似程度」。
 */
data class ExpertWeights(val tag: Double, val franchise: Double, val axes: Double, val analogy: Double) {
    fun toArray(): DoubleArray = doubleArrayOf(tag, franchise, axes, analogy)

    companion object {
        /** 与固定公式等价的权重：1.3 ×（标签 + 0.32 × 系列 + 0.33 × 取向）+ 0.25 × 类比。 */
        val LEGACY = ExpertWeights(
            tag = PersonalDivergenceModel.PERSONAL_GAIN,
            franchise = PersonalDivergenceModel.PERSONAL_GAIN * PersonalDivergenceModel.FRANCHISE_WEIGHT,
            axes = PersonalDivergenceModel.PERSONAL_GAIN * PersonalDivergenceModel.AXIS_WEIGHT,
            analogy = BOLD_WEIGHT,
        )

        /**
         * 在 Bangumi15M 时间评测集上学到的全局权重：600 位评测用户各自在 2022-01-01 之前的评分里做内部 5 折，
         * 把所有折外预测合在一起求非负权重（只用切点之前的数据，不碰评测用的切点之后评分）。
         * 评分少的人几乎就用这组权重；评分多的人按自己的数据偏离它。
         */
        val LEARNED = ExpertWeights(tag = 0.778, franchise = 0.293, axes = 0.376, analogy = 0.058)

        /** 顺序与 [toArray] 相同：标签、系列、取向、类比。 */
        fun of(values: DoubleArray): ExpertWeights =
            ExpertWeights(values[TAG], values[FRANCHISE], values[AXES], values[ANALOGY])

        private const val TAG = 0
        private const val FRANCHISE = 1
        private const val AXES = 2
        private const val ANALOGY = 3
    }
}

/**
 * 个人分歧模型的选项。
 *
 * - [perUserGate]：用你自己的评分做内部 5 折，决定四个部分各给多少权重（向 [gatePrior] 收缩，强度 [gatePriorStrength]
 *   相当于多少部「按全局权重」的作品）；同时得到预测区间。关闭时用固定权重，不给区间。
 * - [scale]：社区分换算方式。
 * - [recencyHalfLifeDays]：大于 0 时，标签和取向回归按评分时间加权（越近权重越大，最旧的保留一半），0 表示不加权。
 * - [controversyAxis]：取向里加入「社区评分分布的离散程度」（需要作品带评分分布）。
 * - [intervalCoverage]：预测区间的名义覆盖率。
 */
data class DivergenceOptions(
    val perUserGate: Boolean = false,
    val gatePrior: ExpertWeights = ExpertWeights.LEGACY,
    val gatePriorStrength: Double = DEFAULT_PRIOR_STRENGTH,
    val scale: ScaleMode = ScaleMode.LINEAR,
    val recencyHalfLifeDays: Double = 0.0,
    val controversyAxis: Boolean = false,
    val intervalCoverage: Double = DEFAULT_COVERAGE,
    /** 同系列也认标题分隔符（「!」「:」「〜」等）前面的部分：「BanG Dream! Ave Mujica」和「BanG Dream! It's MyGO!!!!!」算一个系列。 */
    val seriesPrefix: Boolean = false,
    /** 内部 5 折按作品编号的散列分折：新增或删除一部评分时，其余作品不换折。关闭时整体打乱后按位置轮流分。 */
    val stableFolds: Boolean = false,
    /** 内部 5 折做几遍（每遍换一种分法），各部分权重、区间和折外值取平均，减少分法带来的随机波动。 */
    val innerRepeats: Int = 1,
) {
    companion object {
        private const val DEFAULT_PRIOR_STRENGTH = 20.0
        private const val DEFAULT_COVERAGE = 0.8

        /** 固定权重、线性换算，与之前的发布版完全一致。 */
        val LEGACY = DivergenceOptions()

        /**
         * App 实际使用的配置：按人权重（向 [ExpertWeights.LEARNED] 收缩）+ 预测区间，线性换算，内部 5 折按作品编号分折。
         * 依据见 docs/algorithm-eval.md 的时间切分评测：排序不低于旧版，伤害率显著下降，严重误推不增加；
         * 按编号分折和整体打乱准确度相同，拿掉一部评分时其余预测的变化少一半以上。
         */
        val DEFAULT = DivergenceOptions(perUserGate = true, gatePrior = ExpertWeights.LEARNED, stableFolds = true)
    }
}

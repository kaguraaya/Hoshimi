package com.acgcompass.domain.taste

import com.acgcompass.domain.recommend.RecommendSimilarity
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 一条分歧归因：某个因素让预计评分比「大众水准」高 / 低了多少分（10 分制）。 */
data class DivergenceFactor(val label: String, val delta: Double)

/** 一部你评过分的作品，连同它相对大众基线的分歧；[similarity] 仅在「相似作品」查询里有意义。 */
data class RatedExample(
    val subjectId: String,
    val rating: Int,
    val baseline: Double,
    val similarity: Double = 0.0,
) {
    /** 你的评分 − 大众基线。 */
    val divergence: Double get() = rating - baseline
}

/** 对一部作品的评分预测。 */
data class RatingPrediction(
    /** 预计你会打的分（1–10）。 */
    val rating: Double,
    /** 只看社区评分、按你的打分尺度换算出的「大众基线」。 */
    val baseline: Double,
    /** 个人分歧 = [rating] − [baseline]：你预计会比大众水准高 / 低多少。 */
    val divergence: Double,
    /** 分歧来自哪里（按影响从大到小；正 = 抬高，负 = 拉低）。 */
    val factors: List<DivergenceFactor>,
    /** 这部作品的社区评分（0–10）；没有时为 `null`。 */
    val community: Double? = null,
    /** 你的平均分与评分标准差：判断「这个预计评分对你来说算高还是低」的参照。 */
    val userMean: Double = 0.0,
    val userStd: Double = 1.0,
) {
    /** 预计评分比社区评分高 / 低多少；没有社区评分时为 `null`。 */
    val vsCommunity: Double? get() = community?.let { rating - it }

    /** 其中有多少只是打分习惯（你整体比社区宽松 / 严格）造成的：大众基线 − 社区评分。 */
    val habitOffset: Double? get() = community?.let { baseline - it }

    /** 预计评分在你自己的评分里处于什么位置（标准分）：0 = 你的平均水平。 */
    val relativeToSelf: Double get() = (rating - userMean) / userStd.coerceAtLeast(MIN_STD)

    private companion object {
        const val MIN_STD = 0.3
    }
}

/**
 * **个人分歧模型**（v6）：预测「你会给这部作品打几分」，并说明你和大众会在哪里产生分歧。
 *
 * 大多数人对大多数作品的评分跟着社区走——所以先用社区评分按你的打分尺度得到**大众基线**
 * `baseline = 你的均分 + 斜率·(社区分 − 你看过作品的平均社区分)`。真正的个性在**残差**里：
 * 你实际打的分减去基线。这个模型只学残差，由三部分相加：
 *
 * 1. **标签分歧**：把残差对作品的标签向量做岭回归（核形式求解，再还原成每个标签的权重）。
 *    某个标签权重为 +0.4，意思是「带这个标签的作品，你通常比大众水准多给 0.4 分」。
 * 2. **系列一致性**：同系列里你已经评过的作品的平均残差（带收缩）。第一季比大众多给 2 分的人，
 *    第二季多半还是偏高。
 * 3. **取向维度**：残差对「热门程度 / 年代 / 篇幅」的小型岭回归——你是偏爱小众还是大众、
 *    老番还是新番、长篇还是短篇。
 *
 * 各部分的系数来自 Bangumi15M 抽样 750 位真实用户的 5 折盲测（在一半用户上拟合、另一半上检验）：
 * 仅社区分 Spearman 0.539 / Top10 命中 0.688 → 本模型 0.607 / 0.739；预测与真实评分相差不超过 1 分的
 * 占 76%；在「与大众相差 2 分以上」的评分里，方向判断正确率 70%。同一实验里「想看 / 抛弃」等收藏状态
 * 对预测评分没有可见增益，所以不进这个模型（它们反映的是兴趣，不是看完后的满意度）。
 *
 * 纯 Kotlin、无 Android / IO 依赖；由 [fit] 从已评分样本拟合，结果是不可变值对象。
 */
@Suppress("LongParameterList") // 私有构造：只由 fit 组装的不可变拟合结果。
class PersonalDivergenceModel private constructor(
    val meanRating: Double,
    val meanCommunity: Double,
    val slope: Double,
    /** 标签 → 分歧权重（10 分制，作用于归一化标签向量）。 */
    private val tagWeights: Map<String, Double>,
    /** 标签 → 出现在多少部已评分作品里（展示「分歧最大的标签」时用来排除只有一两部作品支撑的）。 */
    private val tagSupport: Map<String, Int>,
    private val idf: Map<String, Double>,
    private val defaultIdf: Double,
    /** AI 升维分类缓存（清洗后标签 → 维度），预测时与拟合时用同一份。 */
    private val tagOverrides: Map<String, TasteCategory>,
    /** 系列键 → (残差和, 作品数)。 */
    private val franchise: Map<String, Pair<Double, Int>>,
    private val axisWeights: DoubleArray,
    /** 训练残差的标准差：个人分歧的典型幅度。 */
    val residualStd: Double,
    /** 你的评分的标准差（把预计评分换算成相对你自己尺度的高低时用）。 */
    val ratingStd: Double,
    /** 你打过的全部分数（升序）：把预计评分换算成「超过你看过的百分之多少」时用。 */
    private val sortedRatings: DoubleArray,
    val sampleCount: Int,
    internal val examples: List<Pair<RatedExample, Map<String, Double>>>,
) {

    /**
     * 你评过的作品里，题材上与 [feature] 最接近的几部（标签向量余弦相似度）。
     * 「相似的作品你当时怎么评的」是最直观的类比证据，供 AI 分析与解释使用。
     */
    fun nearestRated(feature: WorkFeature, limit: Int): List<RatedExample> {
        val vector = vectorOf(feature)
        if (vector.isEmpty()) return emptyList()
        return examples.asSequence()
            .filter { it.first.subjectId != feature.subjectId }
            .map { (example, v) -> example.copy(similarity = LinearSolver.dot(vector, v)) }
            .filter { it.similarity >= MIN_NEIGHBOR_SIMILARITY }
            .sortedByDescending { it.similarity }
            .take(limit)
            .toList()
    }

    /** [feature] 在本模型标签空间里的归一化向量（与拟合时同一套 IDF 和标签归类）。 */
    internal fun vectorOf(feature: WorkFeature): Map<String, Double> = tagVector(feature, idf, defaultIdf, tagOverrides)

    /** 你与大众分歧最大的评分：高于基线最多的 [limit] 部与低于基线最多的 [limit] 部。 */
    fun strongestDivergences(limit: Int): Pair<List<RatedExample>, List<RatedExample>> {
        val sorted = examples.map { it.first }.sortedByDescending { it.divergence }
        return sorted.take(limit).filter { it.divergence >= MIN_NOTABLE_DIVERGENCE } to
            sorted.takeLast(limit).reversed().filter { it.divergence <= -MIN_NOTABLE_DIVERGENCE }
    }

    /**
     * 喜欢度：[rating] 落在你自己打分历史里的位置，0–100（高于你看过的百分之多少）。推荐排序用它。
     *
     * 评分是整数，直接数「低于它的占比」会在整数点上跳变；这里把每个历史评分当成铺在
     * [r − 0.5, r + 0.5] 上，得到一条平滑、单调的曲线——预计 7.4 和 7.6 不会差出一大截。
     * 两端收在 [LIKEABILITY_MIN]–[LIKEABILITY_MAX]：预测不该给出 0% 或 100% 这种绝对的说法。
     */
    fun likeability(rating: Double): Int {
        if (sortedRatings.isEmpty()) return LIKEABILITY_NEUTRAL
        val below = sortedRatings.sumOf { r -> (rating - (r - HALF_STEP)).coerceIn(0.0, 1.0) }
        val percent = (PERCENT * below / sortedRatings.size).roundToInt()
        return percent.coerceIn(LIKEABILITY_MIN, LIKEABILITY_MAX)
    }

    /** 大众基线：只看社区评分时，预计你会打几分。无社区评分时取你的均分。 */
    fun baseline(community10: Double?): Double = if (community10 == null || community10 <= 0.0) {
        meanRating
    } else {
        meanRating + slope * (community10 - meanCommunity)
    }

    fun predict(feature: WorkFeature): RatingPrediction {
        val base = baseline(feature.bangumiScore?.toDouble())
        val vector = tagVector(feature, idf, defaultIdf, tagOverrides)
        val perTag = vector.mapNotNull { (tag, x) -> tagWeights[tag]?.let { tag to PERSONAL_GAIN * it * x } }
        val tagPart = perTag.sumOf { it.second }
        val franchisePart = PERSONAL_GAIN * FRANCHISE_WEIGHT * franchiseResidual(feature)
        val axes = axes(feature)
        val axisParts = AXIS_LABELS.indices.map { PERSONAL_GAIN * AXIS_WEIGHT * axisWeights[it] * axes[it] }
        val divergence = tagPart + franchisePart + axisParts.sum()

        val factors = ArrayList<DivergenceFactor>()
        perTag.forEach { (tag, delta) -> factors += DivergenceFactor(tag, delta) }
        if (franchisePart != 0.0) factors += DivergenceFactor(FRANCHISE_LABEL, franchisePart)
        AXIS_LABELS.forEachIndexed { i, labels ->
            val delta = axisParts[i]
            // 取向维度的标签随方向变化：同一条权重，对热门作是「偏爱大众向」，对冷门作是「偏爱小众向」。
            if (delta != 0.0) factors += DivergenceFactor(if (axes[i] >= 0.0) labels.first else labels.second, delta)
        }
        return RatingPrediction(
            community = feature.bangumiScore?.toDouble()?.takeIf { it > 0.0 },
            userMean = meanRating,
            userStd = ratingStd,
            rating = (base + divergence).coerceIn(MIN_RATING, MAX_RATING),
            baseline = base.coerceIn(MIN_RATING, MAX_RATING),
            divergence = divergence,
            factors = factors.filter { kotlin.math.abs(it.delta) >= MIN_FACTOR_DELTA }
                .sortedByDescending { kotlin.math.abs(it.delta) }
                .take(MAX_FACTORS),
        )
    }

    private fun franchiseResidual(feature: WorkFeature): Double {
        var sum = 0.0
        var n = 0
        for (key in franchiseKeys(feature)) {
            val (s, c) = franchise[key] ?: continue
            sum += s
            n += c
        }
        return if (n == 0) 0.0 else sum / (n + FRANCHISE_SHRINK)
    }

    /**
     * 你和大众分歧最大的标签：权重最高 / 最低的若干个（只取至少出现在 [MIN_TAG_SUPPORT] 部已评分作品里的）。
     * 用于「口味画像」页展示「你比大众更偏爱 / 更不买账的题材」。
     */
    fun strongestTags(limit: Int): Pair<List<DivergenceFactor>, List<DivergenceFactor>> {
        val sorted = tagWeights.entries
            .filter { (tagSupport[it.key] ?: 0) >= MIN_TAG_SUPPORT }
            .sortedByDescending { it.value }
        val liked = sorted.take(limit).filter { it.value >= MIN_DISPLAY_WEIGHT }
            .map { DivergenceFactor(it.key, it.value) }
        val disliked = sorted.takeLast(limit).reversed().filter { it.value <= -MIN_DISPLAY_WEIGHT }
            .map { DivergenceFactor(it.key, it.value) }
        return liked to disliked
    }

    /**
     * 取向维度的结论（[AxisTendency]）：把每个维度「典型一档差异」换算成分数，
     * 例如「热门程度」返回 −0.3 表示：同等口碑下，热门作比冷门作你大约少给 0.3 分。
     */
    fun axisTendencies(): List<AxisTendency> = AXIS_LABELS.indices.map { i ->
        AxisTendency(
            positiveLabel = AXIS_LABELS[i].first,
            negativeLabel = AXIS_LABELS[i].second,
            delta = AXIS_WEIGHT * axisWeights[i] * AXIS_TYPICAL_STEP[i],
        )
    }

    /** 打分宽严：你的均分比「同样这些作品的社区均分」高多少（正 = 手松，负 = 手紧）。 */
    val leniency: Double get() = meanRating - meanCommunity

    data class AxisTendency(val positiveLabel: String, val negativeLabel: String, val delta: Double)

    @Suppress("TooManyFunctions") // 拟合流程拆成若干小步骤，每步一个私有函数。
    companion object {
        /** 拟合所需的最少样本（带社区评分的已评分作品）。不足时不建模，调用方退回社区 + 画像口径。 */
        const val MIN_SAMPLES: Int = 8
        const val LIKEABILITY_MIN: Int = 3
        const val LIKEABILITY_MAX: Int = 97
        private const val LIKEABILITY_NEUTRAL: Int = 50
        private const val HALF_STEP: Double = 0.5
        private const val PERCENT: Double = 100.0

        /** 参与拟合的样本上限（取最近的）；核矩阵 n×n，300 以内在手机上是毫秒级。 */
        const val MAX_SAMPLES: Int = 300

        /** 标签岭回归的正则强度（真实数据网格：0.5–1 最优，取 1）。 */
        const val TAG_RIDGE_LAMBDA: Double = 1.0

        /** 系列残差 / 取向维度并入预测时的系数（在一半用户上最小二乘拟合得到 0.32 / 0.33）。 */
        /**
         * 个人分歧放大系数。按均方误差最优约为 1.0，但那样预测会紧贴社区分；
         * Bangumi15M 盲测里 1.3 让「与大众相差 1.5 分以上」的评分误差多降 25%，整体误差只增加 0.002。
         */
        const val PERSONAL_GAIN: Double = 1.3
        const val FRANCHISE_WEIGHT: Double = 0.32
        const val AXIS_WEIGHT: Double = 0.33
        const val FRANCHISE_SHRINK: Double = 0.5
        const val AXIS_RIDGE_LAMBDA: Double = 8.0

        /** 基线斜率向全站先验 1.0 收缩的强度。 */
        const val SLOPE_PRIOR_STRENGTH: Double = 15.0
        const val MAX_SLOPE: Double = 2.5

        /** 每部作品参与向量化的标签数上限。 */
        const val MAX_TAGS: Int = 30
        const val MIN_RATING: Double = 1.0
        const val MAX_RATING: Double = 10.0
        const val MIN_RATING_STD: Double = 0.6
        const val MIN_FACTOR_DELTA: Double = 0.15
        const val MAX_FACTORS: Int = 6
        const val FRANCHISE_LABEL: String = "同系列的评分习惯"

        /** 一个标签至少出现在这么多部已评分作品里，才出现在「分歧最大的标签」里（避免一部作品定性）。 */
        const val MIN_TAG_SUPPORT: Int = 3

        /** 「分歧最大的标签」里展示的最小权重（四舍五入后会显示成 ±0.0 的不展示）。 */
        const val MIN_DISPLAY_WEIGHT: Double = 0.15

        /** 「相似作品」的最低余弦相似度，以及算作「明显分歧」的最小分差。 */
        const val MIN_NEIGHBOR_SIMILARITY: Double = 0.15
        const val MIN_NOTABLE_DIVERGENCE: Double = 1.0

        private val AXIS_LABELS: List<Pair<String, String>> = listOf(
            "大众热门作" to "小众冷门作",
            "新番" to "老番",
            "长篇" to "短篇",
        )

        /** 各取向维度「典型一档差异」的大小（约等于一个标准差）。 */
        private val AXIS_TYPICAL_STEP = doubleArrayOf(1.5, 1.0, 1.0)

        private const val VOTES_CENTER = 8.0
        private const val YEAR_CENTER = 2012.0
        private const val YEAR_SCALE = 8.0
        private const val EPISODES_CENTER = 2.5
        private const val EPISODES_CAP = 200
        private val YEAR_REGEX = Regex("(19|20)\\d{2}")

        /** 取向维度特征：[ln(1+票数) − 8, (年份 − 2012)/8, ln(1+集数) − 2.5]；缺失取中性 0。 */
        internal fun axes(feature: WorkFeature): DoubleArray {
            val votes = if (feature.bangumiVotes > 0) ln(1.0 + feature.bangumiVotes) - VOTES_CENTER else 0.0
            val year = yearOf(feature)?.let { (it - YEAR_CENTER) / YEAR_SCALE } ?: 0.0
            val eps = if (feature.eps > 0) ln(1.0 + minOf(feature.eps, EPISODES_CAP)) - EPISODES_CENTER else 0.0
            return doubleArrayOf(votes, year, eps)
        }

        /** 从年代标签（「2016年7月」「2016」）里取年份；取标注人数最多的那个。 */
        private fun yearOf(feature: WorkFeature): Int? = feature.tagCounts
            .filter { TagClassifier.classify(it.name) == TasteCategory.TIME }
            .maxByOrNull { it.count }
            ?.let { YEAR_REGEX.find(it.name)?.value?.toIntOrNull() }

        internal fun franchiseKeys(feature: WorkFeature): Set<String> =
            feature.titleVariants.take(2).map(RecommendSimilarity::franchiseKey).filter { it.isNotBlank() }.toSet()

        /**
         * 作品的标签向量：去掉年代 / 噪声 / 自身标题标签，同义归一，权重 `sqrt(标注人数份额)·idf`，再 L2 归一。
         * 用份额平方根而不是对数：只有二十几个人标的边缘标签不该和主标签差不多重。
         */
        internal fun tagVector(
            feature: WorkFeature,
            idf: Map<String, Double>,
            defaultIdf: Double,
            overrides: Map<String, TasteCategory> = emptyMap(),
        ): Map<String, Double> {
            val terms = rawTerms(feature, overrides)
            if (terms.isEmpty()) return emptyMap()
            val weighted = terms.mapValues { (tag, w) -> w * (idf[tag] ?: defaultIdf) }
            val norm = sqrt(weighted.values.sumOf { it * it })
            return if (norm <= 0.0) emptyMap() else weighted.mapValues { it.value / norm }
        }

        /**
         * [overrides] 是 AI 升维分类的缓存（清洗后标签 → 维度）：本地规则认不出、被 AI 判为噪声 / 年代的标签
         * 也一并剔除，让后台的标签分析结果和 12 维画像一样作用到评分预测上。
         */
        private fun rawTerms(feature: WorkFeature, overrides: Map<String, TasteCategory>): Map<String, Double> {
            val ctx = TagClassifier.Context.of(titles = feature.titleVariants)
            val kept = feature.tagCounts.asSequence().take(MAX_TAGS).mapNotNull { tc ->
                when (TagClassifier.classify(tc.name, ctx, overrides)) {
                    TasteCategory.NOISE, TasteCategory.TIME -> null
                    else -> TagSynonyms.canonical(TagClassifier.clean(tc.name)) to tc.count
                }
            }.toList()
            val max = kept.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return emptyMap()
            val out = HashMap<String, Double>()
            for ((tag, count) in kept) {
                val w = sqrt(count.coerceAtLeast(0).toDouble() / max)
                if (w > 0.0) out[tag] = maxOf(out[tag] ?: 0.0, w)
            }
            return out
        }

        /**
         * 拟合。[corpus] 用来估计标签的逆文档频率（越常见的标签区分度越低）；
         * 传入已评分作品 + 本地缓存的未评分作品特征即可。
         */
        fun fit(
            samples: List<TasteSample>,
            corpus: List<WorkFeature> = emptyList(),
            overrides: Map<String, TasteCategory> = emptyMap(),
        ): PersonalDivergenceModel? {
            val rated = samples
                .filter { it.rating != null && (it.feature.bangumiScore ?: 0f) > 0f }
                .sortedByDescending { it.updatedAtMillis ?: 0L }
                .take(MAX_SAMPLES)
            val n = rated.size
            if (n < MIN_SAMPLES) return null

            val ratings = DoubleArray(n) { rated[it].rating!!.toDouble() }
            val community = DoubleArray(n) { rated[it].feature.bangumiScore!!.toDouble() }
            val meanR = ratings.average()
            val meanC = community.average()
            val slope = shrunkSlope(ratings, community, meanR, meanC)
            val residual = DoubleArray(n) { ratings[it] - (meanR + slope * (community[it] - meanC)) }

            val (idf, defaultIdf) = inverseDocumentFrequency(rated.map { it.feature } + corpus, overrides)
            val vectors = rated.map { tagVector(it.feature, idf, defaultIdf, overrides) }
            // 核矩阵非正定只会在数值异常时出现；此时不建模，调用方退回社区 + 画像口径。
            return tagRidge(vectors, residual)?.let { (weights, support) ->
                PersonalDivergenceModel(
                    meanRating = meanR,
                    meanCommunity = meanC,
                    slope = slope,
                    tagWeights = weights,
                    tagSupport = support,
                    idf = idf,
                    defaultIdf = defaultIdf,
                    tagOverrides = overrides,
                    franchise = franchiseResiduals(rated, residual),
                    axisWeights = axisRidge(rated, residual),
                    residualStd = sqrt(residual.sumOf { it * it } / n),
                    ratingStd = sqrt(ratings.sumOf { (it - meanR) * (it - meanR) } / n).coerceAtLeast(MIN_RATING_STD),
                    sortedRatings = ratings.sortedArray(),
                    sampleCount = n,
                    examples = rated.indices.map { i ->
                        RatedExample(rated[i].subjectId, rated[i].rating!!, meanR + slope * (community[i] - meanC)) to
                            vectors[i]
                    },
                )
            }
        }

        /** 个人评分对社区评分的最小二乘斜率，向全站先验 1.0 收缩。 */
        private fun shrunkSlope(ratings: DoubleArray, community: DoubleArray, meanR: Double, meanC: Double): Double {
            var sxy = 0.0
            var sxx = 0.0
            for (i in ratings.indices) {
                sxy += (community[i] - meanC) * (ratings[i] - meanR)
                sxx += (community[i] - meanC) * (community[i] - meanC)
            }
            val n = ratings.size
            val ols = if (sxx > 0.0) sxy / sxx else 1.0
            return ((n * ols + SLOPE_PRIOR_STRENGTH) / (n + SLOPE_PRIOR_STRENGTH)).coerceIn(0.0, MAX_SLOPE)
        }

        /** 标签的逆文档频率，以及没见过的标签用的默认值。 */
        private fun inverseDocumentFrequency(
            features: List<WorkFeature>,
            overrides: Map<String, TasteCategory>,
        ): Pair<Map<String, Double>, Double> {
            val docs = features.distinctBy { it.subjectId }
            val df = HashMap<String, Int>()
            for (doc in docs) for (tag in rawTerms(doc, overrides).keys) df[tag] = (df[tag] ?: 0) + 1
            return df.mapValues { ln(1.0 + docs.size.toDouble() / (1.0 + it.value)) } to ln(1.0 + docs.size.toDouble())
        }

        /**
         * 标签岭回归（核形式）：解 `(K + λI)·a = residual`，再还原为每个标签的权重 `w = Σ a_i·x_i`。
         * 返回（标签权重, 标签出现的作品数）。
         */
        private fun tagRidge(
            vectors: List<Map<String, Double>>,
            residual: DoubleArray,
        ): Pair<Map<String, Double>, Map<String, Int>>? {
            val n = vectors.size
            val kernel =
                Array(n) { i -> DoubleArray(n) { j -> if (j <= i) LinearSolver.dot(vectors[i], vectors[j]) else 0.0 } }
            for (i in 0 until n) {
                kernel[i][i] += TAG_RIDGE_LAMBDA
                for (j in i + 1 until n) kernel[i][j] = kernel[j][i]
            }
            val alpha = LinearSolver.solveSymmetricPositiveDefinite(kernel, residual) ?: return null
            val weights = HashMap<String, Double>()
            val support = HashMap<String, Int>()
            for (i in 0 until n) {
                for ((tag, x) in vectors[i]) {
                    weights[tag] = (weights[tag] ?: 0.0) + alpha[i] * x
                    support[tag] = (support[tag] ?: 0) + 1
                }
            }
            return weights to support
        }

        /** 系列键 → (残差和, 作品数)。 */
        private fun franchiseResiduals(
            rated: List<TasteSample>,
            residual: DoubleArray,
        ): Map<String, Pair<Double, Int>> {
            val franchise = HashMap<String, Pair<Double, Int>>()
            for (i in rated.indices) {
                for (key in franchiseKeys(rated[i].feature)) {
                    val (s, c) = franchise[key] ?: (0.0 to 0)
                    franchise[key] = (s + residual[i]) to (c + 1)
                }
            }
            return franchise
        }

        /** 取向维度岭回归（3×3 正规方程）。 */
        private fun axisRidge(rated: List<TasteSample>, residual: DoubleArray): DoubleArray {
            val dims = AXIS_LABELS.size
            val gram = Array(dims) { DoubleArray(dims) }
            val rhs = DoubleArray(dims)
            for (i in rated.indices) {
                val row = axes(rated[i].feature)
                for (p in 0 until dims) {
                    rhs[p] += row[p] * residual[i]
                    for (q in 0 until dims) gram[p][q] += row[p] * row[q]
                }
            }
            for (p in 0 until dims) gram[p][p] += AXIS_RIDGE_LAMBDA
            return LinearSolver.solveSymmetricPositiveDefinite(gram, rhs) ?: DoubleArray(dims)
        }
    }
}

/** 小型稠密线性方程组求解（Cholesky 分解）。 */
internal object LinearSolver {

    /** 稀疏向量点积。 */
    fun dot(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var s = 0.0
        for ((k, v) in small) large[k]?.let { s += v * it }
        return s
    }

    /** 解 `A·x = b`，[a] 为对称正定矩阵（会被就地覆盖为分解结果）。矩阵非正定时返回 `null`。 */
    fun solveSymmetricPositiveDefinite(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (j in 0 until n) {
            var diag = a[j][j]
            for (k in 0 until j) diag -= a[j][k] * a[j][k]
            if (diag <= 0.0) return null
            val root = sqrt(diag)
            a[j][j] = root
            for (i in j + 1 until n) {
                var v = a[i][j]
                for (k in 0 until j) v -= a[i][k] * a[j][k]
                a[i][j] = v / root
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var v = b[i]
            for (k in 0 until i) v -= a[i][k] * y[k]
            y[i] = v / a[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var v = y[i]
            for (k in i + 1 until n) v -= a[k][i] * x[k]
            x[i] = v / a[i][i]
        }
        return x
    }
}

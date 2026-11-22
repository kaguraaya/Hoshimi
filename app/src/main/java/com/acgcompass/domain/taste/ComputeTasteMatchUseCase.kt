package com.acgcompass.domain.taste

import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一条推荐理由：某大类（及其代表标签）对总分的有符号贡献。 */
data class TasteReason(
    val category: TasteCategory,
    val label: String,
    val contribution: Double,
)

/**
 * 口味匹配结果。[score] 为最终 5–95 分；[confidence] 独立输出（不混入主分）；[reasons] 为可解释贡献项。
 */
data class TasteMatchResult(
    val score: Int,
    val confidence: Double,
    val rawZ: Double,
    val probability: Double,
    val isRated: Boolean,
    val reasons: List<TasteReason> = emptyList(),
    /**
     * 候选证据量 ∈ [0,1]（[TasteScoringParams.candidateEvidence]）：作品自身可用于比对的内容标签是否充足。
     * 偏低表示「信息不足」，分数已向中性收缩、置信度已下调。
     */
    val evidence: Double = 1.0,
    /**
     * 画像熟悉度 ∈ [0,1]：候选内容标签的强度里，有多大比例落在画像已有立场（正 / 负向）的标签上。
     * 偏低表示这部作品在你的常看范围之外——探索队列据此挑「跨界」候选。
     */
    val familiarity: Double = 0.0,
    /** 该作品的社区评分（0–10）与评分人数，原样带出供推荐排序使用；无评分为 `null` / 0。 */
    val communityScore10: Double? = null,
    val communityVotes: Int = 0,
    /** 社区融合之前的纯画像分（5–95）；已评分作品与 [score] 相同。用于解释「口味本身怎么看」与离线评测。 */
    val profileScore: Int = score,
    /**
     * 题材相似度：12 维画像给出的「像不像你常看、常给高分的类型」（12 维画像匹配分）。
     * 它不回答「你会不会喜欢」，只作为主分数旁边的补充说明。
     */
    val tasteSimilarity: Int = score,
    /** 主分数是否来自评分模型（评分足够多时为 true）；为 false 时主分数是 12 维画像匹配分。 */
    val fromRatingModel: Boolean = false,
    /**
     * 预计满意度（5–95，[TasteScoringParams.satisfactionScore]）：看完大概会有多满意。推荐排序用它；
     * 已评分作品与 [score] 相同（都由显式评分锚定）。
     */
    val satisfaction: Int = score,
    /**
     * 评分预测与分歧归因（[PersonalDivergenceModel]）：预计你会打几分、比大众水准高 / 低多少、原因是什么。
     * 画像样本不足以建模时为 `null`。
     */
    val prediction: RatingPrediction? = null,
    /** 你评过的作品里题材最接近的几部（类比证据：相似的作品你当时怎么评的）。 */
    val similarRated: List<RatedExample> = emptyList(),
    /** 相似作品类比（主分数的主要依据）；评分太少建不了模型时为 null。 */
    val analogy: TasteAnalogy? = null,
    /** 参与类比的已评分作品数（有标签、有社区评分的才算）；用来核对是否有评分没进模型。 */
    val modelSamples: Int = 0,
    /** 换算成百分比之前的原始匹配分（[HeadlineFormula.z]）；没有评分模型时为 null。 */
    val headlineZ: Double? = null,
    /** 圈子分（[TasteCircle.z]）：是不是你常看的那类；没有圈子模型时为 null。 */
    val circleZ: Double? = null,
    /** 已评分作品的回测值（建画像时不知道这部作品）；未评分、或回测还没算完时为 null。 */
    val backtest: BacktestView? = null,
    /** 已评分作品的回测还没算完（或刚评分 / 改了分）：先不显示分数，见 [TasteBacktest.pending]。 */
    val backtestPending: Boolean = false,
) {
    /** 正向理由（真正「命中你口味」的维度）；负向贡献项不应被展示成「命中」。 */
    val positiveReasons: List<TasteReason> get() = reasons.filter { it.contribution > 0.0 }

    companion object {
        /** 画像不可用时的占位中性结果（冷启动）。 */
        val NEUTRAL = TasteMatchResult(
            score = 50,
            confidence = 0.25,
            rawZ = 0.0,
            probability = 0.5,
            isRated = false,
        )
    }
}

/**
 * 将候选作品映射为校准后的口味匹配度；实现为可单元测试的纯函数：
 *
 * 1. 用 [TasteRawScorer] 求十二维融合 raw `z(x)`；
 * 2. 叠加「已评分同作偏置」`0.12·pref(r_x)`（评分过的作品分数贴合本人评分）；
 * 3. 温度化 logistic 校准 `p=σ((z-μ)/τ)`；
 * 4. 分数拉开 `50 + sign·min(45, 1.18·|100p-50|)` → [5,95] 整数（解决「分数挤在 55–65」）；
 * 5. 置信度由样本量 + 各维覆盖率独立计算，并按候选证据量下调；
 * 6. 候选证据量收缩（v4）；另行输出「预计满意度」供推荐排序（v5）。
 */
class ComputeTasteMatchUseCase @Inject constructor() {

    /**
     * @param userRating 若用户已给该作品评分则传入（触发已评分偏置）；否则回退到画像内记录的评分。
     * @param tagOverrides AI 升维分类缓存，仅覆盖本地暂归为题材的未知标签；空表使用本地规则。
     */
    @Suppress("LongParameterList", "LongMethod") // 后两个是批量 / 排名场景的开关；结果字段逐个列出，拆开反而难核对
    operator fun invoke(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        userRating: Int? = null,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        /** 批量排序时为 false：「题材相近的已评分作品」只在详情页用，批量算它是白花时间。 */
        withNeighbors: Boolean = true,
        /** 只要分数（本地排名）时为 false：不算推荐理由，每部作品约省三成时间。 */
        withReasons: Boolean = true,
    ): TasteMatchResult {
        val rating = userRating ?: profile.ratedSubjectScores[feature.subjectId]
        val isRated = rating != null

        if (!profile.isUsable) return coldStart(feature, isRated)

        val f = TasteRawScorer.featurize(feature, tagOverrides)
        val rawZ = TasteRawScorer.rawScore(f, feature, profile)
        val familiarity = profileFamiliarity(f, profile)
        val evidence = TasteScoringParams.candidateEvidence(contentTagCount(f), familiarity)
        val pProfile = TasteScoringParams.shrinkToNeutral(
            TasteScoringParams.logistic(rawZ, profile.calibration.mu, profile.calibration.tau),
            evidence,
        )
        val p = if (isRated) {
            TasteScoringParams.RATED_ANCHOR_WEIGHT * TasteScoringParams.ratedAnchorProbability(rating) +
                (1.0 - TasteScoringParams.RATED_ANCHOR_WEIGHT) * pProfile
        } else {
            pProfile
        }
        // 未评分作品在纯画像分之上做「口味门控社区融合」——画像有立场时（分远离中性 50）几乎不动，
        // 画像接近中性时，按社区口碑和票数置信补位，改善品质驱动的跨题材排序（真实
        // 数据 LOO Spearman 0.33→0.54）。已评分作品仍以显式评分锚为准，不叠社区。
        val score = if (isRated) {
            finalScore(p)
        } else {
            TasteScoringParams.communityBlendedScore(
                finalScore(p),
                feature.bangumiScore?.toDouble(),
                feature.bangumiVotes,
            )
        }

        // 已评分作品的分数由显式评分锚定，置信度不受候选标签多少影响。
        val evidenceFactor = if (isRated) 1.0 else evidenceConfidence(evidence, CONFIDENCE_EVIDENCE_FLOOR)
        val confidence = evidenceFactor * TasteScoringParams.confidence(
            realSampleCount = profile.sampleCount,
            tagCoverage = profile.coverage.tag,
            staffCoverage = profile.coverage.staff,
            commentCoverage = profile.coverage.comment,
            timeCoverage = profile.coverage.time,
        )

        val (prediction, analogy) = predictWithAnalogy(profile.divergence, feature)
        val likeability = likeabilityOf(profile.divergence, rating, prediction)
        val satisfaction = likeability
            ?: fallbackSatisfaction(isRated, score, feature, profile, rawZ, evidence, prediction)
        return TasteMatchResult(
            profileScore = finalScore(p),
            tasteSimilarity = score,
            fromRatingModel = likeability != null,
            satisfaction = satisfaction,
            prediction = prediction,
            similarRated = neighborsOf(profile, feature, withNeighbors),
            analogy = analogy,
            score = score,
            confidence = confidence,
            rawZ = rawZ,
            probability = p,
            isRated = isRated,
            reasons = if (withReasons) buildReasons(f, feature, profile) else emptyList(),
            evidence = evidence,
            familiarity = familiarity,
            communityScore10 = feature.bangumiScore?.toDouble()?.takeIf { it > 0.0 },
            communityVotes = feature.bangumiVotes,
            circleZ = profile.circle?.z(feature),
        ).withHeadline(profile, rating?.toDouble() ?: prediction?.rating)
    }

    /**
     * 预计满意度。有个人分歧模型时用它的预测评分——换算成相对你自己打分尺度的高低：
     * 预计评分等于你的均分 → 50，高一个标准差 → 约 77。没有模型（样本太少）时退回
     * 「社区 + 画像」的 [TasteScoringParams.satisfactionScore]。
     */
    /** 预计评分（已评分用你打的分）在你自己打分历史里的位置；推荐排序用它。 */
    private fun likeabilityOf(model: PersonalDivergenceModel?, rating: Int?, prediction: RatingPrediction?): Int? {
        val basis = rating?.toDouble() ?: prediction?.rating
        return if (model == null || basis == null) null else model.likeability(basis)
    }

    /** 评分太少、建不了评分模型时的满意度：已评分取匹配分，未评分用社区 + 画像的旧口径。 */
    @Suppress("LongParameterList") // 旧口径需要的全部输入，逐个传入比打包成对象更直白
    private fun fallbackSatisfaction(
        isRated: Boolean,
        score: Int,
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        rawZ: Double,
        evidence: Double,
        prediction: RatingPrediction?,
    ): Int = if (isRated) score else satisfaction(feature, profile, rawZ, evidence, prediction)

    private fun neighborsOf(profile: AdvancedTasteProfile, feature: WorkFeature, enabled: Boolean): List<RatedExample> =
        if (enabled) profile.divergence?.nearestRated(feature, SIMILAR_RATED_LIMIT).orEmpty() else emptyList()

    private fun satisfaction(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        rawZ: Double,
        evidence: Double,
        prediction: RatingPrediction?,
    ): Int {
        val model = profile.divergence
        if (prediction != null && model != null) {
            return TasteScoringParams.satisfactionFromRating(prediction.rating, model.meanRating, model.ratingStd)
        }
        return TasteScoringParams.satisfactionScore(
            profileZ = (rawZ - profile.calibration.mu) / profile.calibration.tau,
            evidence = evidence,
            bangumiScore10 = feature.bangumiScore?.toDouble(),
            votes = feature.bangumiVotes,
            sampleCount = profile.sampleCount,
        )
    }

    /** 冷启动（画像不可用）：仅用社区口碑做极弱先验，低置信度。 */
    private fun coldStart(feature: WorkFeature, isRated: Boolean): TasteMatchResult {
        val z = TasteCategory.COMMUNITY.defaultWeight *
            TasteScoringParams.communityGuidance(feature.bangumiScore?.toDouble(), feature.bangumiVotes)
        val p = TasteScoringParams.logistic(z, 0.0, TasteScoringParams.LOGISTIC_TAU_FLOOR)
        return TasteMatchResult.NEUTRAL.copy(
            satisfaction = TasteScoringParams.satisfactionScore(
                profileZ = null,
                evidence = 0.0,
                bangumiScore10 = feature.bangumiScore?.toDouble(),
                votes = feature.bangumiVotes,
                sampleCount = 0,
            ),
            profileScore = finalScore(p),
            score = finalScore(p),
            rawZ = z,
            probability = p,
            isRated = isRated,
        )
    }

    /** 候选的有效内容标签数（不含实体维度：staff / CV / 角色名不代表「题材信息量」）。 */
    private fun contentTagCount(f: TasteRawScorer.Featurized): Int =
        CONTENT_EVIDENCE_CATEGORIES.sumOf { f.byCategory[it]?.size ?: 0 }

    /** 候选内容标签强度中，画像已有立场（正 / 负向任一）的占比。 */
    private fun profileFamiliarity(f: TasteRawScorer.Featurized, profile: AdvancedTasteProfile): Double {
        var known = 0.0
        var total = 0.0
        for (cat in CONTENT_EVIDENCE_CATEGORIES) {
            val tags = f.byCategory[cat] ?: continue
            val pref = profile.category(cat)
            for ((k, q) in tags) {
                total += q
                if (k in pref.positive || k in pref.negative) known += q
            }
        }
        return if (total > 0.0) (known / total).coerceIn(0.0, 1.0) else 0.0
    }

    private fun finalScore(probability: Double): Int =
        TasteScoringParams.spread(probability).roundToInt().coerceIn(5, 95)

    /** 取贡献最大的若干维度作为理由（正向优先，附代表标签）。 */
    private fun buildReasons(
        f: TasteRawScorer.Featurized,
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
    ): List<TasteReason> {
        val reasons = ArrayList<TasteReason>()
        val lambda = TasteScoringParams.LAMBDA_NEGATIVE

        for (cat in TasteCategory.SINGLE_TAG_CATEGORIES) {
            if (cat == TasteCategory.NOISE) continue
            val catTags = f.byCategory[cat] ?: continue
            val pref = profile.category(cat)
            val sim = TasteRawScorer.simCategory(catTags, pref, TasteRawScorer.lambdaFor(cat, lambda))
            val contribution = cat.defaultWeight * sim
            if (abs(contribution) < REASON_MIN) continue
            // 代表标签按该维度净贡献的**实际符号**选取：正向贡献 → 命中最强的正向偏好标签；
            // 负向贡献 → 命中最强的负向偏好标签（如实说明是哪个题材拉低了匹配），不再一律贴正向标签。
            val topTag = if (contribution >= 0.0) {
                catTags.entries.maxByOrNull { (k, q) -> q * (pref.positive[k] ?: 0.0) }
                    ?.takeIf { (k, q) -> q * (pref.positive[k] ?: 0.0) > 0.0 }?.key
            } else {
                catTags.entries.maxByOrNull { (k, q) -> q * (pref.negative[k] ?: 0.0) }
                    ?.takeIf { (k, q) -> q * (pref.negative[k] ?: 0.0) > 0.0 }?.key
            }
            val text = if (topTag != null) "${cat.label}·$topTag" else cat.label
            reasons += TasteReason(cat, text, contribution)
        }

        val comboSim = TasteRawScorer.simCombo(f.topicDeviceTags, profile.combos)
        if (comboSim > 0.0) {
            val best = profile.combos
                .filter { f.topicDeviceTags.containsAll(it.tags) }
                .maxByOrNull { it.strength }
            val text = best?.tags?.joinToString("+")?.let { "${TasteCategory.COMBO.label}·$it" }
                ?: TasteCategory.COMBO.label
            reasons += TasteReason(TasteCategory.COMBO, text, TasteCategory.COMBO.defaultWeight * comboSim)
        }

        return reasons.sortedByDescending { it.contribution }.take(MAX_REASONS)
    }

    companion object {
        /**
         * 口味匹配度（主分数）：按 [formula]（设置里选的分支，见 [TasteMatchBranch]）把预计评分、
         * 相似作品类比和题材相似度换算成百分比。
         */
        fun headlineScore(
            ratingZ: Double,
            analogyZ: Double?,
            similarity: Int,
            formula: HeadlineFormula = TasteAlgorithmConfig.DEFAULT.formula(TasteMatchBranch.STANDARD),
        ): Int = formula.score(formula.z(ratingZ, analogyZ, similarity))

        private const val REASON_MIN: Double = 1e-3
        private const val MAX_REASONS: Int = 4
        private const val SIMILAR_RATED_LIMIT: Int = 6

        /** 证据量为 0 时置信度保留的比例（不直接归零：画像本身的可信度仍在）。 */
        private const val CONFIDENCE_EVIDENCE_FLOOR: Double = 0.5

        /** 计入「候选证据量 / 画像熟悉度」的内容维度。 */
        private val CONTENT_EVIDENCE_CATEGORIES: List<TasteCategory> = listOf(
            TasteCategory.TOPIC,
            TasteCategory.DEVICE,
            TasteCategory.XP,
            TasteCategory.SOURCE,
            TasteCategory.MEME,
            TasteCategory.TIME,
        )
    }
}

/** 候选证据量对置信度的折扣：没有证据时保留 [floor]，证据齐全时不打折。 */
private fun evidenceConfidence(evidence: Double, floor: Double): Double = floor + (1.0 - floor) * evidence

/** 预计评分（有很像的已评分作品时按类比修正）与相似作品类比。 */
private fun predictWithAnalogy(
    model: PersonalDivergenceModel?,
    feature: WorkFeature,
): Pair<RatingPrediction?, TasteAnalogy?> {
    if (model == null) return null to null
    val analogy = model.analogy(feature)
    // 按人权重的模型已经把类比当成预测的一部分，这里不另外修正。
    val prediction = model.predict(feature)
    return (if (model.includesAnalogy) prediction else prediction.withAnalogy(analogy, model.ratingStd)) to analogy
}

/** 旧公式里你打分的标准差下限，避免打分很集中的人换算出过大的标准分。 */
private const val HEADLINE_MIN_STD = 0.6

/** 有评分模型时按所选分支出口味匹配度（[TasteFit]，已评分作品也一样算）；否则保持画像匹配分。 */
private fun TasteMatchResult.withHeadline(profile: AdvancedTasteProfile, basisRating: Double?): TasteMatchResult {
    val model = profile.divergence
    if (model == null || basisRating == null || !fromRatingModel) return this
    val fit = profile.tasteFit
    val fitComponents = if (fit != null) TasteFit.components(this) else null
    return if (fit != null && fitComponents != null) {
        val raw = TasteFit.raw(fitComponents, fit, profile.matchBranch)
        copy(
            score = TasteFit.score(fitComponents, fit, profile.matchBranch),
            modelSamples = model.examples.size,
            headlineZ = raw,
        )
    } else {
        val std = model.ratingStd.coerceAtLeast(HEADLINE_MIN_STD)
        val z = profile.headlineFormula.z((basisRating - model.meanRating) / std, analogy?.z, tasteSimilarity)
        copy(score = profile.headlineFormula.score(z), modelSamples = model.examples.size, headlineZ = z)
    }
}

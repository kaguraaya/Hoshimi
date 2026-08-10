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
) {
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
 * 5. 置信度由样本量 + 各维覆盖率独立计算。
 */
class ComputeTasteMatchUseCase @Inject constructor() {

    /**
     * @param userRating 若用户已给该作品评分则传入（触发已评分偏置）；否则回退到画像内记录的评分。
     * @param tagOverrides AI 升维分类缓存，仅覆盖本地暂归为题材的未知标签；空表使用本地规则。
     */
    operator fun invoke(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        userRating: Int? = null,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
    ): TasteMatchResult {
        val rating = userRating ?: profile.ratedSubjectScores[feature.subjectId]
        val isRated = rating != null

        if (!profile.isUsable) {
            // 冷启动：仅用社区口碑做极弱先验，低置信度。
            val z = TasteCategory.COMMUNITY.defaultWeight *
                TasteScoringParams.communityGuidance(feature.bangumiScore?.toDouble(), feature.bangumiVotes)
            val p = TasteScoringParams.logistic(z, 0.0, TasteScoringParams.LOGISTIC_TAU_FLOOR)
            return TasteMatchResult.NEUTRAL.copy(
                score = finalScore(p),
                rawZ = z,
                probability = p,
                isRated = isRated,
            )
        }

        val f = TasteRawScorer.featurize(feature, tagOverrides)
        val rawZ = TasteRawScorer.rawScore(f, feature, profile)
        val pProfile = TasteScoringParams.logistic(rawZ, profile.calibration.mu, profile.calibration.tau)
        // 已评分作品以显式评分锚为主，并用长期画像修正，避免小众高分作品因标签稀疏被低估。
        // 未评分作品使用纯画像校准分。
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

        val confidence = TasteScoringParams.confidence(
            realSampleCount = profile.sampleCount,
            tagCoverage = profile.coverage.tag,
            staffCoverage = profile.coverage.staff,
            commentCoverage = profile.coverage.comment,
            timeCoverage = profile.coverage.time,
        )

        return TasteMatchResult(
            score = score,
            confidence = confidence,
            rawZ = rawZ,
            probability = p,
            isRated = isRated,
            reasons = buildReasons(f, feature, profile),
        )
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

    private companion object {
        const val REASON_MIN: Double = 1e-3
        const val MAX_REASONS: Int = 4
    }
}

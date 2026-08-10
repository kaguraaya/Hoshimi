package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import javax.inject.Inject
import kotlin.math.ln

/**
 * 个性化口味评分结果。
 *
 * - [available]：是否能给出匹配度；`false` 时上层显示「暂无画像 / 数据不足」（不伪造）。
 * - [fraction]：综合匹配度 ∈ [0.05, 0.98]，含**轻量**社区分先验（用于详情页百分比展示）。
 * - [personal]：**纯个人口味**分量 ∈ [0,1]（不含社区分），供推荐器排序，使推荐更贴合个人而非社区高分。
 * - [matchedHighTags]/[matchedLowTags]：命中的你高分 / 低分常见标签（原大小写，供可解释理由）。
 * - [lowConfidence]：画像样本不足；上层措辞「可能 / 仅供参考」并把分数向中性收缩。
 * - [basis]：评分依据，供上层生成不同理由文案。
 */
data class TasteScore(
    val available: Boolean,
    val fraction: Float,
    val personal: Float,
    val matchedHighTags: List<String>,
    val matchedLowTags: List<String>,
    val lowConfidence: Boolean,
    val basis: Basis,
) {
    enum class Basis { TAG_OVERLAP, COMMUNITY_FALLBACK, NO_PROFILE, INSUFFICIENT }

    companion object {
        /** 无口味画像：不可用，分数中性占位。 */
        fun noProfile(): TasteScore =
            TasteScore(false, 0.5f, 0.5f, emptyList(), emptyList(), lowConfidence = true, Basis.NO_PROFILE)

        /** 既无标签也无社区评分：数据不足，不可用。 */
        fun insufficient(): TasteScore =
            TasteScore(false, 0.5f, 0.5f, emptyList(), emptyList(), lowConfidence = true, Basis.INSUFFICIENT)
    }
}

/**
 * 个性化口味评分器（领域用例，纯 Kotlin、无 Android/IO 依赖，便于单测）。
 *
 * 详情页「口味匹配度」与「今晚看什么」推荐**共用**同一套打分，保证两处口径一致。
 *
 * 核心思路：
 * 1. **加权标签重合（主导）**：把作品标签与你口味画像的「高/低分常见标签」做重合，命中高分标签累加正权、
 *    命中低分标签累加负权。权重 = `ln(1+标注次数)` × 题材/元数据因子（[TasteTagTaxonomy]）——
 *    你高分作品里**反复出现**的标签更重要；**年份/厂商**等元数据标签被弱化、**题材**标签全权重。
 * 2. **总体吻合度**：用软饱和 `posSum/(posSum+K)` 聚合，命中**多个**或**更强**的标签即显著拉升，
 *    体现「魔法+游戏+萝莉 这类组合叠加更优先」，而不仅看单标签。
 * 3. **社区分仅轻量先验**：换算为「相对你均分的高低」（习惯评分），以很低权重并入综合分（再次弱化社区分，
 *    但仍参考）；推荐排序用 [TasteScore.personal]（**完全不含**社区分）。
 * 4. **样本不足收缩**：画像置信低时把分数向中性 0.5 收缩，避免低样本下过度自信。
 * 5. **无标签退化**：作品无标签或画像无标签时，退化为「社区分相对你均分」的低置信估计；连社区分都没有才判定数据不足。
 */
class PersonalTasteScorer @Inject constructor() {

    /**
     * @param work 待评分作品（用其社区标签 [Work.tags]）。
     * @param profile 口味画像；`null` → [TasteScore.noProfile]。
     * @param communityScore10 该作品社区评分（10 分制，可空）；仅作轻量先验。
     */
    fun score(work: Work, profile: TasteProfile?, communityScore10: Float?): TasteScore {
        if (profile == null) return TasteScore.noProfile()

        // 高/低分偏好权重表：tag(小写) → 权重 = ln(1+标注次数) × 题材/元数据因子。
        val highWeights = profile.tagStats
            .filter { it.bucket == TagBucket.HIGH_SCORE }
            .associate { cleanLower(it.tagName) to weightOf(it.tagName, it.count) }
        val lowWeights = profile.tagStats
            .filter { it.bucket == TagBucket.LOW_SCORE }
            .associate { cleanLower(it.tagName) to weightOf(it.tagName, it.count) }

        val workTagNames = work.tags.map { it.name }
        // 清洗并统一为小写，避免格式差异导致相同标签无法匹配。
        val workTagsLower = workTagNames.map { cleanLower(it) }

        // 退化：作品无标签 或 画像无高/低分标签 → 用社区分相对你均分粗估。
        if (workTagsLower.isEmpty() || (highWeights.isEmpty() && lowWeights.isEmpty())) {
            return communityFallback(profile, communityScore10)
        }

        var posSum = 0f
        var negSum = 0f
        val matchedHigh = ArrayList<String>()
        val matchedLow = ArrayList<String>()
        for (i in workTagsLower.indices) {
            val t = workTagsLower[i]
            highWeights[t]?.let {
                posSum += it
                matchedHigh += workTagNames[i]
            }
            lowWeights[t]?.let {
                negSum += it
                matchedLow += workTagNames[i]
            }
        }

        // 软饱和聚合：少数强命中即可拿高分，避免被「标签多」的作品线性稀释（总体吻合度）。
        val coverage = posSum / (posSum + SATURATION_K)
        val negRatio = negSum / (negSum + SATURATION_K)
        // 纯个人口味分量（题材重合，不含社区）；较低基线和较高增益配合更小的
        // [SATURATION_K] 与下方对比度扩展，使「喜欢的高分番」明显拉高、口味不合的明显拉低。
        val personal = (0.28f + 0.74f * coverage - 0.62f * negRatio).coerceIn(0.02f, 1f)

        // 社区分 → 「相对你均分」的习惯调整（很低权重并入综合分）。
        val communityNorm = communityScore10?.let { (it / 10f).coerceIn(0f, 1f) }
        val userAvg = profile.avgScore.takeIf { it > 0f }
        val habitAdj = if (communityScore10 != null && userAvg != null) {
            ((communityScore10 - userAvg) / 10f).coerceIn(-0.15f, 0.15f)
        } else {
            0f
        }

        // 综合匹配度以个人口味为主（0.88），社区评分和观看习惯各占 0.06。
        var fraction = if (communityNorm != null) {
            (personal * 0.88f + communityNorm * 0.06f + (0.5f + habitAdj) * 0.06f).coerceIn(0.02f, 0.99f)
        } else {
            (personal * 0.96f + 0.02f).coerceIn(0.02f, 0.99f)
        }

        // 以中性值 0.5 为轴扩展两侧分数，避免结果过度集中。
        fraction = (0.5f + (fraction - 0.5f) * CONTRAST).coerceIn(0.02f, 0.99f)

        val lowConf = profile.confidence.coerceIn(0f, 1f) < LOW_CONFIDENCE_THRESHOLD
        if (lowConf) fraction = (0.5f + (fraction - 0.5f) * 0.6f).coerceIn(0.05f, 0.98f)

        return TasteScore(
            available = true,
            fraction = fraction,
            personal = personal,
            matchedHighTags = matchedHigh.distinct(),
            matchedLowTags = matchedLow.distinct(),
            lowConfidence = lowConf,
            basis = TasteScore.Basis.TAG_OVERLAP,
        )
    }

    /** 无标签时的低置信估计：社区分相对你均分的高低；连社区分都没有则数据不足。 */
    private fun communityFallback(profile: TasteProfile, community: Float?): TasteScore {
        if (community == null) return TasteScore.insufficient()
        val userAvg = profile.avgScore.takeIf { it > 0f }
        val fraction = if (userAvg != null) {
            (0.5f + (community - userAvg) / 10f).coerceIn(0.1f, 0.9f)
        } else {
            (community / 10f).coerceIn(0.1f, 0.9f)
        }
        val lowConf = profile.confidence.coerceIn(0f, 1f) < LOW_CONFIDENCE_THRESHOLD
        return TasteScore(
            available = true,
            fraction = fraction,
            // 无标签时「个人分」只能借社区相对分近似（略向中性收缩，避免误导推荐排序）。
            personal = (0.5f + (fraction - 0.5f) * 0.7f).coerceIn(0f, 1f),
            matchedHighTags = emptyList(),
            matchedLowTags = emptyList(),
            lowConfidence = lowConf,
            basis = TasteScore.Basis.COMMUNITY_FALLBACK,
        )
    }

    /** 单个偏好标签权重：标注次数对数（高分作品反复出现的标签更重要）× 题材/元数据因子。 */
    private fun weightOf(tag: String, count: Int): Float =
        ln(1.0 + count.coerceAtLeast(0)).toFloat() * TasteTagTaxonomy.weightFactor(tag)

    /** 规范化标签并转为小写，忽略下划线、连字符、空白和大小写差异。 */
    private fun cleanLower(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ").lowercase()

    companion object {
        /** 软饱和常数；数值越小，少量命中对分数的提升越明显。 */
        const val SATURATION_K: Float = 1.0f

        /** 对比度扩展系数；大于 1 时拉开高低匹配结果。 */
        const val CONTRAST: Float = 1.35f

        /** 画像置信低于此值视为低置信，分数向中性收缩。 */
        const val LOW_CONFIDENCE_THRESHOLD: Float = 0.3f
    }
}

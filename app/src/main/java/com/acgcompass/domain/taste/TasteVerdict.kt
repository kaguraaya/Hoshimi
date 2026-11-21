package com.acgcompass.domain.taste

/**
 * 把分数翻译成一句话。详情页、探索队列、推荐共用同一套措辞，避免各说各话。
 *
 * 主结论说的是口味匹配度（[TasteFit]：对你的口味有多契合）。预计评分是另一个输出，两者可以不一致：
 * 预计分低于及格线时，结论后面补一句「预计分不高」，而不是把口味结论改掉。
 * 阈值对应匹配度的刻度：72 ≈ 接近你看过的作品的平均水平，85 ≈ 接近你看过的前 10%，55 ≈ 作品库里中等偏上。
 * 题材相似度（[familiarityNote]）只作为补充说明，用文字不用数字。
 */
object TasteVerdict {
    /** 及格线：预计分低于它时，结论后面注明「预计分不高」。 */
    const val PASS_LINE = 6.0

    const val LIKELY_LOVE = 85
    const val LIKELY_GOOD = 72
    const val AVERAGE = 55

    private const val LEGACY_HIGH = 80
    private const val LEGACY_GOOD = 65
    private const val LEGACY_AVERAGE = 45

    private const val FAMILIAR = 65
    private const val NEUTRAL = 40

    /**
     * @param likeability 主分数（0–100）。有预计评分时见 [ComputeTasteMatchUseCase.headlineScore]；没有时是 12 维画像匹配分。
     * @param predictedRating 预计评分；评分太少建不了模型时为 null。
     * @param uncertain 预测区间很宽（[RatingPrediction.isUncertain]）：结论后面注明「信息不足」。
     */
    fun of(likeability: Int, predictedRating: Double?, uncertain: Boolean = false): String {
        val verdict = base(likeability, predictedRating)
        return if (uncertain && predictedRating != null) "$verdict（信息不足，仅供参考）" else verdict
    }

    private fun base(likeability: Int, predictedRating: Double?): String {
        if (predictedRating == null) {
            return when {
                likeability >= LEGACY_HIGH -> "与口味画像高度匹配"
                likeability >= LEGACY_GOOD -> "可能会喜欢"
                likeability >= LEGACY_AVERAGE -> "可能感觉一般"
                else -> "题材上不太对口"
            }
        }
        val taste = when {
            likeability >= LIKELY_LOVE -> "非常对你口味"
            likeability >= LIKELY_GOOD -> "对你口味"
            likeability >= AVERAGE -> "口味上一般"
            else -> "不太是你的口味"
        }
        return if (predictedRating < PASS_LINE) "$taste，但预计分不高" else taste
    }

    /** 题材相似度的文字说明（[similarity] 是 12 维画像的匹配分）。 */
    fun familiarityNote(similarity: Int): String = when {
        similarity >= FAMILIAR -> "这类题材你常看，也常给高分"
        similarity >= NEUTRAL -> "题材上没有明显倾向"
        else -> "这类题材你看得不多"
    }
}

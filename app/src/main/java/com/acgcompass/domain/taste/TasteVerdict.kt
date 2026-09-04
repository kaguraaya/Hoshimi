package com.acgcompass.domain.taste

/**
 * 把分数翻译成一句话。详情页、探索队列、推荐共用同一套措辞，避免各说各话。
 *
 * 主结论跟着「预计你会打几分」走：[likeability] 是这个预计分落在你自己打分历史里的位置
 * （超过你看过的百分之多少），所以它和预计评分永远同向，不会出现「匹配度很低却预计打高分」。
 * 题材相似度（[familiarityNote]）只作为补充说明，用文字不用数字。
 */
object TasteVerdict {
    /** 及格线：预计分低于它，无论排在你历史里的什么位置，都按「不太合口味」说。 */
    const val PASS_LINE = 6.0

    private const val LIKELY_LOVE = 75
    private const val LIKELY_GOOD = 50
    private const val AVERAGE = 25

    private const val LEGACY_HIGH = 80
    private const val LEGACY_GOOD = 65
    private const val LEGACY_AVERAGE = 45

    private const val FAMILIAR = 65
    private const val NEUTRAL = 40

    /**
     * @param likeability 主分数（0–100）。有预计评分时是它在你打分历史里的分位；没有时是旧的画像匹配分。
     * @param predictedRating 预计评分；评分太少建不了模型时为 null。
     */
    fun of(likeability: Int, predictedRating: Double?): String {
        if (predictedRating == null) {
            return when {
                likeability >= LEGACY_HIGH -> "与口味画像高度匹配"
                likeability >= LEGACY_GOOD -> "可能会喜欢"
                likeability >= LEGACY_AVERAGE -> "可能感觉一般"
                else -> "题材上不太对口"
            }
        }
        return when {
            predictedRating < PASS_LINE -> "可能不太合你口味"
            likeability >= LIKELY_LOVE -> "很可能喜欢"
            likeability >= LIKELY_GOOD -> "应该不错"
            likeability >= AVERAGE -> "中规中矩"
            else -> "对你来说可能偏一般"
        }
    }

    /** 题材相似度的文字说明（[similarity] 是 12 维画像的匹配分）。 */
    fun familiarityNote(similarity: Int): String = when {
        similarity >= FAMILIAR -> "这类题材你常看，也常给高分"
        similarity >= NEUTRAL -> "题材上没有明显倾向"
        else -> "这类题材你看得不多"
    }
}

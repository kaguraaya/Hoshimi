package com.acgcompass.feature.detail

import com.acgcompass.domain.model.Consensus
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import kotlin.math.roundToInt

/**
 * 评分区四个平台分组（固定顺序）：Bangumi、AniList、MAL·Tenrai、VNDB。
 * MAL·Tenrai 合并为一项：优先 MAL 官方，缺失则回退 Tenrai；旧 Jikan 缓存只用于迁移兼容。
 */
internal fun RatingAggregate?.toPlatformRatings(): List<PlatformRatingUiModel> {
    val perSource = this?.perSource ?: emptyMap()
    return listOf(
        platformRating("Bangumi", perSource.validEntry(SourceId.BANGUMI)),
        platformRating("AniList", perSource.validEntry(SourceId.ANILIST)),
        platformRating(
            "MAL·Tenrai",
            perSource.validEntry(SourceId.MAL)
                ?: perSource.validEntry(SourceId.TENRAI)
                ?: perSource.validEntry(SourceId.JIKAN),
        ),
        platformRating("VNDB", perSource.validEntry(SourceId.VNDB)),
    )
}

/** 取该源的有效评分；缺失 / 无效（0 / 负 / NaN / 越界）返回 `null`（不参与展示且不回填）。 */
internal fun Map<SourceId, RatingEntry?>.validEntry(source: SourceId): SourcedRating? {
    val entry = this[source] ?: return null
    if (!AggregateRatingsUseCase.isValidScore(source, entry.score)) return null
    return SourcedRating(source, entry)
}

internal fun platformRating(platform: String, rating: SourcedRating?): PlatformRatingUiModel = if (rating == null) {
    PlatformRatingUiModel(platform = platform, scoreText = null, voteText = null)
} else {
    PlatformRatingUiModel(
        platform = platform,
        scoreText = formatScore(rating.source, rating.entry.score),
        voteText = rating.entry.voteCount
            .takeIf { it > 0 }
            ?.let { "%,d 人评分".format(it) },
    )
}

/** 按来源量纲格式化分数：Bangumi/Jikan/MAL 为 x/10，AniList/VNDB 为 x/100。 */
internal fun formatScore(source: SourceId, score: Float): String = when (source) {
    SourceId.BANGUMI, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL -> "%.1f/10".format(score)
    SourceId.ANILIST, SourceId.VNDB -> "${score.roundToInt()}/100"
}

internal fun Consensus?.toConsensusUiModel(): ConsensusUiModel = if (this == null) {
    ConsensusUiModel.Insufficient
} else {
    ConsensusUiModel.Available(
        stability = metric(
            label = "评分稳定度",
            fraction = stability,
            high = "较稳定",
            mid = "一般",
            low = "分歧较大",
        ),
        controversy = metric(
            label = "争议程度",
            fraction = controversy,
            high = "争议很大",
            mid = "存在分歧",
            low = "争议较小",
        ),
        priority = metric(
            label = "补番优先级",
            fraction = priority,
            high = "建议优先",
            mid = "中等",
            low = "可往后排",
        ),
    )
}

internal fun metric(label: String, fraction: Float, high: String, mid: String, low: String): ConsensusMetric {
    val clamped = fraction.coerceIn(0f, 1f)
    val qualitative = when {
        clamped >= 0.66f -> high
        clamped >= 0.33f -> mid
        else -> low
    }
    return ConsensusMetric(
        label = label,
        percentText = "${(clamped * 100).roundToInt()}%",
        qualitativeText = qualitative,
        fraction = clamped,
    )
}

/** 内部：携带来源的有效评分，用于按源量纲格式化分数。 */
internal data class SourcedRating(val source: SourceId, val entry: RatingEntry)

package com.acgcompass.domain.taste

import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work

/**
 * 用作品自己的资料临时组一份口味特征。
 *
 * 用于没有 Bangumi 条目的作品：标签按排列顺序给递减权重代替标注人数，社区评分取其它源。
 * 结果比 Bangumi 特征粗；拿到 Bangumi 特征后会被替换。
 */
fun Work.toFallbackFeature(ratings: RatingAggregate?): WorkFeature {
    val community = ratings.communityTenPoint()
    return WorkFeature(
        subjectId = id,
        tagCounts = tags.map { it.name }.filter { it.isNotBlank() }.distinct()
            .mapIndexed { index, name -> TagCount(name, (TOP_WEIGHT - index * WEIGHT_STEP).coerceAtLeast(MIN_WEIGHT)) },
        bangumiScore = community?.first,
        bangumiVotes = community?.second ?: 0,
        eps = units.episodes ?: 0,
        durationMin = units.episodeMinutes ?: 0,
        mediaType = mediaType,
        titleVariants = listOfNotNull(titles.cn, titles.ja, titles.canonical, titles.romaji, titles.en)
            .plus(titles.aliases)
            .filter { it.isNotBlank() }
            .distinct(),
        completeness = WorkFeatureCompleteness.BASIC,
    )
}

/** 取一个 10 分制的社区评分与票数：优先 Bangumi，其次 MAL / Jikan，最后 AniList（百分制折算）。 */
private fun RatingAggregate?.communityTenPoint(): Pair<Float, Int>? {
    val perSource = this?.perSource.orEmpty()
    val tenPoint = listOf(SourceId.BANGUMI, SourceId.MAL, SourceId.JIKAN)
        .firstNotNullOfOrNull { source -> perSource[source]?.takeIf { it.score > 0f } }
        ?.let { it.score to it.voteCount }
    val aniList = perSource[SourceId.ANILIST]
        ?.takeIf { it.score > 0f }
        ?.let { it.score / ANILIST_SCALE to it.voteCount }
    return tenPoint ?: aniList
}

private const val TOP_WEIGHT = 100
private const val WEIGHT_STEP = 8
private const val MIN_WEIGHT = 12
private const val ANILIST_SCALE = 10f

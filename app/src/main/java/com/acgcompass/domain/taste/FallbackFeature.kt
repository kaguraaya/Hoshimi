package com.acgcompass.domain.taste

import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work

/**
 * 用作品自己的资料临时组一份口味特征。
 *
 * 口味引擎的特征平时来自 Bangumi 条目（带标注人数的标签、票数）。作品没有关联到 Bangumi 条目时
 * （只在 AniList / MAL 等其它源出现），否则就永远算不出匹配度、界面停在「正在获取标签」。
 * 这里用作品已有的标签（按排列顺序给一个递减的权重代替标注人数）、其它源的社区评分和集数，
 * 结果会比 Bangumi 特征粗一些，但不会空着；之后若拿到了 Bangumi 特征，会自动换成更准的结果。
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

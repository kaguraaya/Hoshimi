package com.acgcompass.feature.home

import com.acgcompass.domain.model.Work
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import kotlin.random.Random

/**
 * 按最近看过 / 在看、评分不低的作品推荐「今晚想看什么类型」的标签。
 *
 * 越近、评分越高的作品权重越大；从权重最高的一批里按权重随机抽 [count] 个，每次打开首页都会换一些。
 * 记录太少时退回 [HomeMood.DEFAULTS]。
 */
internal fun buildMoodSuggestions(
    collections: List<MoodSignal>,
    worksById: Map<String, Work>,
    random: Random,
    count: Int = MOOD_COUNT,
): List<HomeMood> {
    val recent = collections.asSequence()
        .filter { it.status == "看过" || it.status == "在看" }
        .filter { (it.rating ?: MIN_RATING) >= MIN_RATING }
        .sortedByDescending { it.updatedAt }
        .take(RECENT_WINDOW)
        .toList()
    val weights = HashMap<String, Double>()
    recent.forEachIndexed { rank, entry ->
        val tags = worksById[entry.workId]?.tags.orEmpty()
            .map { it.name }
            .filter(TasteTagTaxonomy::isSelectableGenre)
            .take(TAGS_PER_WORK)
        val recency = 1.0 / (1.0 + rank / RECENCY_HALF_RANK)
        val liking = if ((entry.rating ?: 0) >= HIGH_RATING) HIGH_RATING_BONUS else 1.0
        tags.forEachIndexed { position, tag ->
            val prominence = 1.0 - position * PROMINENCE_STEP
            weights[tag] = (weights[tag] ?: 0.0) + recency * liking * prominence
        }
    }
    val pool = weights.entries.sortedByDescending { it.value }.take(count * POOL_FACTOR)
    if (pool.size < MIN_TAGS) return HomeMood.DEFAULTS
    return weightedSample(pool.map { it.key to it.value }, count, random)
        .map { tag -> HomeMood(label = tag, action = "tag:$tag", presetTags = listOf(tag)) }
}

/** 一条收藏记录里推荐类型用得到的部分。 */
internal data class MoodSignal(val workId: String, val status: String?, val rating: Int?, val updatedAt: Long)

/** 不放回的加权随机抽样。 */
private fun weightedSample(entries: List<Pair<String, Double>>, count: Int, random: Random): List<String> {
    val remaining = entries.toMutableList()
    val picked = mutableListOf<String>()
    while (picked.size < count && remaining.isNotEmpty()) {
        var roll = random.nextDouble() * remaining.sumOf { it.second }
        val index = remaining.indexOfFirst { entry ->
            roll -= entry.second
            roll <= 0.0
        }
            .takeIf { it >= 0 } ?: remaining.lastIndex
        picked += remaining.removeAt(index).first
    }
    return picked
}

private const val MOOD_COUNT = 8
private const val POOL_FACTOR = 2
private const val MIN_TAGS = 4
private const val RECENT_WINDOW = 40
private const val TAGS_PER_WORK = 8
private const val MIN_RATING = 6
private const val HIGH_RATING = 8
private const val HIGH_RATING_BONUS = 1.5
private const val RECENCY_HALF_RANK = 10.0
private const val PROMINENCE_STEP = 0.08

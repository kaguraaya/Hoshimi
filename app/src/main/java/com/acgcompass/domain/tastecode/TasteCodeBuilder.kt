package com.acgcompass.domain.tastecode

import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TasteCategory
import kotlin.math.abs
import kotlin.math.sqrt

/** 生成口味码用的一部作品：Bangumi 条目、我的评分、社区评分、年份、评分时间。 */
data class TasteCodeWork(
    val subjectId: Int,
    val rating: Int?,
    val communityScore: Double?,
    val year: Int?,
    val ratedAt: Long,
)

/**
 * 从口味画像和收藏生成口味码。代表作按三组挑选（评分最高 15 部、和大众分歧最大 10 部、最近评分 5 部，不重复），
 * 生成页默认全部带上，用户可以逐部去掉（[TasteCode.works] 由调用方按用户的选择过滤）。
 */
object TasteCodeBuilder {
    private const val TOP_RATED = 15
    private const val MOST_DIVERGENT = 10
    private const val RECENT = 5
    private const val DIVERGENCE_PER_SIDE = 5
    private const val DIMENSION_TOP_TAGS = 5

    /** 进口味码的标签分类：时间标签和梗只反映时代或社区语气，不拿来对比。 */
    private val TAG_CATEGORIES = setOf(
        TasteCategory.TOPIC,
        TasteCategory.COMBO,
        TasteCategory.DEVICE,
        TasteCategory.SOURCE,
        TasteCategory.XP,
        TasteCategory.CHARACTER,
        TasteCategory.STAFF,
        TasteCategory.CV,
    )

    /** 各分组占标签名额的比例。 */
    @Suppress("MagicNumber")
    private val GROUP_SHARES = mapOf(TagGroup.GENRE to 0.5, TagGroup.CHARACTER to 0.25, TagGroup.CREATOR to 0.25)

    /** 年代分档的起始年份，与 [TasteCode.ERA_LABELS] 对应。 */
    @Suppress("MagicNumber")
    private val ERA_STARTS = intArrayOf(1990, 2000, 2005, 2010, 2015, 2020, 2025)

    fun build(
        profile: AdvancedTasteProfile,
        works: List<TasteCodeWork>,
        today: Long,
        nickname: String? = null,
    ): TasteCode {
        val rated = works.filter { it.rating != null && it.rating > 0 }
        return TasteCode(
            generatedOnDay = today,
            algorithmVersion = profile.algorithmVersion,
            nickname = nickname?.trim()?.take(TasteCode.MAX_NICKNAME)?.takeIf { it.isNotEmpty() },
            style = style(profile, rated),
            dimensions = dimensions(profile),
            liked = topTags(profile, positive = true, limit = TasteCode.MAX_LIKED),
            avoided = topTags(profile, positive = false, limit = TasteCode.MAX_AVOIDED),
            divergence = divergence(profile),
            eraShares = eraShares(works),
            works = representativeWorks(rated),
        )
    }

    /** 默认入选的代表作（生成页的初始勾选）。 */
    fun representativeWorks(rated: List<TasteCodeWork>): List<RepresentativeWork> {
        val chosen = LinkedHashMap<Int, RepresentativeWork>()
        fun take(candidates: List<TasteCodeWork>, limit: Int, reason: RepresentativeReason) {
            candidates.asSequence()
                .filter { it.subjectId !in chosen }
                .take(limit)
                .forEach { chosen[it.subjectId] = RepresentativeWork(it.subjectId, it.rating ?: 0, reason) }
        }
        take(
            rated.sortedWith(
                compareByDescending<TasteCodeWork> { it.rating }.thenByDescending { it.communityScore ?: 0.0 },
            ),
            TOP_RATED,
            RepresentativeReason.TOP_RATED,
        )
        take(
            rated.filter { it.communityScore != null }.sortedByDescending {
                abs(
                    (it.rating ?: 0) - (it.communityScore ?: 0.0),
                )
            },
            MOST_DIVERGENT,
            RepresentativeReason.MOST_DIVERGENT,
        )
        take(rated.sortedByDescending { it.ratedAt }, RECENT, RepresentativeReason.RECENT)
        return chosen.values.toList()
    }

    private fun style(profile: AdvancedTasteProfile, rated: List<TasteCodeWork>): ScoringStyle {
        val ratings = rated.mapNotNull { it.rating?.toDouble() }
        val mean = ratings.average().takeIf { !it.isNaN() } ?: profile.userAvgRating
        val spread = if (ratings.size > 1) {
            sqrt(
                ratings.sumOf { (it - mean) * (it - mean) } / (ratings.size - 1),
            )
        } else {
            0.0
        }
        val model = profile.divergence
        val withCommunity = rated.filter { it.communityScore != null }
        val leniency = model?.leniency
            ?: withCommunity.map { (it.rating ?: 0) - (it.communityScore ?: 0.0) }.average().takeIf { !it.isNaN() }
            ?: 0.0
        return ScoringStyle(
            ratedCount = ratings.size,
            mean = mean.toFloat(),
            spread = spread.toFloat(),
            leniency = leniency.toFloat(),
            followsCrowd = (model?.slope ?: 1.0).toFloat(),
        )
    }

    /**
     * 各维度对这个人的影响：维度在匹配度里的默认权重 × 该维度最强几个正向标签的平均强度，按最大值归一。
     * 不用整个维度的权重总和：staff、声优这类标签数量多的维度总和天然偏大，会把形状拉歪。
     */
    private fun dimensions(profile: AdvancedTasteProfile): Map<String, Float> {
        val strengths = TasteCategory.entries
            .filter { it != TasteCategory.NOISE }
            .associateWith { category ->
                val top = profile.category(category).positive.values
                    .map { abs(it) }
                    .sortedDescending()
                    .take(DIMENSION_TOP_TAGS)
                if (top.isEmpty()) 0.0 else category.defaultWeight * top.average()
            }
        val max = strengths.values.maxOrNull()?.takeIf { it > 0.0 } ?: return emptyMap()
        return strengths.mapKeys { it.key.key }.mapValues { (it.value / max).toFloat() }
    }

    /**
     * 喜欢 / 回避的标签：题材、角色属性、创作者分组配额（喜欢 12 / 6 / 6，回避 6 / 3 / 3），某组不够时由其它组补，
     * 避免声优和制作人员挤掉题材。时间标签和梗不算。强度按全部入选标签里的最大值归一。
     */
    private fun topTags(profile: AdvancedTasteProfile, positive: Boolean, limit: Int): List<WeightedTag> {
        val candidates = profile.categories
            .filterKeys { it in TAG_CATEGORIES }
            .flatMap { (category, pref) ->
                (if (positive) pref.positive else pref.negative).map { (tag, weight) ->
                    WeightedTag(tag, abs(weight).toFloat(), category.key)
                }
            }
            .groupBy { it.tag }
            .map { (_, same) -> same.maxBy { it.weight }.copy(weight = same.sumOf { it.weight.toDouble() }.toFloat()) }
            .sortedByDescending { it.weight }
        val quotas = TagGroup.entries.associateWith { group -> (limit * (GROUP_SHARES[group] ?: 0.0)).toInt() }
        val chosen = LinkedHashSet<WeightedTag>()
        TagGroup.entries.forEach { group ->
            candidates.filter { TagGroup.of(it.category) == group }.take(quotas.getValue(group)).forEach(chosen::add)
        }
        candidates.asSequence().filter { it !in chosen }.take(limit - chosen.size).forEach(chosen::add)
        val top = chosen.sortedByDescending { it.weight }
        val max = top.firstOrNull()?.weight?.takeIf { it > 0f } ?: return emptyList()
        return top.map { it.copy(weight = it.weight / max) }
    }

    private fun divergence(profile: AdvancedTasteProfile): List<WeightedTag> {
        val (liked, disliked) = profile.divergence?.strongestTags(DIVERGENCE_PER_SIDE) ?: return emptyList()
        return (liked + disliked).map { WeightedTag(it.label, it.delta.toFloat()) }
    }

    private fun eraShares(works: List<TasteCodeWork>): List<Float> {
        val years = works.mapNotNull { it.year }
        if (years.isEmpty()) return emptyList()
        val counts = IntArray(ERA_STARTS.size + 1)
        years.forEach { year -> counts[ERA_STARTS.count { year >= it }] += 1 }
        return counts.map { it.toFloat() / years.size }
    }
}

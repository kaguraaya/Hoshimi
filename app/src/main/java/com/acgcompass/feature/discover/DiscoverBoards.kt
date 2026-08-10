package com.acgcompass.feature.discover

import androidx.annotation.VisibleForTesting
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.usecase.TasteTagTaxonomy

/**
 * 发现页榜单 / 评分差异榜 / 高级筛选的
 * **纯 Kotlin** 表现层逻辑。无 Android / IO 依赖，可独立单元测试与预览。
 *
 * 设计取向（与全局一致）：
 * - **缺失即标记、绝不伪造**：当前未接线实时榜单端点，榜单以本地已聚合
 *   评分排序兜底并**明确标注数据来源**；某源无数据时整块以「暂无数据」占位，而不是编造名次。
 * - **中性措辞**：评分差异榜只计算并展示「评分差距」这一客观数值，配中性说明
 *   「可能存在圈层口味差异」，不下任何「谁对谁错 / 哪个圈层更好」的结论。
 * - **可扩展**：[RankingBoard] / [ScoreDiffItem] 的构建函数以 [WorkRatings] 为输入，未来接入实时
 *   榜单端点后，只需替换 [WorkRatings] 的数据来源即可填充，无需改动 UI。
 */

/** 发现页的内容分区（顶部 Tab 切换）。搜索分区行为由 实现，保持不变。 */
enum class DiscoverTab(val label: String) {
    /** 搜索。 */
    SEARCH("搜索"),

    /** 各源榜单。 */
    RANKING("榜单"),

    /** 评分差异榜。 */
    SCORE_DIFF("评分差异"),

    /** 高级筛选。 */
    FILTER("筛选"),
}

/**
 * 一部作品 + 其多源评分聚合的组合（表现层中转模型）。
 *
 * [ratings] 为 `null` 表示评分尚不可得（UI 显示「暂无数据」，不伪造）。榜单 / 评分差异 / 筛选
 * 三类视图都以 [WorkRatings] 列表为统一输入，便于未来替换为实时数据。
 */
data class WorkRatings(
    val work: Work,
    val ratings: RatingAggregate? = null,
)

// region 多源榜单

/** 明确要求分别展示的四个榜单来源（VNDB 不在本条要求内）。 */
internal val RANKING_SOURCES: List<SourceId> = listOf(
    SourceId.BANGUMI,
    SourceId.ANILIST,
    SourceId.TENRAI,
    SourceId.MAL,
)

/** 单个榜单展示条数上限，避免列表过长。 */
internal const val RANKING_LIMIT: Int = 20

/**
 * 一个数据源的榜单。
 *
 * @property source 数据源标识（用于来源标记，绝不混淆来源）。
 * @property sourceLabel 数据源展示文案（如「Bangumi」）。
 * @property items 该源下按评分降序排列的作品卡片；为空表示该源暂无可用数据。
 * @property isPlaceholder 是否为占位（无实时 / 本地数据）：为 `true` 时界面显示「暂无数据」。
 */
data class RankingBoard(
    val source: SourceId,
    val sourceLabel: String,
    val items: List<RankedWork>,
    val isPlaceholder: Boolean,
)

/** 榜单单条；[workId] 必须是可用于详情导航的稳定作品标识。 */
data class RankedWork(
    val workId: String,
    val card: WorkCardUiModel,
)

/** 榜单时间范围子分类（总榜 / 今年 / 本季），用于 Bangumi 真实排行。 */
enum class RankingScope(val label: String) {
    OVERALL("总榜"),
    YEAR("今年"),
    SEASON("本季"),
}

/** 把 Bangumi 真实排行的作品 + 评分折叠为榜单卡片（带真实评分；缺失则不显示评分）。 */
fun rankedCardOf(work: Work, entry: RatingEntry?): RankedWork {
    val altTitle = work.titles.ja ?: work.titles.romaji ?: work.titles.en
    val subtitle = listOfNotNull(altTitle, work.year?.toString()).joinToString(" · ")
    val ratingText = entry?.takeIf { it.score > 0f }?.let {
        "Bangumi ${"%.1f".format(it.score)} · ${it.voteCount} 人"
    }
    return RankedWork(
        workId = work.id,
        card = WorkCardUiModel(
            coverUrl = work.coverUrl,
            title = work.displayTitle(),
            subtitle = subtitle,
            type = when (work.mediaType) {
                MediaType.ANIME -> "动画"
                MediaType.MANGA -> "漫画"
                MediaType.NOVEL -> "小说"
                MediaType.GAME -> "游戏"
                MediaType.VN -> "视觉小说"
                MediaType.OTHER -> "其他"
            },
            ratingText = ratingText,
            sourceTags = listOf("Bangumi"),
        ),
    )
}

/**
 * 由本地已聚合评分构建各源榜单。每个源只纳入「在该源拥有评分」的作品，按该源评分
 * 降序排列，并在卡片上明确标注来源。某源无任何作品有评分时，该榜单 [RankingBoard.isPlaceholder]
 * 置 `true`，由界面渲染「暂无数据」占位（不伪造名次）。
 */
@VisibleForTesting
internal fun buildRankingBoards(works: List<WorkRatings>): List<RankingBoard> = RANKING_SOURCES.map { source ->
    val ranked = works
        .mapNotNull { wr ->
            val entry = wr.ratings?.perSource?.get(source) ?: return@mapNotNull null
            wr to entry
        }
        .sortedByDescending { (_, entry) -> entry.score }
        .take(RANKING_LIMIT)
        .map { (wr, entry) -> RankedWork(wr.work.id, wr.work.toRankingCard(source, entry)) }

    RankingBoard(
        source = source,
        sourceLabel = source.discoverLabel(),
        items = ranked,
        isPlaceholder = ranked.isEmpty(),
    )
}

// endregion

// region 评分差异榜

/** 判定「评分差距大」的归一化阈值（0–10 标度）。差距达到该值才进入评分差异榜。 */
internal const val SCORE_DIFF_THRESHOLD: Float = 1.5f

/** 中性说明文案：只提示可能的口味差异，不下结论。 */
internal const val SCORE_DIFF_NEUTRAL_NOTE: String =
    "不同平台评分差距较大，可能存在圈层口味差异，分数仅供参考，请结合自身喜好判断。"

/**
 * 评分差异榜的一条。
 *
 * @property workId 规范化作品 id（点击进入详情）。
 * @property card 作品卡片展示模型。
 * @property spread 归一化（0–10 标度）后的最大评分差距。
 * @property spreadLabel 评分差距展示文案（如「评分差距 2.3」）。
 * @property perSourceLabels 各源评分明细（如「Bangumi 8.1 / AniList 6.2」），用于中性呈现差异来源。
 * @property divergenceLevel 分歧程度分级（按归一化差距），用于语义化徽标。
 * @property sourceScores 各源归一化评分（0–10），用于分布条可视化。
 */
data class ScoreDiffItem(
    val workId: String,
    val card: WorkCardUiModel,
    val spread: Float,
    val spreadLabel: String,
    val perSourceLabels: List<String>,
    val divergenceLevel: DivergenceLevel,
    val sourceScores: List<SourceScore>,
)

/** 评分差异榜中单个来源的归一化评分（0–10 标度），用于分布条可视化。 */
data class SourceScore(
    val label: String,
    val score: Float,
)

/**
 * 评分分歧程度分级（按归一化差距 [scoreSpread]），用于评分差异栏的语义化徽标。
 * 措辞中性，仅描述客观差距大小，不对作品质量/口味下结论。
 */
enum class DivergenceLevel(val label: String) {
    /** 轻微分歧：达到入榜阈值但差距较小。 */
    SLIGHT("轻微分歧"),

    /** 明显分歧。 */
    NOTABLE("明显分歧"),

    /** 显著分歧：差距很大，最值得留意圈层口味差异。 */
    STRONG("显著分歧"),
    ;

    companion object {
        /** 由归一化评分差距映射分歧等级。 */
        fun of(spread: Float): DivergenceLevel = when {
            spread >= 3.5f -> STRONG
            spread >= 2.5f -> NOTABLE
            else -> SLIGHT
        }
    }
}

/**
 * 把各源原始评分线性归一到统一的 0–10 标度，使跨社区评分可比较。
 *
 * 仅做与各源公开标度一致的线性缩放，**不改变**相对大小、不臆造数据：
 * - Bangumi / MAL / Jikan：原生 0–10。
 * - AniList / VNDB：原生 0–100，整体除以 10。
 */
@VisibleForTesting
internal fun normalizeScore(source: SourceId, rawScore: Float): Float = when (source) {
    SourceId.ANILIST, SourceId.VNDB -> rawScore / 10f
    SourceId.BANGUMI, SourceId.MAL, SourceId.TENRAI, SourceId.JIKAN -> rawScore
}.coerceIn(0f, 10f)

/**
 * 计算一部作品跨源评分的**差距**—— 纯函数。
 *
 * 先把各有效源评分归一到 0–10 标度，再取「最高 − 最低」。当有效源少于 2 个时无法构成差异，
 * 返回 `null`（不伪造差距）。
 *
 * @param perSource 多源评分映射；值为 `null` 表示该源缺失，计入忽略。
 * @return 归一化评分差距（≥ 0）；有效源不足 2 个时为 `null`。
 */
@VisibleForTesting
internal fun scoreSpread(perSource: Map<SourceId, RatingEntry?>): Float? {
    val normalized = perSource.entries.mapNotNull { (source, entry) ->
        entry?.let { normalizeScore(source, it.score) }
    }
    if (normalized.size < 2) return null
    return normalized.max() - normalized.min()
}

/**
 * 把「同一部番的多源未合并行」聚为一簇去重——修评分差异榜里同番重复（例：中文名一条 + 罗马音名一条，
 * 且可能因交叉验证挂上同一 Jikan 分）。聚类键取规范化后的标题变体，
 * 长度≥4 去噪）的**交集**，跨语言也能连上（Bangumi 别名常含罗马音 / 日文）；每簇仅保留 Bangumi / 中文代表
 * （来源优先级 + 评分源更全者），去重后仍以**中文名**呈现。不臆造合并分值（避免误并不同季导致分数错配）。
 */
private fun dedupeSameWork(works: List<WorkRatings>): List<WorkRatings> {
    if (works.size < 2) return works
    val parent = IntArray(works.size) { it }
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var cur = x
        while (parent[cur] != cur) {
            val next = parent[cur]
            parent[cur] = root
            cur = next
        }
        return root
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
    val keyToIndex = HashMap<String, Int>()
    works.forEachIndexed { i, wr ->
        for (key in titleVariantKeys(wr.work)) {
            val prev = keyToIndex[key]
            if (prev == null) keyToIndex[key] = i else union(prev, i)
        }
    }
    val clusters = LinkedHashMap<Int, MutableList<WorkRatings>>()
    works.forEachIndexed { i, wr -> clusters.getOrPut(find(i)) { mutableListOf() }.add(wr) }
    return clusters.values.map { group ->
        group.minByOrNull { scoreDiffSourceRank(it.work.primarySource) * 1000 - (it.ratings?.perSource?.size ?: 0) }
            ?: group.first()
    }
}

/** 作品标题的归一化变体键集合（跨语言同番聚类用）；过滤过短噪声键，避免误并。 */
private fun titleVariantKeys(work: Work): Set<String> {
    val t = work.titles
    return (listOfNotNull(t.canonical, t.cn, t.ja, t.romaji, t.en) + t.aliases)
        .asSequence()
        .map { com.acgcompass.domain.matching.normalizeCompact(it) }
        .filter { it.length >= 4 }
        .toSet()
}

/** 评分差异去重的来源优先级（越小越优先作代表）：Bangumi > AniList > MAL > Jikan > VNDB。 */
private fun scoreDiffSourceRank(source: SourceId): Int = when (source) {
    SourceId.BANGUMI -> 0
    SourceId.ANILIST -> 1
    SourceId.MAL -> 2
    SourceId.TENRAI -> 3
    SourceId.JIKAN -> 4
    SourceId.VNDB -> 5
}

/**
 * 构建评分差异榜：保留评分差距 ≥ [SCORE_DIFF_THRESHOLD] 的作品，按差距降序排列。
 * 仅展示客观差距数值与来源明细，措辞见 [SCORE_DIFF_NEUTRAL_NOTE]；展示前按同作聚类去重。
 */
@VisibleForTesting
internal fun buildScoreDiffBoard(works: List<WorkRatings>): List<ScoreDiffItem> =
    dedupeSameWork(works).mapNotNull { wr ->
        val ratings = wr.ratings ?: return@mapNotNull null
        val spread = scoreSpread(ratings.perSource) ?: return@mapNotNull null
        if (spread < SCORE_DIFF_THRESHOLD) return@mapNotNull null
        // 各源归一化评分（0–10），供分布条与文字明细复用，保证两者口径一致。
        val sourceScores = ratings.perSource.entries.mapNotNull { (source, entry) ->
            entry?.let { SourceScore(source.discoverLabel(), normalizeScore(source, it.score)) }
        }
        ScoreDiffItem(
            workId = wr.work.id,
            card = wr.toFilteredCard(),
            spread = spread,
            spreadLabel = "评分差距 ${formatScore(spread)}",
            perSourceLabels = sourceScores.map { "${it.label} ${formatScore(it.score)}" },
            divergenceLevel = DivergenceLevel.of(spread),
            sourceScores = sourceScores,
        )
    }.sortedByDescending { it.spread }

// endregion

// region 高级筛选

/** 完结状态三态筛选（「完结状态」）。 */
enum class FinishedFilter {
    /** 不限。 */
    ALL,

    /** 仅完结（[ReleaseStatus.FINISHED]）。 */
    FINISHED,

    /** 仅未完结（连载 / 未发布 / 搁置等）。 */
    ONGOING,
}

/**
 * 发现页高级筛选条件——不可变数据类，多选维度以集合表示，空集合表示该维度「不限」。
 *
 * 维度对应验收标准 ：类型 / 状态 / 篇幅 / 评分 / 年份 / 完结状态 / 来源平台 / 风险标签 / 心情标签。
 *
 * @property types 媒介类型多选（动画 / 漫画 / …）。
 * @property statuses 发行状态多选。
 * @property lengths 篇幅多选（[TagCategory.LENGTH] 标签名）。
 * @property minRating 最低评分（归一化 0–10 标度）；`null` 表示不限。
 * @property years 年份多选；空表示不限。
 * @property finished 完结状态三态。
 * @property sources 来源平台多选（命中作品主源或任一有评分的源）。
 * @property riskTags 风险标签多选（[TagCategory.RISK] 标签名）。
 * @property moodTags 心情标签多选（[TagCategory.MOOD] 标签名）。
 */
data class DiscoverFilter(
    val types: Set<MediaType> = emptySet(),
    val statuses: Set<ReleaseStatus> = emptySet(),
    val lengths: Set<String> = emptySet(),
    val minRating: Float? = null,
    val years: Set<Int> = emptySet(),
    val finished: FinishedFilter = FinishedFilter.ALL,
    val sources: Set<SourceId> = emptySet(),
    val riskTags: Set<String> = emptySet(),
    val moodTags: Set<String> = emptySet(),
    /** 题材 / 主题（来自社区 [TagCategory.CONTENT] 标签，如 热血 / 校园 / 恋爱）。 */
    val genres: Set<String> = emptySet(),
) {
    /** 是否未设置任何筛选条件（用于「重置」按钮可见性与空筛选直通）。 */
    val isEmpty: Boolean
        get() = types.isEmpty() && statuses.isEmpty() && lengths.isEmpty() &&
            minRating == null && years.isEmpty() && finished == FinishedFilter.ALL &&
            sources.isEmpty() && riskTags.isEmpty() && moodTags.isEmpty() && genres.isEmpty()

    /** 已激活的筛选维度数量（用于折叠标题展示「已选 N 项」）。 */
    fun activeDimensionCount(): Int = listOf(
        types.isNotEmpty(),
        statuses.isNotEmpty(),
        lengths.isNotEmpty(),
        minRating != null,
        years.isNotEmpty(),
        finished != FinishedFilter.ALL,
        sources.isNotEmpty(),
        riskTags.isNotEmpty(),
        moodTags.isNotEmpty(),
        genres.isNotEmpty(),
    ).count { it }
}

/**
 * 可供选择的筛选取值（由当前本地作品集合归纳，避免展示无意义的空选项）。媒介类型 / 状态 / 来源
 * 等固定枚举由界面直接取枚举全集，这里只归纳数据驱动的维度。
 */
data class FilterFacets(
    val years: List<Int> = emptyList(),
    val lengths: List<String> = emptyList(),
    val riskTags: List<String> = emptyList(),
    val moodTags: List<String> = emptyList(),
    /** 可选题材（来自社区 [TagCategory.CONTENT] 标签，叠加常见题材兜底）。 */
    val genres: List<String> = emptyList(),
)

/** 常见传统题材兜底列表（即便本地数据未覆盖也提供，便于按题材筛选）。 */
internal val COMMON_GENRES: List<String> = listOf(
    "热血", "校园", "恋爱", "日常", "搞笑", "治愈", "催泪", "科幻", "奇幻",
    "悬疑", "推理", "运动", "音乐", "战斗", "冒险", "后宫", "百合", "机战", "历史", "美食",
)

/** 从本地作品集合归纳可选筛选取值（年份降序，标签按出现去重）。 */
@VisibleForTesting
internal fun buildFilterFacets(works: List<WorkRatings>): FilterFacets {
    val years = works.mapNotNull { it.work.year }.distinct().sortedDescending()
    val lengths = works.tagNames(TagCategory.LENGTH)
    val risks = works.tagNames(TagCategory.RISK)
    val moods = works.tagNames(TagCategory.MOOD)
    // 题材集合由常见题材兜底值与本地社区题材标签合并；本地标签只保留题材白名单
    // （[TasteTagTaxonomy.isSelectableGenre]），过滤掉已存储的人物名、声优、梗和时间等噪声。
    val localGenres = works.tagNames(TagCategory.CONTENT).filter { TasteTagTaxonomy.isSelectableGenre(it) }
    val genres = (COMMON_GENRES + localGenres).distinct()
    return FilterFacets(
        years = years,
        lengths = lengths,
        riskTags = risks,
        moodTags = moods,
        genres = genres,
    )
}

private fun List<WorkRatings>.tagNames(category: TagCategory): List<String> =
    flatMap { it.work.tags }.filter { it.category == category }.map { it.name }.distinct().sorted()

/**
 * 应用高级筛选——纯函数。每个维度独立做「与」判定；空集合 / `null` / [FinishedFilter.ALL]
 * 表示该维度不参与过滤。全空筛选时原样返回。
 */
@VisibleForTesting
internal fun applyFilter(works: List<WorkRatings>, filter: DiscoverFilter): List<WorkRatings> {
    if (filter.isEmpty) return works
    return works.filter { wr -> wr.matches(filter) }
}

private fun WorkRatings.matches(filter: DiscoverFilter): Boolean {
    val w = work

    if (filter.types.isNotEmpty() && w.mediaType !in filter.types) return false
    if (filter.statuses.isNotEmpty() && w.status !in filter.statuses) return false

    if (filter.lengths.isNotEmpty() && !w.hasAnyTag(TagCategory.LENGTH, filter.lengths)) return false
    if (filter.riskTags.isNotEmpty() && !w.hasAnyTag(TagCategory.RISK, filter.riskTags)) return false
    if (filter.moodTags.isNotEmpty() && !w.hasAnyTag(TagCategory.MOOD, filter.moodTags)) return false
    // 题材匹配——社区 CONTENT 标签或心情标签命中其一即可（题材词可能落在任一类别）。
    if (filter.genres.isNotEmpty() &&
        !w.hasAnyTag(TagCategory.CONTENT, filter.genres) &&
        !w.hasAnyTag(TagCategory.MOOD, filter.genres)
    ) {
        return false
    }

    if (filter.years.isNotEmpty() && (w.year == null || w.year !in filter.years)) return false

    when (filter.finished) {
        FinishedFilter.ALL -> Unit
        FinishedFilter.FINISHED -> if (w.status != ReleaseStatus.FINISHED) return false
        FinishedFilter.ONGOING -> if (w.status == ReleaseStatus.FINISHED) return false
    }

    if (filter.sources.isNotEmpty()) {
        val workSources = buildSet {
            add(w.primarySource)
            ratings?.perSource?.forEach { (source, entry) -> if (entry != null) add(source) }
        }
        if (filter.sources.none { it in workSources }) return false
    }

    if (filter.minRating != null) {
        val best = bestNormalizedScore() ?: return false
        if (best < filter.minRating) return false
    }

    return true
}

/** 该作品跨源的最高归一化评分；无任何评分时返回 `null`。 */
private fun WorkRatings.bestNormalizedScore(): Float? = ratings?.perSource?.entries
    ?.mapNotNull { (source, entry) -> entry?.let { normalizeScore(source, it.score) } }
    ?.maxOrNull()

private fun Work.hasAnyTag(category: TagCategory, names: Set<String>): Boolean =
    tags.any { it.category == category && it.name in names }

// endregion

// region 共享映射与格式化（纯函数）

/** 把 [WorkRatings] 折叠为通用作品卡片（用于评分差异榜与筛选结果）。 */
@VisibleForTesting
internal fun WorkRatings.toFilteredCard(): WorkCardUiModel {
    // 评分来源统一优先级（Bangumi 优先），避免每张卡显示不同源造成「乱」。
    val preferred = preferredSourceScore(ratings)
    return WorkCardUiModel(
        coverUrl = work.coverUrl,
        title = work.displayTitle(),
        subtitle = work.subtitleText(),
        type = work.mediaType.boardLabel(),
        // 缺失即「暂无数据」：无任何源评分时 ratingText 为 null。
        ratingText = preferred?.let { "${it.first.discoverLabel()} ${formatScore(it.second)}" },
        sourceTags = work.activeSourceLabels(ratings),
        completionCost = work.completionCost?.boardLabel(),
        moodRiskTags = work.tags
            .filter { it.category == TagCategory.MOOD || it.category == TagCategory.RISK }
            .map { it.name },
    )
}

/** 榜单卡片：标注所属源与该源评分。 */
private fun Work.toRankingCard(source: SourceId, entry: RatingEntry): WorkCardUiModel = WorkCardUiModel(
    coverUrl = coverUrl,
    title = displayTitle(),
    subtitle = subtitleText(),
    type = mediaType.boardLabel(),
    ratingText = "${source.discoverLabel()} ${formatScore(entry.score)} · ${entry.voteCount} 人",
    sourceTags = listOf(source.discoverLabel()),
    completionCost = completionCost?.boardLabel(),
)

/**
 * 按统一来源优先级 Bangumi→AniList→Jikan→MAL→VNDB 取第一个有评分的源（归一化 0–10 分）。
 * 同一榜单保持评分来源一致；无任何有效评分时返回 `null`。
 */
private fun preferredSourceScore(ratings: RatingAggregate?): Pair<SourceId, Float>? {
    val per = ratings?.perSource ?: return null
    val order = listOf(SourceId.BANGUMI, SourceId.ANILIST, SourceId.TENRAI, SourceId.MAL, SourceId.VNDB)
    for (src in order) {
        val entry = per[src] ?: continue
        if (entry.score > 0f) return src to normalizeScore(src, entry.score)
    }
    return null
}

/** 命中作品「有评分的源」的标签集合（来源标记 ）；主源始终在内。 */
private fun Work.activeSourceLabels(ratings: RatingAggregate?): List<String> {
    val sources = buildSet {
        add(primarySource)
        ratings?.perSource?.forEach { (source, entry) -> if (entry != null) add(source) }
    }
    return sources.map { it.discoverLabel() }
}

private fun Work.subtitleText(): String {
    val altTitle = titles.ja ?: titles.romaji ?: titles.en
    return listOfNotNull(altTitle, year?.toString()).joinToString(separator = " · ")
}

/** 把分数格式化为一位小数（避免浮点尾差，如 2.2999 显示为 2.3）。 */
internal fun formatScore(score: Float): String = "%.1f".format(score)

private fun MediaType.boardLabel(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
}

private fun CompletionCost.boardLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚"
    CompletionCost.WEEKEND -> "周末"
    CompletionCost.LONG_HAUL -> "长期坑"
}

// endregion

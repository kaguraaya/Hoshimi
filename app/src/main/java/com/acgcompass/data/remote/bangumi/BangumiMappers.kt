package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.matching.normalizeTitle
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bangumi DTO → 领域模型映射。
 *
 * 原则（不伪造，缺失即缺失）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」。
 * - 评分缺失（无有效样本）时 [BangumiRatingDto.toRatingEntry] 返回 `null`，**绝不**回填其它源数据。
 * - Bangumi 公共标签为社区自由标签，**无**官方分类信息；因此 [toWork] 不擅自归类（[Work.tags] 留空，
 *   避免污染口味画像的 MOOD/RISK 统计），标签归一化 / 分类是后续分类法任务的职责。需要时可用
 *   [BangumiTagDto.toDomainTag] 在已知分类上下文时显式映射。
 */

/**
 * Bangumi `type` → 领域 [MediaType]。
 *
 * 音乐(3)、三次元/真人(6)和未知类型统一归 [MediaType.OTHER]，不得默认归为 [MediaType.ANIME]。
 * 否则音乐等非动画条目会被误标为动画，泄漏进发现池 / 推荐 / 探索队列（均按 `==ANIME` 过滤）与口味校准池。
 */
fun mapBangumiMediaType(type: Int?): MediaType = when (type) {
    BangumiSubjectType.ANIME -> MediaType.ANIME
    BangumiSubjectType.BOOK -> MediaType.MANGA
    BangumiSubjectType.GAME -> MediaType.GAME
    else -> MediaType.OTHER
}

/** Preserve the tag counts needed by local taste scoring when a subject response is cached. */
fun BangumiSubjectDto.toBasicWorkFeature(
    subjectId: String = id.toString(),
    updatedAt: Long = System.currentTimeMillis(),
    completeness: WorkFeatureCompleteness = WorkFeatureCompleteness.BASIC,
): WorkFeature? {
    if (subjectId.isBlank() || subjectId == "0") return null
    return WorkFeature(
        subjectId = subjectId,
        tagCounts = tags.asSequence()
            .filter { it.name.isNotBlank() }
            .map { TagCount(name = it.name, count = it.count.coerceAtLeast(0)) }
            .toList(),
        bangumiScore = (rating?.score ?: score)?.takeIf { it > 0f },
        bangumiVotes = rating?.total?.coerceAtLeast(0) ?: 0,
        eps = (eps ?: totalEpisodes ?: 0).coerceAtLeast(0),
        platform = platform?.takeIf { it.isNotBlank() },
        mediaType = mapBangumiMediaType(type),
        titleVariants = listOfNotNull(
            name.takeIf { it.isNotBlank() },
            nameCn.takeIf { it.isNotBlank() },
        ).distinct(),
        completeness = completeness,
        baseDataUpdatedAt = updatedAt,
        updatedAt = updatedAt,
        coverUrl = images?.large ?: images?.common,
        airDate = date?.takeIf { it.isNotBlank() },
        ratingHistogram = rating?.histogram().orEmpty(),
    )
}

/** 1–10 分各有多少人打；接口没给或全为 0 时返回空表。 */
fun BangumiRatingDto.histogram(): List<Int> {
    val counts = count ?: return emptyList()
    val bins = (1..MAX_RATING).map { (counts[it.toString()] ?: 0).coerceAtLeast(0) }
    return if (bins.any { it > 0 }) bins else emptyList()
}

private const val MAX_RATING = 10

/** 从 Bangumi 日期串（如 `2023-04-07`）解析年份；无法解析返回 `null`（不臆造）。 */
internal fun parseBangumiYear(date: String?): Int? {
    val head = date?.take(4) ?: return null
    return head.toIntOrNull()?.takeIf { it in 1900..2999 }
}

/** 解析 Bangumi 日期串为 [java.time.LocalDate]（取前 10 位 `yyyy-MM-dd`）；失败返回 `null`。 */
internal fun parseBangumiDate(date: String?): java.time.LocalDate? {
    val head = date?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
    return runCatchingCancellable { java.time.LocalDate.parse(head) }.getOrNull()
}

/**
 * 由开播日期 + 集数推导发行状态（Bangumi v0 无干净的「完结」布尔，采用保守启发式，不臆造）。
 * - 无法解析日期 → UNKNOWN（显示「暂无数据」）。
 * - 开播日期晚于今天 → NOT_RELEASED（未发布）。
 * - 单集 / 剧场版（episodes<=1）且已过开播日 → FINISHED。
 * - 多集：估算完结日 = 开播 + (集数-1)*7 天 + 14 天缓冲；今天在此之前 → RELEASING，否则 FINISHED。
 */
internal fun deriveBangumiStatus(date: String?, episodes: Int?): ReleaseStatus {
    val start = parseBangumiDate(date) ?: return ReleaseStatus.UNKNOWN
    val today = java.time.LocalDate.now()
    if (start.isAfter(today)) return ReleaseStatus.NOT_RELEASED
    val eps = episodes?.takeIf { it > 0 } ?: 1
    if (eps <= 1) return ReleaseStatus.FINISHED
    val estimatedEnd = start.plusDays((eps - 1).toLong() * 7 + 14)
    return if (today.isAfter(estimatedEnd)) ReleaseStatus.FINISHED else ReleaseStatus.RELEASING
}

/** 选取封面 URL：优先 large，其次 common / medium / grid / small；全无返回 `null`。 */
internal fun BangumiImagesDto?.preferredCover(): String? {
    if (this == null) return null
    // Bangumi 图床常返回 http://，Android 默认禁明文 → 升级 https，确保封面可加载（修药屋少女/JOJO 等本地无封面）。
    return (large ?: common ?: medium ?: grid ?: small)?.toHttps()
}

/**
 * 条目评分对象 → [RatingEntry]；当无有效样本（人数 `<= 0`）时返回 `null` 表示「暂无数据」，
 * 不以 0 分伪造评分。
 */
fun BangumiRatingDto?.toRatingEntry(): RatingEntry? {
    if (this == null) return null
    if (total <= 0) return null
    return RatingEntry(
        score = score,
        voteCount = total,
        rank = rank?.takeIf { it > 0 },
    )
}

/** Bangumi 社区标签 → 领域 [Tag]（需在已知分类上下文时显式指定 [category]）。 */
fun BangumiTagDto.toDomainTag(category: TagCategory): Tag = Tag(category = category, name = name)

/** 批量映射社区标签（过滤空名）。 */
fun List<BangumiTagDto>.toDomainTags(category: TagCategory): List<Tag> = asSequence()
    .filter { it.name.isNotBlank() }
    .map { it.toDomainTag(category) }
    .toList()

/**
 * 条目 DTO → 规范化 [Work]。
 *
 * 标题：[Titles.canonical] 取中文名（缺失时回退原名）；[Titles.ja] 取原名（通常为日文）。
 * 体量：动画用 `eps`/`total_episodes`，书籍用 `volumes`（按媒介各取所需，缺失为 `null`）。
 */
fun BangumiSubjectDto.toWork(): Work {
    val original = name.takeIf { it.isNotBlank() }
    val chinese = nameCn.takeIf { it.isNotBlank() }
    val canonical = chinese ?: original ?: id.toString()
    return Work(
        id = id.toString(),
        titles = Titles(
            canonical = canonical,
            cn = chinese,
            ja = original,
            aliases = infobox.titleAliases(exclude = listOfNotNull(original, chinese)),
        ),
        mediaType = mapBangumiMediaType(type),
        year = parseBangumiYear(date),
        status = deriveBangumiStatus(date, (totalEpisodes ?: eps)),
        units = Units(
            episodes = (totalEpisodes ?: eps)?.takeIf { it > 0 },
            volumes = volumes?.takeIf { it > 0 },
        ),
        coverUrl = images.preferredCover(),
        primarySource = SourceId.BANGUMI,
        // 纳入 Bangumi 社区标签用于详情展示、题材筛选和口味匹配，但只保留题材
        // （[TasteTagTaxonomy.isSelectableGenre] 白名单），剔除人物名 / 声优 / 梗 / 厂商 / 年份等噪声——
        // 否则这些高标注噪声会挤占名额、污染发现页筛选并稀释口味匹配。按标注人数降序取前 15。
        tags = tags
            .sortedByDescending { it.count }
            .filter { TasteTagTaxonomy.isSelectableGenre(it.name) }
            .take(15)
            .toDomainTags(TagCategory.CONTENT),
        summary = summary.takeIf { it.isNotBlank() },
        airDate = date?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10),
    )
}

/**
 * 条目 DTO → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getSubjectById` 视为精确命中（默认 `1f`）。
 */
fun BangumiSubjectDto.toWorkMatch(matchConfidence: Float = 1f): WorkMatch = WorkMatch(
    work = toWork(),
    matchConfidence = matchConfidence,
    sourceTag = SourceId.BANGUMI,
    // 评分人数作为热度，用于同名候选的选代表 tiebreak（避免命中 PV / 同人 / 废弃等小条目）。
    popularity = rating?.total?.coerceAtLeast(0) ?: 0,
)

// region legacy `/calendar` 条目映射（每日放送公共发现池）

/** legacy `/calendar` 条目 → 规范化 [Work]（中文名优先，封面取 large/common）。 */
fun BangumiLegacySubjectDto.toWork(): Work {
    val cn = nameCn.takeIf { it.isNotBlank() }
    val ja = name.takeIf { it.isNotBlank() }
    return Work(
        id = id.toString(),
        titles = Titles(
            canonical = cn ?: ja ?: id.toString(),
            cn = cn,
            ja = ja,
        ),
        mediaType = mapBangumiMediaType(type),
        year = parseBangumiYear(airDate),
        coverUrl = images?.let { (it.large ?: it.common ?: it.medium ?: it.grid)?.toHttps() },
        primarySource = SourceId.BANGUMI,
        tags = emptyList(),
    )
}

/** 将 legacy `/calendar` 的 HTTP 封面地址升级为 HTTPS。 */
private fun String.toHttps(): String = if (startsWith("http://")) "https://" + substring("http://".length) else this

/** legacy `/calendar` 条目 → [WorkMatch]（公共发现池视为精确条目）。 */
fun BangumiLegacySubjectDto.toWorkMatch(): WorkMatch = WorkMatch(
    work = toWork(),
    matchConfidence = 1f,
    sourceTag = SourceId.BANGUMI,
    popularity = rating?.total?.coerceAtLeast(0) ?: 0,
)

/** legacy 评分 → [RatingEntry]；score ≤ 0 / 缺失返回 `null`（不伪造）。 */
fun BangumiLegacySubjectDto.toRatingEntry(): RatingEntry? {
    val r = rating ?: return null
    val score = r.score?.takeIf { it > 0f } ?: return null
    return RatingEntry(
        score = score,
        voteCount = r.total?.coerceAtLeast(0) ?: 0,
        rank = r.rank?.takeIf { it > 0 },
    )
}

// endregion

/** 信息框里记录作品其它名称的字段：别名里通常有英文名、罗马音和简称，是去外站匹配时最有用的名字。 */
private val ALIAS_INFOBOX_KEYS = setOf("别名", "英文名", "罗马名", "罗马字", "其他名称", "日文名", "原名")
private const val MAX_INFOBOX_ALIASES = 8

/**
 * 从信息框取作品的其它名称（[ALIAS_INFOBOX_KEYS]），去空白、去重，排除与 [exclude] 归一化后相同的名字，最多
 * [MAX_INFOBOX_ALIASES] 个。值可以是字符串，也可以是 `[{"v": "…"}]` 列表。
 */
internal fun List<BangumiInfoboxItemDto>.titleAliases(exclude: List<String> = emptyList()): List<String> {
    val excluded = exclude.mapTo(HashSet()) { normalizeTitle(it) }
    return asSequence()
        .filter { it.key.trim() in ALIAS_INFOBOX_KEYS }
        .flatMap { infoboxStrings(it.value) }
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .distinctBy { normalizeTitle(it) }
        .filter { normalizeTitle(it).isNotEmpty() && normalizeTitle(it) !in excluded }
        .take(MAX_INFOBOX_ALIASES)
        .toList()
}

private fun infoboxStrings(value: JsonElement?): Sequence<String> = when (value) {
    is JsonPrimitive -> if (value.isString) sequenceOf(value.content) else emptySequence()
    is JsonArray -> value.asSequence().mapNotNull { item ->
        when (item) {
            is JsonObject -> (item["v"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            is JsonPrimitive -> item.takeIf { it.isString }?.content
            else -> null
        }
    }
    else -> emptySequence()
}

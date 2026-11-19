package com.acgcompass.data.remote.jikan

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkIds
import com.acgcompass.domain.model.WorkMatch

/**
 * Jikan DTO → 领域模型映射。
 *
 * 原则（不伪造，缺失即缺失，与 `BangumiMappers` 一致）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」。
 * - 评分缺失（`score<=0` 或 `scored_by<=0`）时 [JikanAnimeDto.toRatingEntry] 返回 `null`，
 *   **绝不**以 Jikan 的「缺失即 0」回填 0 分，也绝不跨源回填其它源数据。
 * - Jikan 的 `genres`/`themes` 等是 MAL 英文社区标签，**无**本项目分类法语义；因此 [toWork]
 *   不擅自归类（[Work.tags] 留空），标签归一化 / 分类是后续分类法任务的职责。
 */

/** Jikan 动画 `type` → 领域 [MediaType]；Jikan anime 端点只返回动画，未知 / 缺失回退 [MediaType.ANIME]。 */
fun mapJikanMediaType(type: String?): MediaType = when (type) {
    // 主题曲 MV、宣传片、广告是动画条目的衍生物，不能当作正片参与匹配和推荐。
    JikanAnimeType.MUSIC,
    JikanAnimeType.PV,
    JikanAnimeType.CM,
    -> MediaType.OTHER
    else -> MediaType.ANIME
}

/** Jikan 动画 `status` → 领域 [ReleaseStatus]；未知 / 缺失回退 [ReleaseStatus.UNKNOWN]（不臆造）。 */
fun mapJikanStatus(status: String?): ReleaseStatus = when (status) {
    JikanAnimeStatus.FINISHED -> ReleaseStatus.FINISHED
    JikanAnimeStatus.AIRING -> ReleaseStatus.RELEASING
    JikanAnimeStatus.NOT_YET_AIRED -> ReleaseStatus.NOT_RELEASED
    else -> ReleaseStatus.UNKNOWN
}

/** 从 ISO 日期串（如 `1998-04-03T00:00:00+00:00`）解析年份；无法解析返回 `null`（不臆造）。 */
internal fun parseJikanYear(date: String?): Int? {
    val head = date?.take(4) ?: return null
    return head.toIntOrNull()?.takeIf { it in 1900..2999 }
}

/** 选取动画发行年份：优先 [JikanAnimeDto.year]，其次从 `aired.from` 解析；全无返回 `null`。 */
internal fun JikanAnimeDto.resolvedYear(): Int? = year?.takeIf { it in 1900..2999 } ?: parseJikanYear(aired?.from)

/** 选取封面 URL：优先 jpg 大图，其次 jpg 普通图，再退化到 webp；全无返回 `null`（不臆造）。 */
internal fun JikanImagesDto?.preferredCover(): String? {
    if (this == null) return null
    return jpg?.largeImageUrl
        ?: jpg?.imageUrl
        ?: webp?.largeImageUrl
        ?: webp?.imageUrl
        ?: jpg?.smallImageUrl
        ?: webp?.smallImageUrl
}

/**
 * 动画 DTO → [RatingEntry]（MAL 评分）；当无有效样本（`score<=0` 或 `scored_by<=0`）时返回 `null`
 * 表示「暂无数据」，不以 0 分伪造评分。[rank] 仅在为正时保留。
 */
fun JikanAnimeDto.toRatingEntry(): RatingEntry? {
    val s = score ?: 0f
    val votes = scoredBy ?: 0
    if (s <= 0f || votes <= 0) return null
    return RatingEntry(
        score = s,
        voteCount = votes,
        rank = rank?.takeIf { it > 0 },
    )
}

/**
 * 动画 DTO → 规范化 [Work]。
 *
 * 标题：[Titles.canonical] 取主标题 `title`（Jikan 以 MAL 罗马音 / 英文为主，缺失回退英文名 / id）；
 * [Titles.ja] 取日文名，[Titles.en] 取英文名，别名取 `title_synonyms`（过滤空白）。
 * 体量：动画用 `episodes`（缺失 / 非正为 `null`）。
 */
fun JikanAnimeDto.toWork(source: SourceId = SourceId.JIKAN): Work {
    val primary = title.takeIf { it.isNotBlank() }
    val english = titleEnglish?.takeIf { it.isNotBlank() }
    val japanese = titleJapanese?.takeIf { it.isNotBlank() }
    val canonical = primary ?: english ?: japanese ?: malId.toString()
    val aliases = titleSynonyms.filter { it.isNotBlank() }
    return Work(
        id = WorkIds.local(source, malId.toString()),
        titles = Titles(
            canonical = canonical,
            ja = japanese,
            en = english,
            aliases = aliases,
        ),
        mediaType = mapJikanMediaType(type),
        year = resolvedYear(),
        status = mapJikanStatus(status),
        units = Units(
            episodes = episodes?.takeIf { it > 0 },
        ),
        coverUrl = images.preferredCover(),
        primarySource = source,
        tags = emptyList(),
        summary = synopsis?.takeIf { it.isNotBlank() },
    )
}

/**
 * 动画 DTO → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getAnime` 视为精确命中（默认 `1f`）。
 */
fun JikanAnimeDto.toWorkMatch(matchConfidence: Float = 1f, source: SourceId = SourceId.JIKAN): WorkMatch = WorkMatch(
    work = toWork(source),
    matchConfidence = matchConfidence,
    sourceTag = source,
)

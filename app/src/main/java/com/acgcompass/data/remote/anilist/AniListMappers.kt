package com.acgcompass.data.remote.anilist

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch

/**
 * AniList DTO → 领域模型映射。
 *
 * 原则（不伪造，缺失即缺失）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」。
 * - 评分缺失（`averageScore` 为 `null` 或 `<= 0`）时 [AniListMediaDto.toRatingEntry] 返回 `null`，
 *   **绝不**回填其它源数据。
 * - **中文兜底语义**：Bangumi（提供中文名）不可用时降级到 AniList，
 *   AniList 无中文标题，故 [Work.titles.canonical] 取**英文**标题（其次罗马音 / 原文），
 *   作为 Bangumi 中文名缺位时的兜底展示标题。
 */

/**
 * AniList `type` + `format` → 领域 [MediaType]。
 *
 * AniList 用 `type`(ANIME/MANGA) 粗分，`format` 细分；轻小说在 `MANGA` 下以 `NOVEL` 表示。
 * 动画下的 `MUSIC`（主题曲 MV 等）归为 [MediaType.OTHER]，不当作正片参与匹配和推荐。
 * 未知或缺失类型回退为 [MediaType.ANIME]。
 */
fun mapAniListMediaType(type: String?, format: String?): MediaType = when (type) {
    AniListMediaType.ANIME -> if (format == AniListMediaFormat.MUSIC) MediaType.OTHER else MediaType.ANIME
    AniListMediaType.MANGA ->
        if (format == AniListMediaFormat.NOVEL) MediaType.NOVEL else MediaType.MANGA
    else -> MediaType.ANIME
}

/** AniList `MediaStatus` → 领域 [ReleaseStatus]；未知 / 缺失回退 [ReleaseStatus.UNKNOWN]。 */
fun mapAniListStatus(status: String?): ReleaseStatus = when (status) {
    "FINISHED" -> ReleaseStatus.FINISHED
    "RELEASING" -> ReleaseStatus.RELEASING
    "NOT_YET_RELEASED" -> ReleaseStatus.NOT_RELEASED
    "CANCELLED" -> ReleaseStatus.CANCELLED
    "HIATUS" -> ReleaseStatus.ON_HIATUS
    else -> ReleaseStatus.UNKNOWN
}

/** 选取封面 URL：优先 extraLarge，其次 large / medium；全无返回 `null`。 */
internal fun AniListCoverImageDto?.preferredCover(): String? {
    if (this == null) return null
    return extraLarge ?: large ?: medium
}

/** 年份：优先 `seasonYear`，回退 `startDate.year`；越界 / 缺失返回 `null`（不臆造）。 */
internal fun AniListMediaDto.resolveYear(): Int? = (seasonYear ?: startDate?.year)?.takeIf { it in 1900..2999 }

/**
 * `Media` → [RatingEntry]；当 `averageScore` 缺失或 `<= 0` 时返回 `null` 表示「暂无数据」，
 * 不以 0 分伪造评分。
 *
 * - [RatingEntry.score]：保留 AniList **0–100** 原生量纲（聚合时统一归一，见 `AggregateRatingsUseCase`）。
 * - [RatingEntry.voteCount]：`stats.scoreDistribution` 各档 `amount` 之和（真实评分样本数）；
 *   未请求 / 缺失时为 0（不臆造）。
 * - [RatingEntry.rank]：优先取历史总榜（`allTime == true`）的 `RATED` 排名。
 */
fun AniListMediaDto.toRatingEntry(): RatingEntry? {
    val score = averageScore?.takeIf { it > 0 }?.toFloat() ?: return null
    val voteCount = stats?.scoreDistribution
        ?.sumOf { (it.amount ?: 0).coerceAtLeast(0) }
        ?: 0
    val rank = rankings
        .firstOrNull { it.type == AniListRankType.RATED && it.allTime == true && (it.rank ?: 0) > 0 }
        ?.rank
    return RatingEntry(
        score = score,
        voteCount = voteCount,
        rank = rank,
    )
}

/**
 * `Media` → 规范化 [Work]。
 *
 * 标题（中文兜底）：[Titles.canonical] 取 `english ?: romaji ?: native ?: id`；
 * [Titles.en] / [Titles.romaji] / [Titles.ja] 分别保留英文 / 罗马音 / 原文。
 * 体量：动画用 `episodes`(+`duration` 为单集分钟)，漫画 / 小说用 `chapters`→`volumes`（缺失为 `null`）。
 */
fun AniListMediaDto.toWork(): Work {
    val english = title?.english?.takeIf { it.isNotBlank() }
    val romaji = title?.romaji?.takeIf { it.isNotBlank() }
    val native = title?.nativeTitle?.takeIf { it.isNotBlank() }
    val canonical = english ?: romaji ?: native ?: id.toString()
    return Work(
        id = id.toString(),
        titles = Titles(
            canonical = canonical,
            ja = native,
            romaji = romaji,
            en = english,
        ),
        mediaType = mapAniListMediaType(type, format),
        year = resolveYear(),
        status = mapAniListStatus(status),
        units = Units(
            episodes = episodes?.takeIf { it > 0 },
            episodeMinutes = duration?.takeIf { it > 0 },
            volumes = volumes?.takeIf { it > 0 },
        ),
        coverUrl = coverImage.preferredCover(),
        primarySource = SourceId.ANILIST,
        tags = emptyList(),
        // AniList 简介请求 asHtml:false，并继续清理残留标签和多余空白；缺失时为 null。
        summary = description?.cleanAniListDescription(),
    )
}

/** 清理 AniList 简介：移除 `<br>` / HTML 标签、折叠多余空白；空串返回 null（不伪造）。 */
private fun String.cleanAniListDescription(): String? = replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()
    .takeIf { it.isNotBlank() }

/**
 * `Media` → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getMediaById` 视为精确命中（默认 `1f`）。
 */
fun AniListMediaDto.toWorkMatch(matchConfidence: Float = 1f): WorkMatch = WorkMatch(
    work = toWork(),
    matchConfidence = matchConfidence,
    sourceTag = SourceId.ANILIST,
)

package com.acgcompass.data.remote.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AniList GraphQL DTO 集合。
 *
 * 单一 endpoint `POST https://graphql.anilist.co`，body `{query, variables}`，
 * 响应顶层为 `{ "data": {...}, "errors": [...] }`。这里只承载传输结构，领域语义由
 * [AniListMappers] 转换。
 *
 * 设计约束：AniList schema 可能演进，故**所有字段均可空或带默认值**，
 * 配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys` + `coerceInputValues`）
 * 做向后兼容解析，源新增 / 缺失字段都不会导致反序列化崩溃；缺失即缺失，不臆造。
 */

/** GraphQL `data` 字段在「按 ID 取 Media」时的结构：`{ "Media": {...} }`。 */
@Serializable
data class AniListMediaResponse(
    @SerialName("Media") val media: AniListMediaDto? = null,
)

/** GraphQL `data` 字段在「按标题搜索」时的结构：`{ "Page": { "media": [...] } }`。 */
@Serializable
data class AniListPageResponse(
    @SerialName("Page") val page: AniListPageDto? = null,
)

@Serializable
data class AniListScheduleResponse(
    @SerialName("Page") val page: AniListSchedulePageDto? = null,
)

@Serializable
data class AniListSchedulePageDto(
    @SerialName("airingSchedules") val schedules: List<AniListAiringScheduleDto> = emptyList(),
    @SerialName("pageInfo") val pageInfo: AniListPageInfoDto? = null,
)

@Serializable
data class AniListPageInfoDto(
    @SerialName("hasNextPage") val hasNextPage: Boolean = false,
)

@Serializable
data class AniListAiringScheduleDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("airingAt") val airingAt: Long = 0L,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("media") val media: AniListMediaDto? = null,
)

/** `Page` 对象：本项目只取 `media` 列表（分页元信息按需再扩展）。 */
@Serializable
data class AniListPageDto(
    @SerialName("media") val media: List<AniListMediaDto> = emptyList(),
)

/**
 * `Media` 对象（动画 / 漫画 / 轻小说统一实体）。
 *
 * - [averageScore]：**0–100** 加权均分（聚合时由 `AggregateRatingsUseCase.normalizeToTen` 归一）。
 * - [type]：`ANIME` / `MANGA`；[format]：`TV`/`MOVIE`/`OVA`/`ONA`/`SPECIAL`/`MUSIC`/`MANGA`/`NOVEL`/`ONE_SHOT`…
 * - [status]：`FINISHED`/`RELEASING`/`NOT_YET_RELEASED`/`CANCELLED`/`HIATUS`。
 * - [season]：`WINTER`/`SPRING`/`SUMMER`/`FALL`。
 */
@Serializable
data class AniListMediaDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("type") val type: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("title") val title: AniListTitleDto? = null,
    @SerialName("averageScore") val averageScore: Int? = null,
    @SerialName("meanScore") val meanScore: Int? = null,
    @SerialName("popularity") val popularity: Int? = null,
    @SerialName("favourites") val favourites: Int? = null,
    @SerialName("trending") val trending: Int? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("season") val season: String? = null,
    @SerialName("seasonYear") val seasonYear: Int? = null,
    @SerialName("episodes") val episodes: Int? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("chapters") val chapters: Int? = null,
    @SerialName("volumes") val volumes: Int? = null,
    @SerialName("startDate") val startDate: AniListFuzzyDateDto? = null,
    @SerialName("coverImage") val coverImage: AniListCoverImageDto? = null,
    @SerialName("rankings") val rankings: List<AniListRankingDto> = emptyList(),
    @SerialName("stats") val stats: AniListStatsDto? = null,
    @SerialName("countryOfOrigin") val countryOfOrigin: String? = null,
)

/** 多语言标题（罗马音 / 英文 / 原文，任一可缺失）。 */
@Serializable
data class AniListTitleDto(
    @SerialName("romaji") val romaji: String? = null,
    @SerialName("english") val english: String? = null,
    // `native` 是 Kotlin 软关键字，序列化键保持 GraphQL 原名。
    @SerialName("native") val nativeTitle: String? = null,
)

/** 模糊日期（年 / 月 / 日，任一可缺失）。 */
@Serializable
data class AniListFuzzyDateDto(
    @SerialName("year") val year: Int? = null,
    @SerialName("month") val month: Int? = null,
    @SerialName("day") val day: Int? = null,
)

/** 封面多尺寸 URL（无图时各尺寸为 `null`）。 */
@Serializable
data class AniListCoverImageDto(
    @SerialName("extraLarge") val extraLarge: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("color") val color: String? = null,
)

/** 排名条目：[type] = `RATED` / `POPULAR`；[allTime] 区分历史总榜与季度/年度榜。 */
@Serializable
data class AniListRankingDto(
    @SerialName("rank") val rank: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("allTime") val allTime: Boolean? = null,
    @SerialName("context") val context: String? = null,
)

/** 统计信息：本项目用 [scoreDistribution] 的各档人数之和作为真实评分样本数。 */
@Serializable
data class AniListStatsDto(
    @SerialName("scoreDistribution") val scoreDistribution: List<AniListScoreDistributionDto> = emptyList(),
)

/** 评分分布的一档：[score] 分值（10 / 20 / …）、[amount] 该分值的用户数。 */
@Serializable
data class AniListScoreDistributionDto(
    @SerialName("score") val score: Int? = null,
    @SerialName("amount") val amount: Int? = null,
)

/** AniList GraphQL `errors[]` 单项（用于把 `200 + errors` 映射到领域错误）。 */
@Serializable
data class AniListGraphQlError(
    @SerialName("message") val message: String? = null,
    @SerialName("status") val status: Int? = null,
)

/** AniList `Media.type` / `format` 取值常量（官方枚举）。 */
object AniListMediaType {
    const val ANIME: String = "ANIME"
    const val MANGA: String = "MANGA"
}

/** AniList `MediaFormat` 关注的取值常量（用于区分小说 / 漫画）。 */
object AniListMediaFormat {
    const val NOVEL: String = "NOVEL"
}

/** AniList `MediaRankType` 取值常量。 */
object AniListRankType {
    const val RATED: String = "RATED"
    const val POPULAR: String = "POPULAR"
}

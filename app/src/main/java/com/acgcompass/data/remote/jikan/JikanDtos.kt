package com.acgcompass.data.remote.jikan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jikan REST v4 DTO 集合。
 *
 * Jikan 是抓取 MyAnimeList 的**非官方只读 API**（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.JIKAN_BASE_URL]，无需 key）。所有响应以
 * `{ data: ... }` 信封包裹，列表接口额外含 `pagination`。
 *
 * 设计约束：上游 MAL 抓取结构可能演进，故**所有字段均可空或带默认值**，
 * 配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys` + `coerceInputValues`）做向后兼容解析。
 *
 * 官方 JSON 约定：标量缺失 → `null`；数组 / 对象缺失 → 空；**评分（`score`）缺失 → `0`**，
 * 因此 mapper 在 `score<=0` / `scored_by<=0` 时按「暂无数据」处理，绝不以 0 分伪造评分。
 */

/** 图片集合（jpg / webp 两种格式，各含多尺寸 URL）。 */
@Serializable
data class JikanImagesDto(
    @SerialName("jpg") val jpg: JikanImageSetDto? = null,
    @SerialName("webp") val webp: JikanImageSetDto? = null,
)

/** 单一格式的多尺寸图片 URL（无图时各尺寸可为 `null`）。 */
@Serializable
data class JikanImageSetDto(
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("small_image_url") val smallImageUrl: String? = null,
    @SerialName("large_image_url") val largeImageUrl: String? = null,
)

/** 标题条目：`type`（Default/Japanese/English/Synonym…）+ `title`。 */
@Serializable
data class JikanTitleDto(
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String = "",
)

/** 播出 / 发行日期区间（仅承载传输结构，年份解析在 mapper）。 */
@Serializable
data class JikanAiredDto(
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("string") val string: String? = null,
)

@Serializable
data class JikanBroadcastDto(
    @SerialName("day") val day: String? = null,
    @SerialName("time") val time: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("string") val displayText: String? = null,
)

/** 通用命名实体（genres / themes / demographics / studios / producers / licensors）。 */
@Serializable
data class JikanNamedEntityDto(
    @SerialName("mal_id") val malId: Int = 0,
    @SerialName("type") val type: String? = null,
    @SerialName("name") val name: String = "",
    @SerialName("url") val url: String? = null,
)

/**
 * 动画条目（`GET /v4/anime/{id}` 的 `data`，亦为 `GET /v4/anime?q=` 列表元素）。
 *
 * 评分相关字段（对应 MAL）：[score]（0–10，缺失为 0）、[scoredBy]（评分人数）、
 * [rank]（综合排名）、[popularity]（人气排名）、[members]、[favorites]。
 */
@Serializable
data class JikanAnimeDto(
    @SerialName("mal_id") val malId: Int = 0,
    @SerialName("url") val url: String? = null,
    @SerialName("images") val images: JikanImagesDto? = null,
    @SerialName("approved") val approved: Boolean = false,
    @SerialName("titles") val titles: List<JikanTitleDto> = emptyList(),
    @SerialName("title") val title: String = "",
    @SerialName("title_english") val titleEnglish: String? = null,
    @SerialName("title_japanese") val titleJapanese: String? = null,
    @SerialName("title_synonyms") val titleSynonyms: List<String> = emptyList(),
    @SerialName("type") val type: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("episodes") val episodes: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("airing") val airing: Boolean = false,
    @SerialName("aired") val aired: JikanAiredDto? = null,
    @SerialName("broadcast") val broadcast: JikanBroadcastDto? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("score") val score: Float? = null,
    @SerialName("scored_by") val scoredBy: Int? = null,
    @SerialName("rank") val rank: Int? = null,
    @SerialName("popularity") val popularity: Int? = null,
    @SerialName("members") val members: Int? = null,
    @SerialName("favorites") val favorites: Int? = null,
    @SerialName("synopsis") val synopsis: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("season") val season: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("genres") val genres: List<JikanNamedEntityDto> = emptyList(),
    @SerialName("themes") val themes: List<JikanNamedEntityDto> = emptyList(),
    @SerialName("demographics") val demographics: List<JikanNamedEntityDto> = emptyList(),
    @SerialName("studios") val studios: List<JikanNamedEntityDto> = emptyList(),
    @SerialName("producers") val producers: List<JikanNamedEntityDto> = emptyList(),
)

/** 列表分页信息（搜索 / Reviews）。 */
@Serializable
data class JikanPaginationDto(
    @SerialName("last_visible_page") val lastVisiblePage: Int = 0,
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("items") val items: JikanPaginationItemsDto? = null,
)

/** 分页计数（搜索结果总量）。 */
@Serializable
data class JikanPaginationItemsDto(
    @SerialName("count") val count: Int = 0,
    @SerialName("total") val total: Int = 0,
    @SerialName("per_page") val perPage: Int = 0,
)

/** 单条目信封（`GET /v4/anime/{id}`）。 */
@Serializable
data class JikanAnimeResponseDto(
    @SerialName("data") val data: JikanAnimeDto? = null,
)

/** 搜索结果信封（`GET /v4/anime?q=`）。 */
@Serializable
data class JikanAnimeSearchResponseDto(
    @SerialName("pagination") val pagination: JikanPaginationDto? = null,
    @SerialName("data") val data: List<JikanAnimeDto> = emptyList(),
)

/** 用户反应计数（Review 的赞同维度）。 */
@Serializable
data class JikanReviewReactionsDto(
    @SerialName("overall") val overall: Int = 0,
    @SerialName("nice") val nice: Int = 0,
    @SerialName("love_it") val loveIt: Int = 0,
    @SerialName("funny") val funny: Int = 0,
    @SerialName("confusing") val confusing: Int = 0,
    @SerialName("informative") val informative: Int = 0,
    @SerialName("well_written") val wellWritten: Int = 0,
    @SerialName("creative") val creative: Int = 0,
)

/** 评论作者（用户）。 */
@Serializable
data class JikanUserDto(
    @SerialName("username") val username: String = "",
    @SerialName("url") val url: String? = null,
    @SerialName("images") val images: JikanImagesDto? = null,
)

/** 用户评论（`GET /v4/anime/{id}/reviews` 列表元素）。 */
@Serializable
data class JikanReviewDto(
    @SerialName("mal_id") val malId: Int = 0,
    @SerialName("url") val url: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("review") val review: String = "",
    @SerialName("score") val score: Int? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("is_spoiler") val isSpoiler: Boolean = false,
    @SerialName("is_preliminary") val isPreliminary: Boolean = false,
    @SerialName("episodes_watched") val episodesWatched: Int? = null,
    @SerialName("reactions") val reactions: JikanReviewReactionsDto? = null,
    @SerialName("user") val user: JikanUserDto? = null,
)

/** 评论列表信封（`GET /v4/anime/{id}/reviews`）。 */
@Serializable
data class JikanReviewsResponseDto(
    @SerialName("pagination") val pagination: JikanPaginationDto? = null,
    @SerialName("data") val data: List<JikanReviewDto> = emptyList(),
)

/** 推荐目标条目（精简，仅含标识 / 标题 / 图）。 */
@Serializable
data class JikanRecommendationEntryDto(
    @SerialName("mal_id") val malId: Int = 0,
    @SerialName("url") val url: String? = null,
    @SerialName("title") val title: String = "",
    @SerialName("images") val images: JikanImagesDto? = null,
)

/** 单条推荐（`GET /v4/anime/{id}/recommendations` 列表元素）。 */
@Serializable
data class JikanRecommendationDto(
    @SerialName("entry") val entry: JikanRecommendationEntryDto? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("votes") val votes: Int = 0,
)

/** 推荐列表信封（`GET /v4/anime/{id}/recommendations`）。 */
@Serializable
data class JikanRecommendationsResponseDto(
    @SerialName("data") val data: List<JikanRecommendationDto> = emptyList(),
)

/** Jikan 动画 `type` 取值（均归一为领域 ANIME；保留常量用于显示 / 调试）。 */
object JikanAnimeType {
    const val TV: String = "TV"
    const val MOVIE: String = "Movie"
    const val OVA: String = "OVA"
    const val ONA: String = "ONA"
    const val SPECIAL: String = "Special"
    const val MUSIC: String = "Music"
    const val PV: String = "PV"
    const val CM: String = "CM"
}

/** Jikan 动画 `status` 取值（用于映射领域 [com.acgcompass.domain.model.ReleaseStatus]）。 */
object JikanAnimeStatus {
    const val FINISHED: String = "Finished Airing"
    const val AIRING: String = "Currently Airing"
    const val NOT_YET_AIRED: String = "Not yet aired"
}

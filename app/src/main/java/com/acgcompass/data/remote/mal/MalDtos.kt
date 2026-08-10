package com.acgcompass.data.remote.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MyAnimeList 官方 API v2 DTO 集合。
 *
 * MAL 官方 API（Base URL：[com.acgcompass.core.network.NetworkConstants.MAL_BASE_URL]）的关键约定：
 * - **字段按需返回**：默认不返回全部字段，需以 `fields` 查询参数显式声明（见 [MalFields]）。
 * - **缺失字段直接不出现在响应中**（不是 `null`），故所有 DTO 字段均可空或带默认值，
 *   配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys` + `coerceInputValues`）做向后兼容解析。
 * - 列表统一 `{ data:[...], paging:{ previous?, next? } }`。
 *
 * 不伪造原则：`mean`（评分）缺失即「暂无评分」，mapper 绝不以 0 回填。
 */

/** 作品主图（`main_picture`，多尺寸；无图时各尺寸可缺失）。 */
@Serializable
data class MalPictureDto(
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
)

/** 备用标题（`alternative_titles`：英文 / 日文 / 同义词）。 */
@Serializable
data class MalAlternativeTitlesDto(
    @SerialName("en") val en: String? = null,
    @SerialName("ja") val ja: String? = null,
    @SerialName("synonyms") val synonyms: List<String> = emptyList(),
)

/** 首播季（`start_season`：年份 + 季节 winter/spring/summer/fall）。 */
@Serializable
data class MalStartSeasonDto(
    @SerialName("year") val year: Int? = null,
    @SerialName("season") val season: String? = null,
)

/**
 * 动画条目（`GET /v2/anime/{anime_id}` 的响应根，亦为搜索 / 用户列表中的 `node`）。
 *
 * 评分相关字段：[mean]（0–10 加权均分，**缺失即不返回**）、[rank]（综合排名）、
 * [popularity]（人气排名）、[numScoringUsers]（评分人数）、[numListUsers]（列表用户数）。
 */
@Serializable
data class MalAnimeDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("main_picture") val mainPicture: MalPictureDto? = null,
    @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitlesDto? = null,
    @SerialName("mean") val mean: Float? = null,
    @SerialName("rank") val rank: Int? = null,
    @SerialName("popularity") val popularity: Int? = null,
    @SerialName("num_scoring_users") val numScoringUsers: Int? = null,
    @SerialName("num_list_users") val numListUsers: Int? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("start_season") val startSeason: MalStartSeasonDto? = null,
)

/** 列表分页游标（`paging`：仅承载上一页 / 下一页完整 URL）。 */
@Serializable
data class MalPagingDto(
    @SerialName("previous") val previous: String? = null,
    @SerialName("next") val next: String? = null,
)

/** 搜索结果列表元素（`GET /v2/anime?q=` 的 `data[]`，每项以 `node` 包裹动画）。 */
@Serializable
data class MalAnimeListNodeDto(
    @SerialName("node") val node: MalAnimeDto? = null,
)

/** 搜索结果信封（`GET /v2/anime?q=`）。 */
@Serializable
data class MalAnimeListResponseDto(
    @SerialName("data") val data: List<MalAnimeListNodeDto> = emptyList(),
    @SerialName("paging") val paging: MalPagingDto? = null,
)

/**
 * 用户在某作品上的列表状态（`list_status`）。
 *
 * - [status]：`watching` / `completed` / `on_hold` / `dropped` / `plan_to_watch`。
 * - [score]：用户个人评分 0–10（0 表示未评分）。
 * - [numEpisodesWatched]：已观看集数（进度）。
 */
@Serializable
data class MalListStatusDto(
    @SerialName("status") val status: String? = null,
    @SerialName("score") val score: Int = 0,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int = 0,
    @SerialName("is_rewatching") val isRewatching: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** 用户动画列表元素（`GET /v2/users/{user}/animelist` 的 `data[]`：`node` + `list_status`）。 */
@Serializable
data class MalUserListNodeDto(
    @SerialName("node") val node: MalAnimeDto? = null,
    @SerialName("list_status") val listStatus: MalListStatusDto? = null,
)

/** 用户动画列表信封（`GET /v2/users/{user}/animelist`）。 */
@Serializable
data class MalUserAnimeListResponseDto(
    @SerialName("data") val data: List<MalUserListNodeDto> = emptyList(),
    @SerialName("paging") val paging: MalPagingDto? = null,
)

/** MAL 动画 `media_type` 取值（均归一为领域 ANIME；保留常量用于显示 / 调试）。 */
object MalMediaType {
    const val TV: String = "tv"
    const val MOVIE: String = "movie"
    const val OVA: String = "ova"
    const val ONA: String = "ona"
    const val SPECIAL: String = "special"
    const val MUSIC: String = "music"
    const val UNKNOWN: String = "unknown"
}

/** MAL 动画 `status` 取值（用于映射领域 [com.acgcompass.domain.model.ReleaseStatus]）。 */
object MalAnimeStatus {
    const val FINISHED: String = "finished_airing"
    const val AIRING: String = "currently_airing"
    const val NOT_YET_AIRED: String = "not_yet_aired"
}

/**
 * `fields` 参数预设。MAL 默认不返回全部字段，须显式列出。
 *
 * 与本项目领域模型一一对应，避免过度抓取（仅取映射所需字段）。
 */
object MalFields {

    /** 动画详情 / 搜索所需字段：id,title,main_picture,mean,rank,popularity,num_episodes,status,start_season,media_type。 */
    const val ANIME_DETAIL: String =
        "id,title,main_picture,alternative_titles,mean,rank,popularity," +
            "num_scoring_users,num_list_users,num_episodes,status,start_season,media_type"

    /** 用户动画列表所需字段：节点基础信息 + 个人进度/评分（`list_status`）。 */
    const val USER_LIST: String =
        "list_status,id,title,main_picture,mean,num_episodes,status,start_season,media_type"
}

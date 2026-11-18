package com.acgcompass.data.remote.bangumi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Bangumi `/v0/` REST DTO 集合。
 *
 * 设计约束：
 * - `/v0/search/subjects` 为**实验性** API，`Subject` 完整字段仍可能演进，故**所有字段均可空或带默认值**，
 *   配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys` + `coerceInputValues`）做向后兼容解析，
 *   源新增 / 缺失字段都不会导致反序列化崩溃。
 * - 这里**只**承载传输结构，不臆造未核验字段；领域语义由 `BangumiMappers` 转换。
 */

/** 条目封面 / 缩略图多尺寸 URL（无图时各尺寸可为 `null`）。 */
@Serializable
data class BangumiImagesDto(
    @SerialName("small") val small: String? = null,
    @SerialName("grid") val grid: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("common") val common: String? = null,
)

/** 公共标签（社区标签）：名称 + 标注人数。 */
@Serializable
data class BangumiTagDto(
    @SerialName("name") val name: String = "",
    @SerialName("count") val count: Int = 0,
)

/** 条目评分对象：[score] 评分、[total]/[count] 人数、[rank] 排名（部分条目无排名）。 */
@Serializable
data class BangumiRatingDto(
    @SerialName("rank") val rank: Int? = null,
    @SerialName("total") val total: Int = 0,
    @SerialName("count") val count: Map<String, Int>? = null,
    @SerialName("score") val score: Float = 0f,
)

/** 收藏数细分计数（各状态人数，缺失为 `null`）。 */
@Serializable
data class BangumiCollectionDto(
    @SerialName("wish") val wish: Int? = null,
    @SerialName("collect") val collect: Int? = null,
    @SerialName("doing") val doing: Int? = null,
    @SerialName("on_hold") val onHold: Int? = null,
    @SerialName("dropped") val dropped: Int? = null,
)

/**
 * 条目（`GET /v0/subjects/{id}`）。
 *
 * Bangumi `type`：`1`=书籍、`2`=动画、`3`=音乐、`4`=游戏、`6`=三次元（见 `BangumiSubjectType`）。
 */
@Serializable
data class BangumiSubjectDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("type") val type: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("nsfw") val nsfw: Boolean = false,
    @SerialName("date") val date: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("images") val images: BangumiImagesDto? = null,
    @SerialName("eps") val eps: Int? = null,
    @SerialName("total_episodes") val totalEpisodes: Int? = null,
    @SerialName("volumes") val volumes: Int? = null,
    @SerialName("tags") val tags: List<BangumiTagDto> = emptyList(),
    @SerialName("rating") val rating: BangumiRatingDto? = null,
    /** 收藏列表里内嵌的精简条目没有 [rating]，只有这个分数。 */
    @SerialName("score") val score: Float? = null,
    @SerialName("collection") val collection: BangumiCollectionDto? = null,
    /** 条目信息框（搜索结果与条目详情都有，收藏列表里的精简条目没有）。 */
    @SerialName("infobox") val infobox: List<BangumiInfoboxItemDto> = emptyList(),
)

/**
 * 信息框的一项。`value` 可能是字符串，也可能是 `[{"v": "…"}]` 形式的列表（如「别名」），所以按原始 JSON 保存。
 */
@Serializable
data class BangumiInfoboxItemDto(
    @SerialName("key") val key: String = "",
    @SerialName("value") val value: JsonElement? = null,
)

/** 分页条目列表（`POST /v0/search/subjects`）。 */
@Serializable
data class BangumiPagedSubjectDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("data") val data: List<BangumiSubjectDto> = emptyList(),
)

/** `POST /v0/search/subjects` 请求体。`limit`/`offset` 为 query，不在此体内。 */
@Serializable
data class BangumiSearchRequestDto(
    @SerialName("keyword") val keyword: String,
    @SerialName("sort") val sort: String? = null,
    @SerialName("filter") val filter: BangumiSearchFilterDto? = null,
)

/** 搜索过滤维度（官方确认：`type`/`meta_tags`/`tag`/`air_date`/`rating`/`rating_count`/`rank`/`nsfw`）。 */
@Serializable
data class BangumiSearchFilterDto(
    @SerialName("type") val type: List<Int>? = null,
    @SerialName("meta_tags") val metaTags: List<String>? = null,
    @SerialName("tag") val tag: List<String>? = null,
    @SerialName("air_date") val airDate: List<String>? = null,
    @SerialName("rating") val rating: List<String>? = null,
    @SerialName("rating_count") val ratingCount: List<String>? = null,
    @SerialName("rank") val rank: List<String>? = null,
    @SerialName("nsfw") val nsfw: Boolean? = null,
)

/** 关联人物（`GET /v0/subjects/{id}/persons`）。 */
@Serializable
data class BangumiRelatedPersonDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("type") val type: Int? = null,
    @SerialName("career") val career: List<String> = emptyList(),
    @SerialName("relation") val relation: String? = null,
    @SerialName("images") val images: BangumiImagesDto? = null,
)

/** 关联角色（`GET /v0/subjects/{id}/characters`）。 */
@Serializable
data class BangumiRelatedCharacterDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("type") val type: Int? = null,
    @SerialName("relation") val relation: String? = null,
    @SerialName("images") val images: BangumiImagesDto? = null,
    @SerialName("actors") val actors: List<BangumiRelatedPersonDto> = emptyList(),
)

/** 关联作品（`GET /v0/subjects/{id}/subjects`）。 */
@Serializable
data class BangumiSubjectRelationDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("type") val type: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    @SerialName("relation") val relation: String? = null,
    @SerialName("images") val images: BangumiImagesDto? = null,
)

/** 用户单条收藏（个人评分 / 短评 / 进度 / 状态），可携带嵌套条目。 */
@Serializable
data class BangumiUserSubjectCollectionDto(
    @SerialName("subject_id") val subjectId: Int = 0,
    @SerialName("subject_type") val subjectType: Int? = null,
    @SerialName("rate") val rate: Int = 0,
    @SerialName("type") val type: Int? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("ep_status") val epStatus: Int = 0,
    @SerialName("vol_status") val volStatus: Int = 0,
    @SerialName("private") val private: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("subject") val subject: BangumiSubjectDto? = null,
)

/** 分页用户收藏列表（`GET /v0/users/{username}/collections`）。 */
@Serializable
data class BangumiPagedUserCollectionDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("data") val data: List<BangumiUserSubjectCollectionDto> = emptyList(),
)

/** 当前登录用户（`GET /v0/me`，需 Bearer 授权）。 */
@Serializable
data class BangumiMeDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("username") val username: String = "",
    @SerialName("nickname") val nickname: String = "",
    @SerialName("user_group") val userGroup: Int? = null,
    @SerialName("avatar") val avatar: BangumiImagesDto? = null,
    @SerialName("sign") val sign: String? = null,
)

/** Bangumi 条目 `type` 数值常量（官方枚举）。 */
object BangumiSubjectType {
    const val BOOK: Int = 1
    const val ANIME: Int = 2
    const val MUSIC: Int = 3
    const val GAME: Int = 4
    const val REAL: Int = 6
}

/** Bangumi `sort` 可选值（`POST /v0/search/subjects`）。 */
object BangumiSearchSort {
    const val MATCH: String = "match"
    const val HEAT: String = "heat"
    const val RANK: String = "rank"
    const val SCORE: String = "score"
}

/**
 * 修改用户单个条目收藏的请求体（`POST /v0/users/-/collections/{subject_id}`，需 Bearer）。
 *
 * 全部字段可空——只提交用户改动的字段（服务端按存在的字段更新，缺省不动）。
 * - [type]：收藏状态 1=想看 / 2=看过 / 3=在看 / 4=搁置 / 5=抛弃（见 [BangumiCollectionType]）。
 * - [rate]：评分 0–10（0 表示清除评分）。
 * - [epStatus] / [volStatus]：看到的话数 / 卷数。
 * - [comment]：短评。 [tags]：标签。 [private]：是否设为私密。
 *
 * 安全：本请求只在用户显式编辑「我的记录」并已配置 Bangumi Token 时发起；Token 由拦截器注入，
 * 绝不出现在请求体 / 日志。
 */
@Serializable
data class BangumiUpdateCollectionRequest(
    @SerialName("type") val type: Int? = null,
    @SerialName("rate") val rate: Int? = null,
    @SerialName("ep_status") val epStatus: Int? = null,
    @SerialName("vol_status") val volStatus: Int? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("private") val `private`: Boolean? = null,
)

/** Bangumi 收藏状态枚举值（与 v0 `type` 对齐）。 */
object BangumiCollectionType {
    const val WISH: Int = 1
    const val DONE: Int = 2
    const val DOING: Int = 3
    const val ON_HOLD: Int = 4
    const val DROPPED: Int = 5
}

/**
 * Bangumi 每日放送（`GET /calendar`，legacy 公共端点，免鉴权）。返回 7 个工作日分组，
 * 每组含当日在播条目。用于「本季 / 近期热门」公共发现池（默认数据源为 Bangumi）。
 */
@Serializable
data class BangumiCalendarDayDto(
    @SerialName("weekday") val weekday: BangumiCalendarWeekdayDto? = null,
    @SerialName("items") val items: List<BangumiLegacySubjectDto> = emptyList(),
)

@Serializable
data class BangumiCalendarWeekdayDto(
    @SerialName("id") val id: Int? = null,
    @SerialName("en") val en: String? = null,
    @SerialName("cn") val cn: String? = null,
)

/** legacy 条目（`/calendar` 项）：字段与 `/v0` 不同，封面在 [images]、评分在 [rating]。 */
@Serializable
data class BangumiLegacySubjectDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    @SerialName("type") val type: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("images") val images: BangumiLegacyImagesDto? = null,
    @SerialName("rating") val rating: BangumiLegacyRatingDto? = null,
)

/** legacy 封面多尺寸。 */
@Serializable
data class BangumiLegacyImagesDto(
    @SerialName("large") val large: String? = null,
    @SerialName("common") val common: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("grid") val grid: String? = null,
)

/** legacy 评分（score / total 人数 / rank 排名）。 */
@Serializable
data class BangumiLegacyRatingDto(
    @SerialName("score") val score: Float? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("rank") val rank: Int? = null,
)

/**
 * 章节（`GET /v0/episodes` 的 data 项）。`type=0` 为本篇；`sort`/`ep` 为集数排序。
 * 仅取展示与排序所需字段，其余忽略（`ignoreUnknownKeys`）。
 */
@Serializable
data class BangumiEpisodeDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("type") val type: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    @SerialName("sort") val sort: Float = 0f,
    @SerialName("ep") val ep: Float? = null,
)

/** 分页章节列表（`GET /v0/episodes`）。 */
@Serializable
data class BangumiPagedEpisodeDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("data") val data: List<BangumiEpisodeDto> = emptyList(),
)

/** 批量章节收藏更新请求体（`episode_id` 数组 + `type`，EpisodeCollectionType）。 */
@Serializable
data class BangumiPatchEpisodesRequest(
    @SerialName("episode_id") val episodeId: List<Int>,
    @SerialName("type") val type: Int,
)

/** 章节收藏类型（与 v0 EpisodeCollectionType 对齐）。 */
object BangumiEpisodeCollectionType {
    const val WISH: Int = 1
    const val DONE: Int = 2
    const val DROPPED: Int = 3
}

/** 章节类型（EpType）。 */
object BangumiEpisodeType {
    const val MAIN: Int = 0
}

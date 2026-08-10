package com.acgcompass.data.remote.vndb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * VNDB API v2「Kana」DTO 集合。
 *
 * VNDB 是 **POST-JSON 查询型 HTTP API**（endpoint：
 * [com.acgcompass.core.network.NetworkConstants.VNDB_BASE_URL]`vn`）。查询体 [VndbQueryRequestDto]
 * 描述 `filters` / `fields` / 排序 / 分页，响应 [VndbVnResponseDto] 以 `{ results, more, count? }` 包裹。
 *
 * 设计约束：VNDB schema 会演进（见官方 Change Log），故**所有响应字段
 * 均可空或带默认值**，配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys` +
 * `coerceInputValues`）做向后兼容解析。
 *
 * 官方约定：`rating`（贝叶斯均分 10–100）/ `votecount` 缓存；无人投票时 `rating` 为 `null`，
 * 因此 mapper 在 `rating==null` 或 `votecount<=0` 时按「暂无数据」处理，绝不以 0 伪造评分。
 */

/**
 * `POST /vn` 查询请求体。成员均可选（服务端有默认值）；本项目只用到 `filters`/`fields` 与少量分页。
 *
 * - [filters]：三元谓词数组（如 `["id","=","v17"]`、`["search","=","关键字"]`），可用 `and`/`or`
 *   组合。以 [JsonElement] 承载，由 [VndbFilters] 构造，保证传输任意合法过滤结构而不臆造字段。
 * - [fields]：逗号分隔字段串（支持 `image{url,sexual}` 嵌套），顶层 `id` 恒返回。
 * - [results]：每页条数（≤100）；[page] 从 1 起；[sort]/[reverse] 排序。
 */
@Serializable
data class VndbQueryRequestDto(
    @SerialName("filters") val filters: JsonElement,
    @SerialName("fields") val fields: String,
    @SerialName("sort") val sort: String? = null,
    @SerialName("reverse") val reverse: Boolean? = null,
    @SerialName("results") val results: Int? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("count") val count: Boolean? = null,
)

/**
 * `POST /vn` 响应信封：[results] 为命中条目数组；[more] 为 `true` 时递增 `page` 可取更多；
 * [count] 仅在请求 `count:true` 时存在。
 */
@Serializable
data class VndbVnResponseDto(
    @SerialName("results") val results: List<VndbVnDto> = emptyList(),
    @SerialName("more") val more: Boolean = false,
    @SerialName("count") val count: Int? = null,
)

/**
 * VN 图片对象（`image`，可空）。[sexual] 为 **0–2 的成人内容评分均值**（0 安全 / 1 暗示 / 2 露骨），
 * 用于成人内容分级过滤；[violence] 同理为暴力评分均值。
 */
@Serializable
data class VndbImageDto(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("sexual") val sexual: Float? = null,
    @SerialName("violence") val violence: Float? = null,
    @SerialName("votecount") val voteCount: Int? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("dims") val dimensions: List<Int> = emptyList(),
    @SerialName("thumbnail_dims") val thumbnailDimensions: List<Int> = emptyList(),
)

@Serializable
data class VndbTagDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String? = null,
    @SerialName("rating") val rating: Float = 0f,
    @SerialName("spoiler") val spoiler: Int = 0,
    @SerialName("lie") val lie: Boolean = false,
)

@Serializable
data class VndbProducerDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("original") val original: String? = null,
    @SerialName("type") val type: String? = null,
)

@Serializable
data class VndbStaffDto(
    @SerialName("id") val id: String = "",
    @SerialName("aid") val aliasId: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("original") val original: String? = null,
    @SerialName("role") val role: String = "",
    @SerialName("note") val note: String? = null,
)

@Serializable
data class VndbCharacterDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("original") val original: String? = null,
)

@Serializable
data class VndbVoiceActorDto(
    @SerialName("note") val note: String? = null,
    @SerialName("staff") val staff: VndbStaffDto? = null,
    @SerialName("character") val character: VndbCharacterDto? = null,
)

@Serializable
data class VndbRelationDto(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("alttitle") val altTitle: String? = null,
    @SerialName("relation") val relation: String = "",
    @SerialName("relation_official") val official: Boolean = false,
    @SerialName("rating") val rating: Float? = null,
    @SerialName("votecount") val voteCount: Int? = null,
    @SerialName("image") val image: VndbImageDto? = null,
)

/** VN 标题条目（`titles[]`）：每种语言至多一条；[latin] 为罗马音版本，[main] 标记主标题。 */
@Serializable
data class VndbTitleDto(
    @SerialName("lang") val lang: String? = null,
    @SerialName("title") val title: String = "",
    @SerialName("latin") val latin: String? = null,
    @SerialName("official") val official: Boolean = false,
    @SerialName("main") val main: Boolean = false,
)

/**
 * VN 条目（`POST /vn` 的 `results` 元素）。
 *
 * 评分相关：[rating]（**贝叶斯均分，10–100，无人投票为 `null`**）、[votecount]（投票数）、
 * [average]（原始均分 10–100，可空）。体量：[length]（1–5 粗略时长）/ [lengthMinutes]（实测均值分钟）。
 */
@Serializable
data class VndbVnDto(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String? = null,
    @SerialName("alttitle") val altTitle: String? = null,
    @SerialName("titles") val titles: List<VndbTitleDto> = emptyList(),
    @SerialName("aliases") val aliases: List<String> = emptyList(),
    @SerialName("olang") val olang: String? = null,
    @SerialName("released") val released: String? = null,
    @SerialName("devstatus") val developmentStatus: Int? = null,
    @SerialName("languages") val languages: List<String> = emptyList(),
    @SerialName("platforms") val platforms: List<String> = emptyList(),
    @SerialName("length") val length: Int? = null,
    @SerialName("length_minutes") val lengthMinutes: Int? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("image") val image: VndbImageDto? = null,
    @SerialName("rating") val rating: Float? = null,
    @SerialName("votecount") val voteCount: Int? = null,
    @SerialName("average") val average: Float? = null,
    @SerialName("screenshots") val screenshots: List<VndbImageDto> = emptyList(),
    @SerialName("relations") val relations: List<VndbRelationDto> = emptyList(),
    @SerialName("tags") val tags: List<VndbTagDto> = emptyList(),
    @SerialName("developers") val developers: List<VndbProducerDto> = emptyList(),
    @SerialName("staff") val staff: List<VndbStaffDto> = emptyList(),
    @SerialName("va") val voiceActors: List<VndbVoiceActorDto> = emptyList(),
)

/**
 * `POST /vn` 请求的 `fields` 串：仅显式列出本项目所需字段（无通配符，需逐项声明，含嵌套）。
 * 顶层 `id` 恒返回，无需列出。
 */
object VndbFieldSets {
    const val VN_COMPACT: String =
        "title, alttitle, titles{lang,title,latin,official,main}, aliases, olang, released, " +
            "length_minutes, image{url,sexual,violence,thumbnail,dims,thumbnail_dims}, " +
            "rating, votecount, tags{id,category,rating,spoiler,lie}"

    const val VN_DETAIL: String =
        "title, alttitle, titles{lang,title,latin,official,main}, aliases, olang, " +
            "released, devstatus, languages, platforms, length, length_minutes, description, " +
            "image{url,sexual,violence,votecount,thumbnail,dims,thumbnail_dims}, rating, votecount, average, " +
            "screenshots{id,url,sexual,violence,votecount,thumbnail,dims,thumbnail_dims}, " +
            "relations{id,title,alttitle,relation,relation_official,rating,votecount,image{url,sexual,violence,thumbnail,dims,thumbnail_dims}}, " +
            "tags{id,name,category,rating,spoiler,lie}, developers{id,name,original,type}, " +
            "staff{id,aid,name,original,role,note}, " +
            "va{note,staff{id,aid,name,original},character{id,name,original}}"
}

/** `POST /vn` 的 `sort` 合法取值（仅保留本项目可能用到的）。 */
object VndbSort {
    const val ID: String = "id"
    const val SEARCH_RANK: String = "searchrank"
    const val RATING: String = "rating"
    const val VOTE_COUNT: String = "votecount"
    const val RELEASED: String = "released"
}

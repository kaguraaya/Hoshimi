package com.acgcompass.domain.detail

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class DetailCharacter(
    val name: String,
    val role: String? = null,
    val actor: String? = null,
)

@Serializable
data class DetailStaff(
    val name: String,
    val role: String? = null,
)

@Serializable
data class DetailRelation(
    val id: Int,
    val type: Int? = null,
    val name: String,
    val nameCn: String = "",
    val relation: String? = null,
)

@Serializable
data class DetailComment(
    val nickname: String,
    val rate: Int,
    val text: String,
)

@Serializable
data class DetailExtrasData(
    val characters: List<DetailCharacter> = emptyList(),
    val staff: List<DetailStaff> = emptyList(),
    val relations: List<DetailRelation> = emptyList(),
    val comments: List<DetailComment> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 角色 / Staff / 关联作品这次没拉到（不写入缓存）。 */
    @Transient val creditsFailed: Boolean = false,
    /** 短评这次没拉到（不写入缓存）。 */
    @Transient val commentsFailed: Boolean = false,
    /** 这次没拉到的原因（不写入缓存）。 */
    @Transient val failure: ExtrasFailure? = null,
)

/** Bangumi 扩展资料没拉到的原因，详情页按原因给不同的说明。 */
enum class ExtrasFailure {
    /** Bangumi 返回「请求太频繁」。 */
    RATE_LIMITED,

    /** 连不上 Bangumi（断网、超时）。 */
    NETWORK,

    /** 其它（服务器出错、返回的数据解析不了）。 */
    OTHER,
}

object DetailCacheTtl {
    const val PERSONAL_MILLIS = 15L * 60L * 1_000L
    const val COMMENTS_MILLIS = 6L * 60L * 60L * 1_000L
    const val MISSING_RATING_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    /** 放送中或近一年的作品：平台上「暂无评分」常常只是还没攒够人数，一天后就再查。 */
    const val MISSING_RATING_RECENT_MILLIS = 24L * 60L * 60L * 1_000L
    const val BASE_MILLIS = 24L * 60L * 60L * 1_000L
    const val CREDITS_RELATIONS_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    /** 部分请求失败时的短缓存，下次打开详情会再补。 */
    const val PARTIAL_MILLIS = 10L * 60L * 1_000L
}

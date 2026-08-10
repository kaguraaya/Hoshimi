package com.acgcompass.domain.detail

import kotlinx.serialization.Serializable

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
)

object DetailCacheTtl {
    const val PERSONAL_MILLIS = 15L * 60L * 1_000L
    const val COMMENTS_MILLIS = 6L * 60L * 60L * 1_000L
    const val MISSING_RATING_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val BASE_MILLIS = 24L * 60L * 60L * 1_000L
    const val CREDITS_RELATIONS_MILLIS = 7L * 24L * 60L * 60L * 1_000L
}

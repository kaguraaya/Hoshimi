package com.acgcompass.domain.visualnovel

import com.acgcompass.domain.model.Work
import kotlinx.serialization.Serializable

enum class VisualNovelSort {
    RATING,
    POPULARITY,
    RELEASE_DATE,
    RANDOM,
}

data class VisualNovelListItem(
    val work: Work,
    val rating: Float? = null,
    val voteCount: Int = 0,
    val adult: Boolean = false,
    val previewUrl: String? = work.coverUrl,
    val originalUrl: String? = work.coverUrl,
)

data class VisualNovelPage(
    val items: List<VisualNovelListItem>,
    val page: Int,
    val hasMore: Boolean,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val refreshedAt: Long? = null,
    val refreshFailed: Boolean = false,
)

@Serializable
data class VisualNovelImage(
    val id: String? = null,
    val url: String,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailWidth: Int? = null,
    val thumbnailHeight: Int? = null,
    val sexual: Float = 0f,
    val violence: Float = 0f,
) {
    val sensitive: Boolean get() = sexual >= 1f || violence >= 1.5f
}

@Serializable
data class VisualNovelCredit(
    val id: String,
    val name: String,
    val originalName: String? = null,
    val role: String? = null,
    val note: String? = null,
)

@Serializable
data class VisualNovelVoiceRole(
    val characterId: String,
    val characterName: String,
    val actorId: String,
    val actorName: String,
)

@Serializable
data class VisualNovelRelation(
    val id: String,
    val title: String,
    val relation: String,
    val official: Boolean,
    val rating: Float? = null,
    val voteCount: Int = 0,
)

@Serializable
data class VisualNovelTag(
    val id: String,
    val name: String,
    val rating: Float,
    val spoilerLevel: Int,
    val category: String? = null,
)

@Serializable
data class VisualNovelDetail(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val description: String? = null,
    val released: String? = null,
    val languages: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val rating: Float? = null,
    val voteCount: Int = 0,
    val bangumiRating: Float? = null,
    val bangumiVoteCount: Int = 0,
    val bangumiSubjectId: Int? = null,
    val bangumiSummary: String? = null,
    val translatedDescription: String? = null,
    val lengthMinutes: Int? = null,
    val cover: VisualNovelImage? = null,
    val screenshots: List<VisualNovelImage> = emptyList(),
    val tags: List<VisualNovelTag> = emptyList(),
    val developers: List<VisualNovelCredit> = emptyList(),
    val staff: List<VisualNovelCredit> = emptyList(),
    val voiceRoles: List<VisualNovelVoiceRole> = emptyList(),
    val relations: List<VisualNovelRelation> = emptyList(),
    val adult: Boolean = false,
)

data class VisualNovelDetailSnapshot(
    val detail: VisualNovelDetail,
    val stale: Boolean,
)

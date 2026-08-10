package com.acgcompass.domain.detail

/** A personal record owned by the user, independent of its storage or sync implementation. */
data class PersonalRecord(
    val id: String,
    val source: String,
    val sourceItemId: String,
    val workId: String,
    val status: String?,
    val rating: Int?,
    val progress: Int?,
    val comment: String?,
    val tags: List<String>,
    val updatedAt: Long,
    val syncedAt: Long,
    val sourceUpdatedAt: String?,
    val isPrivate: Boolean,
)

/** Minimal rated-work context used to explain a taste match without exposing persistence models. */
data class RatedWorkContext(
    val title: String,
    val rating: Int,
    val comment: String?,
    val updatedAt: Long,
)

/** Fields supported by Bangumi's collection update endpoint. */
data class BangumiRecordUpdate(
    val type: Int? = null,
    val rating: Int? = null,
    val comment: String? = null,
    val tags: List<String>? = null,
    val isPrivate: Boolean? = null,
)

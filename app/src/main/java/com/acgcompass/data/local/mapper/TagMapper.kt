package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory

/**
 * Entity ↔ domain-model mappers for [Tag] .
 *
 * The persisted [TagEntity.category] is a String. A row whose category cannot be
 * parsed is dropped (mapped to `null`) rather than guessed, so corrupt data never
 * fabricates a category . Use [toDomainList] to map and filter in one step.
 */

fun TagEntity.toDomain(): Tag? {
    val parsed = TagCategory.fromStorage(category) ?: return null
    return Tag(category = parsed, name = name)
}

/** Maps a list of tag entities to domain tags, silently dropping unparseable rows. */
fun List<TagEntity>.toDomainList(): List<Tag> = mapNotNull { it.toDomain() }

/**
 * Maps a domain [Tag] to its persistable entity. The stable [id] must be provided
 * by the caller (taxonomy ids are managed by the repository).
 */
fun Tag.toEntity(id: String): TagEntity = TagEntity(
    id = id,
    category = category.name,
    name = name,
)

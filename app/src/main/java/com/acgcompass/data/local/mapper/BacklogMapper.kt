package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.Priority

/**
 * Entity ↔ domain-model mappers for [BacklogItem] .
 *
 * [BacklogItemEntity.priority] is a String; an unparseable value falls back to
 * [Priority.MEDIUM] .
 */

fun BacklogItemEntity.toDomain(): BacklogItem = BacklogItem(
    workId = workId,
    priority = Priority.fromStorage(priority),
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

fun BacklogItem.toEntity(): BacklogItemEntity = BacklogItemEntity(
    workId = workId,
    priority = priority.name,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

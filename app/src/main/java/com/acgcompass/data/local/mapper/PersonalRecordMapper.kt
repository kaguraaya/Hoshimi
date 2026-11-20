package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.RecommendationExposure

/** 个人收藏：数据库行 ↔ 领域模型（字段一一对应，`localWorkId` 对应 `workId`）。 */
fun UserCollectionEntity.toDomain(): PersonalRecord = PersonalRecord(
    id = id,
    source = source,
    sourceItemId = sourceItemId,
    workId = localWorkId,
    status = status,
    rating = rating,
    progress = progress,
    comment = comment,
    tags = tags,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
    sourceUpdatedAt = sourceUpdatedAt,
    isPrivate = isPrivate,
)

fun PersonalRecord.toEntity(): UserCollectionEntity = UserCollectionEntity(
    id = id,
    source = source,
    sourceItemId = sourceItemId,
    localWorkId = workId,
    status = status,
    rating = rating,
    progress = progress,
    comment = comment,
    tags = tags,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
    sourceUpdatedAt = sourceUpdatedAt,
    isPrivate = isPrivate,
)

/** 推荐曝光：数据库行 ↔ 领域模型。 */
fun RecommendationExposureEntity.toDomain(): RecommendationExposure =
    RecommendationExposure(id, subjectId, context, exposedAt, clickedAt, dismissedAt)

fun RecommendationExposure.toEntity(): RecommendationExposureEntity =
    RecommendationExposureEntity(id, subjectId, context, exposedAt, clickedAt, dismissedAt)

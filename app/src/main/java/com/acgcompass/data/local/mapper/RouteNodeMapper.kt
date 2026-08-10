package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.RouteNodeEntity
import com.acgcompass.domain.ai.RouteRecommendation
import com.acgcompass.domain.model.RouteNode
import com.acgcompass.domain.model.RouteRelationType

/**
 * Entity ↔ domain-model mappers for [RouteNode] .
 *
 * Enum-ish fields persisted as String (relationType / watchRecommendation) are
 * parsed here at the persistence ↔ domain boundary. Unknown / corrupt String
 * values fall back to safe defaults instead of throwing :
 * - relationType → [RouteRelationType.OTHER]
 * - watchRecommendation → [RouteRecommendation.OPTIONAL]
 */

fun RouteNodeEntity.toDomain(): RouteNode = RouteNode(
    id = id,
    seriesId = seriesId,
    workId = workId,
    relationType = RouteRelationType.fromStorage(relationType),
    recommendation = RouteRecommendation.fromRaw(watchRecommendation),
    orderIndex = orderIndex,
    confirmed = confirmed,
)

fun RouteNode.toEntity(): RouteNodeEntity = RouteNodeEntity(
    id = id,
    seriesId = seriesId,
    workId = workId,
    relationType = relationType.name,
    watchRecommendation = recommendation.name,
    orderIndex = orderIndex,
    confirmed = confirmed,
)

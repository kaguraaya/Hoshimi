package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.AiResultEntity
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType

/**
 * Entity ↔ domain-model mappers for [AiResult] .
 *
 * String-backed enums (taskType / generator) fall back to safe defaults on unknown
 * values . [AiResultEntity.dataSources] is a comma-separated String of
 * source tags; it is split/joined here, tolerating blanks and `null`.
 */

private const val DATA_SOURCES_DELIMITER = ","

fun AiResultEntity.toDomain(): AiResult = AiResult(
    id = id,
    workId = workId,
    taskType = AiTaskType.fromStorage(taskType),
    generator = AiGenerator.fromStorage(generator),
    payloadJson = payloadJson,
    confidence = confidence,
    dataSources = dataSources
        ?.split(DATA_SOURCES_DELIMITER)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList(),
    generatedAt = generatedAt,
    inputFingerprint = inputFingerprint,
    promptVersion = promptVersion,
)

fun AiResult.toEntity(): AiResultEntity = AiResultEntity(
    id = id,
    workId = workId,
    taskType = taskType.name,
    generator = generator.name,
    payloadJson = payloadJson,
    confidence = confidence,
    dataSources = dataSources
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(DATA_SOURCES_DELIMITER),
    generatedAt = generatedAt,
    inputFingerprint = inputFingerprint,
    promptVersion = promptVersion,
)

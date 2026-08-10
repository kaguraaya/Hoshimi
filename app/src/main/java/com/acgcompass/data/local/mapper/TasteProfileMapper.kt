package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.TasteTagStat

/**
 * Entity ↔ domain-model mappers for [TasteProfile] and [TasteTagStat] .
 *
 * Per-tag stats live in a separate table ([TasteTagStatEntity]); [toDomain] takes
 * the already-loaded stats and [toEntities] splits a profile back into its profile
 * row plus stat rows. [TasteTagStatEntity.bucket] is a String enum with a safe
 * fallback .
 */

fun TasteTagStatEntity.toDomain(): TasteTagStat = TasteTagStat(
    tagName = tagName,
    bucket = TagBucket.fromStorage(bucket),
    count = count,
)

fun TasteProfileEntity.toDomain(stats: List<TasteTagStatEntity> = emptyList()): TasteProfile = TasteProfile(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
    tagStats = stats.map { it.toDomain() },
)

/** Maps the scalar profile fields to the profile entity (excludes per-tag stats). */
fun TasteProfile.toEntity(): TasteProfileEntity = TasteProfileEntity(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
)

/**
 * Maps the profile's per-tag stats to their entity rows. Stable stat ids are
 * supplied by [idFor] (defaulting to "{profileId}:{bucket}:{tagName}").
 */
fun TasteProfile.toStatEntities(
    idFor: (TasteTagStat) -> String = { "$id:${it.bucket.name}:${it.tagName}" },
): List<TasteTagStatEntity> = tagStats.map { stat ->
    TasteTagStatEntity(
        id = idFor(stat),
        profileId = id,
        tagName = stat.tagName,
        bucket = stat.bucket.name,
        count = stat.count,
    )
}

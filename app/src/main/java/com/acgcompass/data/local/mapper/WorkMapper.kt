package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work

/**
 * Entity ↔ domain-model mappers for [Work] .
 *
 * Enum fields persisted as String (mediaType / status / completionCostBucket /
 * primarySource) are converted here at the persistence ↔ domain boundary so the
 * Room layer stays decoupled from domain enums. Unknown / corrupt String values
 * fall back to safe defaults instead of throwing .
 *
 * Tags are not stored on [WorkEntity] (they live in tags + work_tags join tables),
 * so [toDomain] takes the already-resolved [tags]; [toEntity] takes persistence
 * timestamps that are not part of the domain model.
 */

/** Default media type used only when a stored value is unknown / corrupt . */
private val DEFAULT_MEDIA_TYPE = MediaType.ANIME

/** Default primary source used only when a stored value is unknown / corrupt . */
private val DEFAULT_PRIMARY_SOURCE = SourceId.BANGUMI

fun WorkEntity.toDomain(tags: List<Tag> = emptyList()): Work = Work(
    id = id,
    titles = Titles(
        canonical = canonicalTitle,
        cn = titleCn,
        ja = titleJa,
        romaji = titleRomaji,
        en = titleEn,
        aliases = aliases,
    ),
    mediaType = MediaType.fromStorage(mediaType) ?: DEFAULT_MEDIA_TYPE,
    year = year,
    status = ReleaseStatus.fromStorage(status),
    units = Units(
        episodes = episodes,
        episodeMinutes = episodeMinutes,
        volumes = volumes,
        estPlayMinutes = estPlayMinutes,
    ),
    coverUrl = coverUrl,
    primarySource = SourceId.fromStorage(primarySource) ?: DEFAULT_PRIMARY_SOURCE,
    completionCost = CompletionCost.fromStorage(completionCostBucket),
    tags = tags,
    summary = summary,
    airDate = airDate,
)

/**
 * Maps a domain [Work] to its persistable entity. Persistence-only timestamps
 * must be supplied by the caller (e.g. the repository), defaulting to "now".
 */
fun Work.toEntity(createdAt: Long = System.currentTimeMillis(), updatedAt: Long = createdAt): WorkEntity = WorkEntity(
    id = id,
    canonicalTitle = titles.canonical,
    titleJa = titles.ja,
    titleRomaji = titles.romaji,
    titleEn = titles.en,
    titleCn = titles.cn,
    aliases = titles.aliases,
    mediaType = mediaType.name,
    year = year,
    status = status.name,
    episodes = units.episodes,
    episodeMinutes = units.episodeMinutes,
    volumes = units.volumes,
    estPlayMinutes = units.estPlayMinutes,
    coverUrl = coverUrl,
    primarySource = primarySource.name,
    completionCostBucket = completionCost?.name,
    summary = summary,
    airDate = airDate,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

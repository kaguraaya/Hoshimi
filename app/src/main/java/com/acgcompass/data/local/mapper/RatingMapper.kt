package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.domain.model.Consensus
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId

/**
 * Entity ↔ domain-model mappers for ratings .
 *
 * Per-source [RatingEntity] rows are aggregated into a [RatingAggregate]. A row
 * flagged [RatingEntity.missing] becomes a `null` entry in `perSource` and its
 * value is NEVER back-filled from another source (). Rows
 * whose sourceId cannot be parsed are dropped .
 */

/** Maps a single rating row to a domain [RatingEntry], or `null` when marked missing. */
fun RatingEntity.toEntryOrNull(): RatingEntry? = if (missing) {
    null
} else {
    RatingEntry(score = score, voteCount = voteCount, rank = rank)
}

/**
 * Aggregates per-source rating rows into a [RatingAggregate].
 *
 * The latest row (by [RatingEntity.fetchedAt]) wins per source. [consensus] is
 * supplied by the domain/use-case layer (it is derived, not persisted on a row),
 * and stays `null` when there is not enough signal .
 */
fun List<RatingEntity>.toRatingAggregate(consensus: Consensus? = null): RatingAggregate {
    val perSource: Map<SourceId, RatingEntry?> =
        this
            .mapNotNull { row -> SourceId.fromStorage(row.sourceId)?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) -> rows.maxByOrNull { it.fetchedAt }!!.toEntryOrNull() }
    return RatingAggregate(perSource = perSource, consensus = consensus)
}

/**
 * Maps a domain [RatingEntry] to a persistable [RatingEntity] (a present rating).
 * Persistence-only identifiers/metadata must be supplied by the caller.
 */
fun RatingEntry.toEntity(
    id: String,
    workId: String,
    sourceId: SourceId,
    fetchedAt: Long = System.currentTimeMillis(),
): RatingEntity = RatingEntity(
    id = id,
    workId = workId,
    sourceId = sourceId.name,
    score = score,
    voteCount = voteCount,
    rank = rank,
    fetchedAt = fetchedAt,
    missing = false,
)

/**
 * Builds a "missing" rating row for an explicitly absent source so the absence is
 * persisted (and never confused with "not yet fetched").  / .
 */
fun missingRatingEntity(
    id: String,
    workId: String,
    sourceId: SourceId,
    fetchedAt: Long = System.currentTimeMillis(),
): RatingEntity = RatingEntity(
    id = id,
    workId = workId,
    sourceId = sourceId.name,
    score = 0f,
    voteCount = 0,
    rank = null,
    fetchedAt = fetchedAt,
    missing = true,
)

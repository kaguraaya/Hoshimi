package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef

/**
 * Entity ↔ domain-model mappers between [SourceLinkEntity] and [SourceRef] .
 *
 * A link whose sourceId cannot be parsed is dropped (mapped to `null`).
 * The [SourceLinkEntity.id] and [SourceLinkEntity.workId] are persistence concerns;
 * [toEntity] therefore requires them from the caller.
 */

fun SourceLinkEntity.toSourceRef(): SourceRef? {
    val parsed = SourceId.fromStorage(sourceId) ?: return null
    return SourceRef(
        sourceId = parsed,
        sourceItemId = sourceItemId,
        matchConfidence = matchConfidence,
        userOverridden = userOverridden,
    )
}

fun SourceRef.toEntity(id: String, workId: String, linkedAt: Long = System.currentTimeMillis()): SourceLinkEntity =
    SourceLinkEntity(
        id = id,
        workId = workId,
        sourceId = sourceId.name,
        sourceItemId = sourceItemId,
        matchConfidence = matchConfidence,
        userOverridden = userOverridden,
        linkedAt = linkedAt,
    )

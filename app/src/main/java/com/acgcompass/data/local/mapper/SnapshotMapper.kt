package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.domain.model.ChangeLog
import com.acgcompass.domain.model.ChangeType
import com.acgcompass.domain.model.Snapshot
import com.acgcompass.domain.model.SnapshotKind

/**
 * Entity ↔ domain-model mappers for time-machine models :
 * [Snapshot] and [ChangeLog]. String-backed enums (kind / changeType) fall back to
 * safe defaults on unknown values .
 */

fun SnapshotEntity.toDomain(): Snapshot = Snapshot(
    id = id,
    takenAt = takenAt,
    kind = SnapshotKind.fromStorage(kind),
    payloadHash = payloadHash,
)

fun Snapshot.toEntity(): SnapshotEntity = SnapshotEntity(
    id = id,
    takenAt = takenAt,
    kind = kind.name,
    payloadHash = payloadHash,
)

fun ChangeLogEntity.toDomain(): ChangeLog = ChangeLog(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = ChangeType.fromStorage(changeType),
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

fun ChangeLog.toEntity(): ChangeLogEntity = ChangeLogEntity(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = changeType.name,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

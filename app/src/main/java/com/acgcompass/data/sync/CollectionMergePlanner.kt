package com.acgcompass.data.sync

import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.repository.CollectionRemoval
import java.time.Instant
import java.time.OffsetDateTime

/** 一条远端收藏：映射好的本地实体，以及它在 Bangumi 上的更新时间（毫秒，解析失败为 null）。 */
data class RemoteCollection(val entity: UserCollectionEntity, val updatedAtMillis: Long?) {
    val workId: String get() = entity.localWorkId
}

/**
 * 一次合并的结果。
 *
 * @property upserts 以远端为准写入本地的记录。
 * @property pushLocal 本地有更新的修改，保留本地并推到 Bangumi 的作品。
 * @property keptRemoved 在应用里移除过、远端之后没再改过，继续保持移除的作品。
 * @property clearRemovals 远端在移除之后又改过（以远端为准），要删掉移除标记的作品。
 * @property remoteRemoved Bangumi 上已经删除、要移进回收站的作品。
 * @property obsoleteMarkers 远端也没有了的过期移除标记，可以删掉。
 */
data class CollectionMergePlan(
    val upserts: List<UserCollectionEntity>,
    val pushLocal: List<String>,
    val keptRemoved: List<String>,
    val clearRemovals: List<String>,
    val remoteRemoved: List<String>,
    val obsoleteMarkers: List<String>,
)

/**
 * Bangumi 收藏与本地记录的合并规则：**以较新的一方为准**。
 *
 * 远端每一条：
 * - 在应用里移除过：远端更新时间晚于移除时间 → 以远端为准（加回来，删掉移除标记）；否则保持移除。
 * - 本地有还没推上去的修改（`updatedAt > syncedAt`）：远端更晚 → 用远端覆盖；否则保留本地并推送。
 * - 其它 → 用远端覆盖本地。
 *
 * 本地有、远端没有的 Bangumi 收藏，只有在 [complete]（这次确实拿全了全部收藏）时才处理：
 * - 在「想看」待推队列里、或本地有还没推上去的修改 → 保留并推送（本地的修改更新）；
 * - 从没在远端出现过（`sourceUpdatedAt` 为空，比如关闭了想看同步时本地加的）→ 保留，它本来就只在本地；
 * - 其它 → Bangumi 上已经删除，移进回收站。
 */
fun planCollectionMerge(
    remote: List<RemoteCollection>,
    local: Map<String, UserCollectionEntity>,
    removals: Map<String, CollectionRemoval>,
    pendingWish: Set<String>,
    complete: Boolean,
): CollectionMergePlan {
    val plan = MergePlanBuilder()
    remote.forEach { plan.mergeRemote(it, local[it.workId], removals[it.workId]) }
    if (complete) {
        val remoteIds = remote.mapTo(HashSet()) { it.workId }
        local.values
            .filter { it.source == BANGUMI_SOURCE && it.localWorkId !in remoteIds }
            .forEach { plan.mergeLocalOnly(it, pendingWish) }
        removals.filter { (workId, removal) -> workId !in remoteIds && removal.purged }
            .keys
            .forEach { plan.obsoleteMarkers += it }
    }
    return plan.build()
}

private class MergePlanBuilder {
    val upserts = mutableListOf<UserCollectionEntity>()
    val pushLocal = mutableListOf<String>()
    val keptRemoved = mutableListOf<String>()
    val clearRemovals = mutableListOf<String>()
    val remoteRemoved = mutableListOf<String>()
    val obsoleteMarkers = mutableListOf<String>()

    fun mergeRemote(r: RemoteCollection, l: UserCollectionEntity?, removal: CollectionRemoval?) {
        val remoteAt = r.updatedAtMillis
        when {
            removal != null && remoteAt != null && remoteAt > removal.removedAt -> {
                clearRemovals += r.workId
                upserts += r.entity
            }
            removal != null -> keptRemoved += r.workId
            l != null && l.isUnsynced() && (remoteAt == null || remoteAt <= l.updatedAt) -> pushLocal += r.workId
            else -> upserts += r.entity
        }
    }

    fun mergeLocalOnly(l: UserCollectionEntity, pendingWish: Set<String>) {
        when {
            l.localWorkId in pendingWish || l.isUnsynced() -> pushLocal += l.localWorkId
            l.sourceUpdatedAt == null -> Unit
            else -> remoteRemoved += l.localWorkId
        }
    }

    fun build() = CollectionMergePlan(upserts, pushLocal, keptRemoved, clearRemovals, remoteRemoved, obsoleteMarkers)
}

/** 本地有还没推上去的修改。 */
internal fun UserCollectionEntity.isUnsynced(): Boolean = updatedAt > syncedAt

/** 解析 Bangumi 的 ISO 8601 时间（带时区偏移或 Z），失败返回 null。 */
internal fun parseBangumiTime(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(value).toEpochMilli() }
        .getOrNull()
}

private const val BANGUMI_SOURCE = "BANGUMI"

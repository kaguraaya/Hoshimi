package com.acgcompass.data.sync

import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiUserSubjectCollectionDto

/**
 * Bangumi 收藏 DTO → 本地收藏实体；没有条目 id 时返回 null。写入时间 [now] 同时作为 `updatedAt` 和 `syncedAt`
 * （刚从远端拿到，视为已同步）；Bangumi 上的更新时间保存在 `sourceUpdatedAt`。
 *
 * 收藏标签属于用户自己的记录，作品的社区标签另由条目资料写入，两者分开保存。
 */
internal fun BangumiUserSubjectCollectionDto.toCollectionEntity(now: Long): UserCollectionEntity? {
    val subject = subjectId.takeIf { it > 0 } ?: return null
    val workId = subject.toString()
    return UserCollectionEntity(
        id = "BANGUMI:$subject",
        source = CredentialSourceId.BANGUMI.name,
        sourceItemId = workId,
        localWorkId = workId,
        status = bangumiStatusLabel(type),
        rating = rate.takeIf { it in 1..MAX_RATING },
        progress = epStatus.takeIf { it > 0 },
        comment = comment?.takeIf { it.isNotBlank() },
        tags = tags.map(::cleanCollectionTag).filter(String::isNotBlank).distinct(),
        updatedAt = now,
        syncedAt = now,
        sourceUpdatedAt = updatedAt,
        isPrivate = private,
    )
}

/** Bangumi 收藏 type 与状态文字：1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃。 */
private val BANGUMI_STATUS_LABELS: List<String> = listOf("想看", "看过", "在看", "搁置", "抛弃")

/** Bangumi 收藏 type → 状态文字；未知返回 null。 */
internal fun bangumiStatusLabel(type: Int?): String? = type?.let { BANGUMI_STATUS_LABELS.getOrNull(it - 1) }

/** 状态文字 → Bangumi 收藏 type；未知返回 null。 */
internal fun bangumiTypeOf(label: String?): Int? = BANGUMI_STATUS_LABELS.indexOf(label).takeIf { it >= 0 }?.plus(1)

/** 标签清洗：下划线和连字符转为空格，折叠多余空白。 */
private fun cleanCollectionTag(raw: String): String =
    raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

private const val MAX_RATING = 10

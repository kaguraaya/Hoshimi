package com.acgcompass.domain.visualnovel

import java.security.MessageDigest

object VisualNovelCachePolicy {
    const val RANKING_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L
    const val DETAIL_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L

    fun rankingIsStale(cachedAt: Long, now: Long): Boolean = now >= cachedAt && now - cachedAt >= RANKING_TTL_MILLIS

    fun pageContentHash(page: VisualNovelPage): String = sha256(
        buildString {
            append(page.hasMore)
            page.items.forEach { item ->
                append('|').append(item.work.id)
                append(':').append(item.work.titles.canonical)
                append(':').append(item.work.titles.cn)
                append(':').append(item.work.titles.ja)
                append(':').append(item.work.coverUrl)
                append(':').append(item.work.year)
                append(':').append(item.rating)
                append(':').append(item.voteCount)
                append(':').append(item.adult)
            }
        },
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

package com.acgcompass.domain.ai

import com.acgcompass.domain.usecase.RadarRequest
import java.security.MessageDigest

/** Stable invalidation policy for spoiler-radar output. Volatile scores and reviews are excluded. */
object RadarCachePolicy {
    const val PROMPT_VERSION = 2

    fun fingerprint(request: RadarRequest): String {
        val stableInput = buildString {
            append(request.title.trim().lowercase()).append('\n')
            append(request.summary.trim().replace(Regex("\\s+"), " ")).append('\n')
            append(request.mediaType.trim().uppercase()).append('\n')
            request.tags.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .forEach { append(it).append('\n') }
            append("prompt:").append(PROMPT_VERSION)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(stableInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

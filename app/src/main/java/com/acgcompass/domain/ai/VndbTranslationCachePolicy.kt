package com.acgcompass.domain.ai

import java.security.MessageDigest

object VndbTranslationCachePolicy {
    const val PROMPT_VERSION: Int = 1

    fun fingerprint(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun isReusable(inputFingerprint: String?, promptVersion: Int, source: String): Boolean =
        inputFingerprint == fingerprint(source) && promptVersion == PROMPT_VERSION
}

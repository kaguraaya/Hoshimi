package com.acgcompass.core.security

import com.acgcompass.core.network.NetworkConstants
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure security checks shared by the Bangumi OAuth UI and its JVM tests. */
object BangumiOAuthSecurity {
    private val allowedAuthorizationHosts = setOf(
        "bgm.tv",
        "www.bgm.tv",
        "bangumi.tv",
        "www.bangumi.tv",
    )

    fun newState(): String {
        val bytes = ByteArray(STATE_BYTE_COUNT)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isExactRedirect(url: String): Boolean {
        val actual = parse(url) ?: return false
        val expected = URI(NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI)
        return actual.scheme.equals(expected.scheme, ignoreCase = true) &&
            actual.host.equals(expected.host, ignoreCase = true) &&
            normalizedPath(actual) == normalizedPath(expected) &&
            actual.fragment == null &&
            actual.userInfo == null &&
            actual.port == UNSPECIFIED_PORT
    }

    fun isAllowedAuthorizationNavigation(url: String, authorizationUrl: String? = null): Boolean {
        val uri = parse(url)
        val configuredOrigin = authorizationUrl?.let(::parse)
        return when {
            uri == null || !isSafeHttpsOrigin(uri) -> false
            uri.host?.lowercase() in allowedAuthorizationHosts && effectivePort(uri) == HTTPS_PORT -> true
            configuredOrigin == null || !isSafeHttpsOrigin(configuredOrigin) -> false
            else -> uri.host.equals(configuredOrigin.host, ignoreCase = true) &&
                effectivePort(uri) == effectivePort(configuredOrigin)
        }
    }

    fun stateMatches(expected: String?, returned: String?): Boolean {
        if (expected.isNullOrBlank() || returned.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            returned.toByteArray(Charsets.UTF_8),
        )
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun isSafeHttpsOrigin(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null

    private fun effectivePort(uri: URI): Int = if (uri.port == UNSPECIFIED_PORT) HTTPS_PORT else uri.port

    private fun normalizedPath(uri: URI): String = uri.path.trimEnd('/').ifEmpty { "/" }

    private const val STATE_BYTE_COUNT = 32
    private const val HTTPS_PORT = 443
    private const val UNSPECIFIED_PORT = -1
}

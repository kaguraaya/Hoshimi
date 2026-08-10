package com.acgcompass.domain.connectivity

/** A user-initiated connectivity probe target. */
enum class ConnectionTarget(
    val documentationUrl: String,
) {
    BANGUMI("https://bangumi.github.io/api/"),
    ANILIST("https://docs.anilist.co/"),
    TENRAI("https://api.tenrai.org/v1/"),
    JIKAN("https://docs.api.jikan.moe/"),
    MAL("https://myanimelist.net/apiconfig/references/api/v2"),
    VNDB("https://api.vndb.org/kana"),
    AI_PROVIDER(""),
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val docUrl: String? = null,
)

interface ConnectionTester {
    suspend fun test(target: ConnectionTarget): ConnectionTestResult
}

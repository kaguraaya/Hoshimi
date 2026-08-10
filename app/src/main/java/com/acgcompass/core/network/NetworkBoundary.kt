package com.acgcompass.core.network

enum class NetworkSource {
    BANGUMI,
    ANILIST,
    TENRAI,
    JIKAN,
    MAL,
    VNDB,
    AI_PROVIDER,
}

data class NetworkCredential(
    val token: String? = null,
    val apiKey: String? = null,
    val clientId: String? = null,
)

interface NetworkCredentialProvider {
    suspend fun get(source: NetworkSource): NetworkCredential?

    /** Called only when an authenticated request is rejected by its source. */
    suspend fun onUnauthorized(source: NetworkSource) = Unit
}

data class BangumiNetworkConfig(
    val baseUrl: String,
    val official: Boolean,
    val allowTokenOnNonOfficial: Boolean,
)

interface BangumiNetworkConfigProvider {
    suspend fun get(): BangumiNetworkConfig
}

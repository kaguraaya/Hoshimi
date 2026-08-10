package com.acgcompass.data.remote.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Google Gemini `generateContent` 响应 DTO。
 *
 * 只解析本应用关心的字段；配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys`）
 * 容忍字段演进，避免反序列化崩溃。出站请求体由 [GeminiProvider] 动态构造。
 */
@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    @SerialName("usageMetadata") val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
    val role: String? = null,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
)

@Serializable
data class GeminiUsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @SerialName("totalTokenCount") val totalTokenCount: Int? = null,
)

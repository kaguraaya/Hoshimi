package com.acgcompass.data.remote.ai

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.asException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini provider 实现（design「AiProvider 抽象」）。
 *
 * 与 OpenAI 兼容形态不同，Gemini 使用 `POST {baseUrl}/v1beta/models/{model}:generateContent`，
 * 鉴权通过 `x-goog-api-key` 请求头注入（**不**用 `Authorization: Bearer`），系统提示词放在
 * `systemInstruction`、任务内容放在 `contents`，结构化输出经 `generationConfig.responseMimeType`
 * + `responseSchema` 控制。故单独映射，不与 OpenAI 兼容实现共享请求构造。
 *
 * **安全约束**：baseUrl / model / key 全部在 [complete] 调用时经 [credentialSource]
 * 从 `CredentialStore` 读取；本类不持有、不缓存、不记录任何明文 key。
 */
class GeminiProvider(
    private val credentialSource: AiCredentialSource,
    private val httpCaller: AiHttpCaller,
    private val json: Json,
) : AiProvider {

    override val id: ProviderId = ProviderId.GEMINI

    override fun supportsStructuredOutput(): Boolean = true

    override suspend fun complete(req: AiRequest): AiRawResponse {
        val config = credentialSource.load()

        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "未配置 Gemini API Key").asException()

        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() } ?: AiDefaults.GEMINI_BASE_URL

        val model = req.model.ifBlank { config.defaultModel.orEmpty() }
            .takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "未配置 Gemini 模型名").asException()

        val url = baseUrl.trimEnd('/') + "/v1beta/models/" + model + ":generateContent"
        val bodyJson = buildRequestBody(req)
        val request = Request.Builder()
            .url(url)
            // 鉴权头（Gemini 用 x-goog-api-key，而非 Authorization Bearer）。
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", APPLICATION_JSON)
            .post(json.encodeToString(bodyJson).toRequestBody(APPLICATION_JSON.toMediaType()))
            .build()

        val rawBody = httpCaller.execute(request)
        return parseResponse(rawBody)
    }

    private fun buildRequestBody(req: AiRequest): JsonObject = buildJsonObject {
        if (req.systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", req.systemPrompt) }
                }
            }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", req.userContent) }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", req.temperature)
            req.maxTokens?.let { put("maxOutputTokens", it) }
            applyResponseFormat(req.responseFormat)
        }
    }

    /**
     * 在 `generationConfig` 内追加结构化输出约束（Gemini 形态）。
     *
     * - [AiResponseFormat.Text]：不设限，纯文本。
     * - [AiResponseFormat.JsonObject]：`responseMimeType = application/json`。
     * - [AiResponseFormat.JsonSchema]：附加 `responseSchema`（best-effort 透传上层构造的 schema）。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.applyResponseFormat(format: AiResponseFormat) {
        when (format) {
            AiResponseFormat.Text -> Unit
            AiResponseFormat.JsonObject -> put("responseMimeType", APPLICATION_JSON)
            is AiResponseFormat.JsonSchema -> {
                put("responseMimeType", APPLICATION_JSON)
                put("responseSchema", format.schema)
            }
        }
    }

    private fun parseResponse(rawBody: String): AiRawResponse {
        val parsed = runCatching { json.decodeFromString<GeminiResponse>(rawBody) }
            .getOrElse { throw AppError.AiMalformed().asException() }
        val candidate = parsed.candidates.firstOrNull()
        val content = candidate?.content?.parts
            ?.firstOrNull { !it.text.isNullOrBlank() }
            ?.text
        if (content.isNullOrBlank()) {
            throw AppError.AiMalformed(cause = "AI 未返回任何内容").asException()
        }
        return AiRawResponse(
            content = content,
            finishReason = mapFinishReason(candidate.finishReason),
            usage = parsed.usageMetadata?.let {
                AiUsage(
                    promptTokens = it.promptTokenCount,
                    completionTokens = it.candidatesTokenCount,
                    totalTokens = it.totalTokenCount,
                )
            },
            rawBody = rawBody,
        )
    }

    private fun mapFinishReason(raw: String?): FinishReason = when (raw?.uppercase()) {
        "STOP" -> FinishReason.STOP
        "MAX_TOKENS" -> FinishReason.LENGTH
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }

    private companion object {
        const val APPLICATION_JSON = "application/json"
    }
}

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
 * OpenAI 兼容 provider 的共享实现（design「AiProvider 抽象」）。
 *
 * 覆盖 [ProviderId.OPENAI] / [ProviderId.DEEPSEEK] / [ProviderId.OPENROUTER] /
 * [ProviderId.CUSTOM_OPENAI_COMPAT] —— 它们都遵循 OpenAI `POST {baseUrl}/chat/completions`
 * 协议、以 `Authorization: Bearer <key>` 鉴权、并（在支持时）通过 `response_format` 请求
 * 结构化输出。差异仅体现在 **默认 baseUrl** 与 **是否支持 `json_schema`** 上，故以构造参数注入，
 * 由 `AiModule` 为每个 [ProviderId] 绑定一个配置好的实例。
 *
 * **安全约束**：baseUrl / model / key 全部在 [complete] 调用时经 [credentialSource]
 * 从 `CredentialStore` 读取；本类不持有、不缓存、不记录任何明文 key。
 *
 * @property id                provider 标识（DI map 键）。
 * @property capabilities 端点能力与默认地址。
 */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    private val capabilities: OpenAiProviderCapabilities,
    private val credentialSource: AiCredentialSource,
    private val httpCaller: AiHttpCaller,
    private val json: Json,
) : AiProvider {

    override fun supportsStructuredOutput(): Boolean = capabilities.structuredOutput

    override suspend fun complete(req: AiRequest): AiRawResponse {
        val config = credentialSource.load()

        // 凭据 / 配置缺失 → Unauthorized（引导用户到设置页），绝不崩溃。
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "未配置 AI API Key").asException()

        val baseUrl = (config.baseUrl?.takeIf { it.isNotBlank() } ?: capabilities.defaultBaseUrl)
            ?: throw AppError.Unauthorized(cause = "未配置自定义 AI 服务地址（Base URL）").asException()

        val model = req.model.ifBlank { config.defaultModel.orEmpty() }
            .takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "未配置 AI 模型名").asException()

        val url = baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH
        val bodyJson = buildRequestBody(req, model, config.thinkingEnabled)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", APPLICATION_JSON)
            .post(json.encodeToString(bodyJson).toRequestBody(APPLICATION_JSON.toMediaType()))
            .build()

        val rawBody = httpCaller.execute(request)
        return parseResponse(rawBody)
    }

    internal fun buildRequestBody(req: AiRequest, model: String, thinkingEnabled: Boolean): JsonObject {
        return buildJsonObject {
            put("model", model)
            put("temperature", req.temperature)
            req.maxTokens?.let { put("max_tokens", it) }
            putJsonArray("messages") {
                if (req.systemPrompt.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", req.systemPrompt)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", req.userContent)
                }
            }
            responseFormatJson(req.responseFormat)?.let { put("response_format", it) }
            // 对支持 thinking 扩展的端点关闭思考模式。部分混合思考模型默认开启思考，
            // 思维链 token 会占用 completion 预算。仅对声明支持的 provider 注入，避免未知字段被拒绝。
            if (capabilities.supportsThinking && !thinkingEnabled) {
                putJsonObject("thinking") { put("type", "disabled") }
            }
        }
    }

    /**
     * 把 [AiResponseFormat] 映射为 OpenAI `response_format`。
     *
     * 不支持 `json_schema` 的兼容端点在收到
     * [AiResponseFormat.JsonSchema] 时降级为 `json_object`，schema 约束由上层 `AiEngine`
     * 写入提示词并在收到结果后自行校验。
     */
    private fun responseFormatJson(format: AiResponseFormat): JsonObject? = when (format) {
        AiResponseFormat.Text -> null
        AiResponseFormat.JsonObject -> buildJsonObject { put("type", "json_object") }
        is AiResponseFormat.JsonSchema ->
            if (capabilities.structuredOutput) {
                buildJsonObject {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", format.name)
                        put("strict", format.strict)
                        put("schema", format.schema)
                    }
                }
            } else {
                buildJsonObject { put("type", "json_object") }
            }
    }

    private fun parseResponse(rawBody: String): AiRawResponse {
        val parsed = runCatching { json.decodeFromString<OpenAiChatResponse>(rawBody) }
            .getOrElse { throw AppError.AiMalformed().asException() }
        val choice = parsed.choices.firstOrNull()
        // content 为空时回落 reasoning_content；推理型模型在较小 token 预算下可能只产出思维链。
        // 让连通性探针不再误判失败；结构化任务若仅得到思维链会在上层 JSON 校验阶段触发「修复」二次请求。
        val content = choice?.message?.content?.takeIf { it.isNotBlank() }
            ?: choice?.message?.reasoningContent?.takeIf { it.isNotBlank() }
        if (content.isNullOrBlank()) {
            throw AppError.AiMalformed(cause = "AI 未返回任何内容").asException()
        }
        return AiRawResponse(
            content = content,
            finishReason = mapFinishReason(choice?.finishReason),
            usage = parsed.usage?.let {
                AiUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            },
            rawBody = rawBody,
        )
    }

    private fun mapFinishReason(raw: String?): FinishReason = when (raw?.lowercase()) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        else -> FinishReason.UNKNOWN
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val APPLICATION_JSON = "application/json"
    }
}

/** OpenAI 兼容端点的稳定能力配置。 */
data class OpenAiProviderCapabilities(
    val defaultBaseUrl: String?,
    val structuredOutput: Boolean,
    val supportsThinking: Boolean = false,
)

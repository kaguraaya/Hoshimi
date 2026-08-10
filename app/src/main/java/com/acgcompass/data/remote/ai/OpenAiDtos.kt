package com.acgcompass.data.remote.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 `chat/completions` 响应 DTO。
 *
 * 只解析本应用关心的字段；配合 [com.acgcompass.core.network.NetworkJson]（`ignoreUnknownKeys`）
 * 容忍各兼容端点（OpenAI / DeepSeek / OpenRouter / 自定义）的字段差异，避免反序列化崩溃。
 *
 * 出站请求体由 [OpenAiCompatibleProvider] 以 `buildJsonObject` 动态构造（因 JSON Schema 为
 * 运行时传入的任意结构），故此处仅定义**响应**侧 DTO。
 */
@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiMessage(
    val role: String? = null,
    val content: String? = null,
    // 推理型模型（部分 Kimi / DeepSeek-R 派生）把思维链放在 reasoning_content；当 content 为空但
    // 本字段非空时（常因 max_tokens 在推理阶段耗尽），仍证明「连通 + 鉴权 + 模型存在」，
    // 供连通性探针判断请求已成功到达模型，避免误报连接失败。
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

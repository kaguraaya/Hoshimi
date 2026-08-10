package com.acgcompass.data.remote.ai

import kotlinx.serialization.json.JsonObject

/**
 * 一次 AI 补全请求的 provider 无关描述。
 *
 * 由上层 `AiEngine`依据任务类型组装：注入强制剧透保护的系统提示词
 * （[systemPrompt]）、任务输入（[userContent]）、目标输出格式（[responseFormat]）以及
 * 模型与采样参数。各 [AiProvider] 负责把它映射为自身的线上协议（OpenAI `chat/completions`
 * 或 Gemini `generateContent`）。
 *
 * @property systemPrompt   系统提示词（含剧透保护与输出约束）。
 * @property userContent    用户/任务内容（评论、标签、简介、列表等已拼装文本）。
 * @property model          目标模型名；为空白时回落到用户配置中的默认模型（[AiEndpointConfig.defaultModel]）。
 * @property temperature    采样温度（0.0–2.0，越低越确定，结构化任务建议偏低）。
 * @property responseFormat 期望的输出格式（纯文本 / JSON 对象 / JSON Schema），见 [AiResponseFormat]。
 * @property maxTokens      可选的最大输出 token 上限（`null` 表示由 provider 默认）。
 */
data class AiRequest(
    val systemPrompt: String,
    val userContent: String,
    val model: String,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val responseFormat: AiResponseFormat = AiResponseFormat.Text,
    val maxTokens: Int? = null,
) {
    companion object {
        /** 结构化任务的默认温度（偏低以提升可解析性与可复现性）。 */
        const val DEFAULT_TEMPERATURE: Double = 0.2
    }
}

/**
 * 期望的输出格式。
 *
 * provider 依据自身能力（[AiProvider.supportsStructuredOutput]）尽力满足：不支持 JSON Schema
 * 的兼容端点会降级为「JSON 对象」模式，schema 约束改由 `AiEngine` 写入提示词并在收到结果后校验。
 */
sealed interface AiResponseFormat {

    /** 纯文本输出（不强制 JSON）。 */
    data object Text : AiResponseFormat

    /** 仅要求合法 JSON 对象（不绑定具体 schema），对应 OpenAI `response_format: json_object`。 */
    data object JsonObject : AiResponseFormat

    /**
     * 要求符合指定 JSON Schema 的结构化输出（对应 OpenAI `response_format: json_schema`
     * 或 Gemini `responseSchema`）。
     *
     * @property name   schema 名称（OpenAI 要求提供）。
     * @property schema JSON Schema 主体（kotlinx.serialization 的 [JsonObject]，由调用方构造）。
     * @property strict 是否要求严格符合（OpenAI `strict`），默认严格。
     */
    data class JsonSchema(
        val name: String,
        // Fully-qualified to avoid shadowing by the sibling [JsonObject] data object
        // declared in this same sealed interface.
        val schema: kotlinx.serialization.json.JsonObject,
        val strict: Boolean = true,
    ) : AiResponseFormat
}

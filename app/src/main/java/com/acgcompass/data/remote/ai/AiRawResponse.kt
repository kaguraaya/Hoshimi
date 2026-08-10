package com.acgcompass.data.remote.ai

/**
 * provider 返回的「原始」补全结果。
 *
 * 这里只做到「取出模型输出的文本/JSON 内容 + 结束原因 + 用量」，**不**做 schema 校验、
 * 修复二次请求或剧透过滤 —— 那些属于上层 `AiEngine`的职责。
 *
 * @property content      模型输出的主体内容（JSON 任务时为 JSON 文本，文本任务时为纯文本）。
 * @property finishReason 结束原因（用于判断是否被截断 / 安全拦截，影响是否触发修复请求）。
 * @property usage        token 用量（用于成本估算 ）；provider 未返回时为 `null`。
 * @property rawBody      provider 完整响应体原文（便于诊断 / 修复请求引用）；可能较大，按需保留。
 */
data class AiRawResponse(
    val content: String,
    val finishReason: FinishReason,
    val usage: AiUsage? = null,
    val rawBody: String? = null,
)

/**
 * 归一化的补全结束原因。各 provider 的原始字符串经 provider 实现映射到此枚举。
 *
 * - [STOP]：正常完成。
 * - [LENGTH]：达到 token 上限被截断（OpenAI `length` / Gemini `MAX_TOKENS`）。
 * - [CONTENT_FILTER]：被安全/内容策略拦截（OpenAI `content_filter` / Gemini `SAFETY`/`RECITATION`）。
 * - [TOOL_CALLS]：因工具调用而停止（本应用一般不使用）。
 * - [UNKNOWN]：未知或缺省。
 */
enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    UNKNOWN,
}

/**
 * token 用量（用于成本估算）。字段可能因 provider 缺省而为 `null`。
 */
data class AiUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

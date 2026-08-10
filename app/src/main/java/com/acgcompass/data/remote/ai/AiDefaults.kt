package com.acgcompass.data.remote.ai

/**
 * 各 AI provider 的**公开**默认 Base URL。
 *
 * 这里**只**放置无需鉴权即可拼接的公开端点根地址，**绝不**包含任何 key / token / secret ——
 * 所有凭据均由用户在设置页自行填写并存入 `CredentialStore`，运行时由 provider 即时注入鉴权头。
 *
 * 用户可在配置中覆盖 [AiEndpointConfig.baseUrl]；[ProviderId.CUSTOM_OPENAI_COMPAT] 无默认值，
 * 必须由用户提供（自建 OpenAI 兼容端点）。
 *
 * > 注意：下列 URL 为基于既有官方文档的设计假设，接入前应联网核验最新文档。
 *
 * 约定：OpenAI 兼容端点的 base 末尾是否带 `/` 不敏感（provider 会归一化后再拼 `chat/completions`）。
 */
object AiDefaults {

    /** OpenAI 官方（`/v1/chat/completions`）。 */
    const val OPENAI_BASE_URL: String = "https://api.openai.com/v1/"

    /** DeepSeek（OpenAI 兼容，`/chat/completions`）。 */
    const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com/"

    /** OpenRouter（OpenAI 兼容聚合，`/api/v1/chat/completions`）。 */
    const val OPENROUTER_BASE_URL: String = "https://openrouter.ai/api/v1/"

    /** Google Gemini（`generateContent`）。 */
    const val GEMINI_BASE_URL: String = "https://generativelanguage.googleapis.com/"
}

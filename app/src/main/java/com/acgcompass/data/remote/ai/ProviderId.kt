package com.acgcompass.data.remote.ai

/**
 * AI 服务提供方标识。
 *
 * 用户在设置页选择并配置某个 provider 的 Base URL / 模型名 / API Key（凭据存于
 * `CredentialStore`，密文隔离）。前四类为 OpenAI 兼容形态（共享 `/chat/completions` 调用），
 * [GEMINI] 使用 Google `generateContent` 形态，[CUSTOM_OPENAI_COMPAT] 为用户自建的
 * OpenAI 兼容端点（Base URL + 模型名 + key 全部由用户提供）。
 *
 * 枚举名作为多绑定 map 的键与持久化标识，**不得**随意重命名（升级兼容）。
 */
enum class ProviderId {
    OPENAI,
    GEMINI,
    DEEPSEEK,
    OPENROUTER,
    CUSTOM_OPENAI_COMPAT,
    ;

    companion object {
        /** 从持久化字符串解析，未知值返回 `null`，避免损坏数据导致崩溃。 */
        fun fromStorage(raw: String?): ProviderId? = entries.firstOrNull { it.name == raw }
    }
}

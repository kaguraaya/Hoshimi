package com.acgcompass.data.credential

/**
 * 凭据归属的数据源 / 服务标识。
 *
 * 每个 [SourceId] 对应一组用户自行提供的凭据（token / apiKey / OAuth client 等），
 * 由 `CredentialStore` 以加密形式（EncryptedSharedPreferences + Keystore）单独隔离存储。
 *
 * - [BANGUMI]：主来源，支持 Access Token 和 OAuth。
 * - [ANILIST]：补充来源，公共查询免鉴权并支持可选 Token。
 * - [JIKAN]：无凭据补充来源。
 * - [MAL]：MyAnimeList 官方 API，使用 Client ID 和 OAuth2 PKCE。
 * - [VNDB]：视觉小说来源，支持可选 Token。
 * - [AI_PROVIDER]：用户配置的 AI 服务（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义兼容端点）。
 *
 * 枚举名作为加密存储键的稳定前缀，**不得**随意重命名以保证升级兼容。
 */
enum class SourceId {
    BANGUMI,
    ANILIST,
    JIKAN,
    MAL,
    VNDB,
    AI_PROVIDER,
    ;

    companion object {
        /** 从持久化字符串解析，未知值返回 `null`，避免升级 / 损坏数据导致崩溃。 */
        fun fromStorage(raw: String?): SourceId? = entries.firstOrNull { it.name == raw }
    }
}

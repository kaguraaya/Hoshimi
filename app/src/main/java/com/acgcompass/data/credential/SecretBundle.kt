package com.acgcompass.data.credential

import kotlinx.serialization.Serializable

/**
 * 单个数据源 / AI 服务的凭据集合（明文，仅在内存与加密存储之间流转）。
 *
 * 所有字段均为可选 [String]，按数据源各取所需：
 * - REST token 型（Bangumi / VNDB）：[token]。
 * - AI provider：[apiKey] + [baseUrl] + [model]（自定义 OpenAI 兼容端点）。
 * - OAuth 型（MAL 官方 / Bangumi）：[clientId] (+ [clientSecret])；OAuth 换取的 access token 存入 [token]，
 *   并另存 [refreshToken] 与 [tokenExpiresAt]（epoch 毫秒）用于到期前自动续期。
 *
 * **安全约束**：该类承载明文凭据，**绝不**可写入 Room、DataStore、
 * 默认备份或日志。它仅在 `CredentialStore.put` / `get` 时于内存中存在，落盘时一律由
 * EncryptedSharedPreferences（AES256-GCM，Keystore 保护）加密。对外展示一律使用
 * [RedactedSecret] / [RedactedCredentials] 脱敏形式。
 *
 * 使用 [kotlinx.serialization] 进行序列化，仅用于写入**加密**存储（密文），不用于明文持久化。
 */
@Serializable
data class SecretBundle(
    val token: String? = null,
    val apiKey: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    /** OAuth 续期令牌（与 [token] 同源换取）。仅 OAuth 流程写入，手动粘贴 Token 时为空。 */
    val refreshToken: String? = null,
    /** OAuth access token 过期时刻（epoch 毫秒）。用于启动时判断是否需要提前续期。 */
    val tokenExpiresAt: Long? = null,
) {
    /** 是否含有任意一项实际凭据值（用于派生 `configured` 元数据）。`baseUrl`/`model`/过期时刻 不算敏感凭据但计入「已配置」。 */
    fun hasAnyValue(): Boolean = listOf(token, apiKey, clientId, clientSecret, baseUrl, model, refreshToken)
        .any { !it.isNullOrBlank() }

    /** 生成脱敏视图：敏感字段掩码（仅保留末尾 ≤4 字符），非敏感字段（baseUrl/model）原样保留。 */
    fun toRedacted(): RedactedSecret = RedactedSecret(
        token = CredentialMasking.mask(token),
        apiKey = CredentialMasking.mask(apiKey),
        clientId = CredentialMasking.mask(clientId),
        clientSecret = CredentialMasking.mask(clientSecret),
        // baseUrl / model 非敏感：保留原值以便用户核对配置（不含 key/token）。
        baseUrl = baseUrl?.takeIf { it.isNotBlank() },
        model = model?.takeIf { it.isNotBlank() },
    )
}

/**
 * 凭据掩码工具。
 *
 * 核心规则：**最多**保留末尾 4 个字符，其余以 `****` 替代；当原始值长度 ≤4 时不暴露任何字符，
 * 避免短密钥被整体泄露。输出形如 `****…ab` / `****…wxyz`。
 */
object CredentialMasking {
    private const val MAX_VISIBLE_TAIL = 4
    private const val MASK_PREFIX = "****…"

    /**
     * 对单个敏感值脱敏。
     *
     * @return `null` 当输入为 `null`；空白原样返回；否则返回脱敏字符串。
     */
    fun mask(raw: String?): String? {
        if (raw == null) return null
        if (raw.isBlank()) return raw
        // 长度 ≤4 时完全不暴露，避免短密钥整体泄露；否则暴露末尾 ≤4 字符（且不超过原值长度减 4）。
        val tailLen = if (raw.length <= MAX_VISIBLE_TAIL) {
            0
        } else {
            minOf(MAX_VISIBLE_TAIL, raw.length - MAX_VISIBLE_TAIL)
        }
        val tail = if (tailLen > 0) raw.takeLast(tailLen) else ""
        return MASK_PREFIX + tail
    }
}

/**
 * 单源凭据的脱敏视图（可安全用于 UI 展示 / 导出）。
 *
 * 敏感字段（[token]/[apiKey]/[clientId]/[clientSecret]）为掩码形式；
 * 非敏感字段（[baseUrl]/[model]）可保留原值。`null` 表示该字段未配置。
 */
@Serializable
data class RedactedSecret(
    val token: String? = null,
    val apiKey: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
)

/**
 * 全部已配置源的脱敏凭据集合。
 *
 * 默认备份**不含**凭据；仅当用户显式选择导出凭据并二次确认时，使用本结构输出脱敏值
 * （如 `sk-****…ab`）。不含任何完整明文。
 */
@Serializable
data class RedactedCredentials(
    val entries: Map<SourceId, RedactedSecret> = emptyMap(),
)

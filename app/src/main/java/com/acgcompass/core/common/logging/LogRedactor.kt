package com.acgcompass.core.common.logging

/**
 * 纯函数日志脱敏器。
 *
 * **安全不变式（CRITICAL）**：任何疑似 key / token / secret 在写入日志、错误报告之前都必须经过
 * [redact]。本类**绝不**输出完整凭据明文——匹配到的敏感值一律被掩码，最多保留末尾 4 个字符。
 *
 * 设计为「纯函数 + 无 Android 依赖」，因此 [redact] 可在 JVM 单元测试中独立验证，无需任何插桩或 mock。
 *
 * 覆盖的脱敏模式（按应用顺序）：
 * 1. `Authorization: Bearer xxx`（含独立的 `Bearer xxx`）。
 * 2. 敏感字段：`api_key` / `token` / `access_token` / `client_secret` / `secret` / `password` 等
 *    的 query 参数或 JSON / kv 字段值。
 * 3. JWT 形态的三段式 token（`header.payload.signature`）。
 * 4. OpenAI 风格 key（`sk-...`）。
 * 5. 通用长字母数字密钥（≥20 字符且字母与数字混合）。
 *
 * 掩码形如 `****…abcd`；当可见尾部为空时为 `****`。
 */
object LogRedactor {

    private const val MAX_VISIBLE_TAIL = 4
    private const val MASK_PREFIX = "****…"
    private const val FULL_MASK = "****"

    /**
     * 对单个敏感值掩码：最多保留末尾 [MAX_VISIBLE_TAIL] 个字符，其余替换为 `****`。
     * 当原始值长度 ≤ [MAX_VISIBLE_TAIL] 时完全不暴露，避免短密钥整体泄露。
     */
    private fun mask(value: String): String {
        if (value.isEmpty()) return value
        val tailLen = if (value.length <= MAX_VISIBLE_TAIL) {
            0
        } else {
            minOf(MAX_VISIBLE_TAIL, value.length - MAX_VISIBLE_TAIL)
        }
        return if (tailLen > 0) MASK_PREFIX + value.takeLast(tailLen) else FULL_MASK
    }

    // Authorization 头 / 独立的 Bearer 凭据：保留 scheme 关键字，仅掩码其后的 token。
    private val bearerRegex =
        Regex("""(?i)\b(bearer\s+)([A-Za-z0-9._~+/=-]{4,})""")

    // 敏感字段（query 参数 / JSON / kv）。保留字段名与分隔符，仅掩码值。
    // 值的负向前瞻排除 scheme 关键字（Bearer/Basic/Token），把它们交给 [bearerRegex] 处理，避免误伤。
    private val sensitiveFieldRegex = Regex(
        """(?i)("?\b(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|""" +
            """client[_-]?secret|client[_-]?id|authorization|token|secret|""" +
            """password|passwd|pwd)\b"?\s*[:=]\s*"?)""" +
            """(?!(?:bearer|basic|token)\b)([^"\s,&}';]+)""",
    )

    // JWT：三段 base64url（header.payload.signature）。
    private val jwtRegex =
        Regex("""\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")

    // OpenAI 风格 key：sk- 前缀。
    private val skKeyRegex =
        Regex("""\bsk-[A-Za-z0-9_-]{10,}\b""")

    // 通用长密钥：≥20 字符，且同时含字母与数字（降低对普通长单词的误判）。
    private val genericSecretRegex = Regex(
        """\b(?=[A-Za-z0-9]{20,}\b)(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{20,}\b""",
    )

    /**
     * 对任意输入文本脱敏后返回。纯函数：相同输入恒定产生相同输出，且不产生副作用。
     *
     * @return 脱敏后的字符串；不含任何完整 key / token / secret。
     */
    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var out = input
        // Bearer 在敏感字段之前处理，确保 "Authorization: Bearer xxx" 正确保留 scheme。
        out = bearerRegex.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        out = sensitiveFieldRegex.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        out = jwtRegex.replace(out) { m -> mask(m.value) }
        out = skKeyRegex.replace(out) { m -> mask(m.value) }
        out = genericSecretRegex.replace(out) { m -> mask(m.value) }
        return out
    }
}

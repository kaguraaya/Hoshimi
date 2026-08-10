package com.acgcompass.domain.matching

import java.text.Normalizer

/**
 * 标题归一化。纯 Kotlin，无 Android 依赖，可单元/属性测试。
 *
 * 归一化用于跨源（中文 / 日文 / 罗马音 / 英文 / 别名）匹配：把同一作品在不同源、不同书写习惯下的
 * 标题折叠成同一可比较的规范形，再交由 [similarity] / [matchConfidence] 计算置信度。
 *
 * 归一化步骤（顺序固定，保证幂等）：
 * 1. **Unicode 兼容分解再合成（NFKC）**：全角 → 半角、各种兼容字形折叠为规范形。
 * 2. **大小写折叠**：统一为小写（locale 无关）。
 * 3. **去标点 / 符号**：所有非字母、非数字、非空白字符视为分隔符。
 * 4. **空白折叠 + trim**：连续空白（含被标点替换出的空白）折叠为单个半角空格并去除首尾空白。
 *
 * 幂等性（**MUST**）：`normalizeTitle(normalizeTitle(x)) == normalizeTitle(x)`。
 * 这一性质成立的原因：输出仅含「小写、NFKC 规范、以单个半角空格分隔的字母 / 数字 token」，
 * 对其再次执行各步骤均为恒等变换。
 */
public fun normalizeTitle(raw: String): String {
    if (raw.isEmpty()) return ""

    // 1. NFKC：全角→半角、兼容字形折叠（幂等）。
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    // 2. 大小写折叠（locale 无关，避免土耳其语 i/İ 等区域差异）。
    val lowered = normalized.lowercase()

    // 3 + 4. 去标点/符号并折叠空白：逐字符扫描，标点与空白都作为「分隔符」，
    // 仅在已有内容且上一个写入不是空格时补一个空格，最后去除尾随空格。
    val builder = StringBuilder(lowered.length)
    var pendingSeparator = false
    for (ch in lowered) {
        val isSeparator = ch.isWhitespace() || !ch.isLetterOrDigit()
        if (isSeparator) {
            // 只有当前面已写入过真实内容时才记一个待定分隔符，避免前导空格。
            if (builder.isNotEmpty()) pendingSeparator = true
        } else {
            if (pendingSeparator) {
                builder.append(' ')
                pendingSeparator = false
            }
            builder.append(ch)
        }
    }
    return builder.toString()
}

/**
 * 紧凑归一化：在 [normalizeTitle] 基础上去掉所有空格，用于 `9nine`、`9-nine` 等
 * 仅靠数字-字母边界或连字符产生空格差异的标题做精确相等比较。幂等。
 */
public fun normalizeCompact(raw: String): String = normalizeTitle(raw).replace(" ", "")

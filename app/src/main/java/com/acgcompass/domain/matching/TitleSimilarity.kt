package com.acgcompass.domain.matching

import kotlin.math.max
import kotlin.math.min

/**
 * 标题相似度（的输入项）。纯 Kotlin，可单元/属性测试。
 *
 * 采用 **归一化 Levenshtein 编辑距离** 度量两个标题的接近程度：
 * `similarity = 1 - editDistance(na, nb) / max(len(na), len(nb))`，其中 `na/nb` 为
 * [normalizeTitle] 归一化后的标题。结果落在 `[0,1]`：
 * - `1.0` 表示归一化后完全一致（含两者都为空的退化情况）。
 * - `0.0` 表示完全不同。
 *
 * 选择编辑距离而非纯 token 集合，是因为它对中/日/罗马音等不含空格分词的标题也稳定，
 * 同时对错字、缺字、副标题差异有平滑的容忍度。
 *
 * 该函数在内部先归一化输入，调用方无需预先归一化（重复归一化因幂等而无副作用）。
 */
public fun similarity(a: String, b: String): Double {
    val na = normalizeTitle(a)
    val nb = normalizeTitle(b)

    if (na == nb) return 1.0
    val longerLen = max(na.length, nb.length)
    if (longerLen == 0) return 1.0 // 两者归一化后都为空：视为一致。

    val distance = levenshtein(na, nb)
    val editSim = (1.0 - distance.toDouble() / longerLen).coerceIn(0.0, 1.0)

    // 子串和前缀包含加权：当较短串完整出现在较长串中（如查询「2.5次元」
    // 命中「2.5次元の誘惑」），给出按长度比缩放的较高相似度，弥补编辑距离对「部分查询」过低的问题。
    val shorter = if (na.length <= nb.length) na else nb
    val longer = if (na.length <= nb.length) nb else na
    val containSim = if (shorter.isNotEmpty() && longer.contains(shorter)) {
        0.5 + 0.5 * (shorter.length.toDouble() / longer.length)
    } else {
        0.0
    }

    return max(editSim, containSim).coerceIn(0.0, 1.0)
}

/**
 * 计算两个字符串的 Levenshtein 编辑距离（插入/删除/替换各计 1）。
 * 使用两行滚动数组，空间 O(min(n,m))。
 */
private fun levenshtein(s: String, t: String): Int {
    if (s == t) return 0
    if (s.isEmpty()) return t.length
    if (t.isEmpty()) return s.length

    // 让 s 为较短串以减少数组宽度。
    val (shorter, longer) = if (s.length <= t.length) s to t else t to s
    val width = shorter.length

    var previous = IntArray(width + 1) { it }
    var current = IntArray(width + 1)

    for (i in 1..longer.length) {
        current[0] = i
        val longerChar = longer[i - 1]
        for (j in 1..width) {
            val cost = if (longerChar == shorter[j - 1]) 0 else 1
            current[j] = min(
                min(
                    current[j - 1] + 1, // 插入
                    previous[j] + 1, // 删除
                ),
                previous[j - 1] + cost, // 替换 / 匹配
            )
        }
        val tmp = previous
        previous = current
        current = tmp
    }
    return previous[width]
}

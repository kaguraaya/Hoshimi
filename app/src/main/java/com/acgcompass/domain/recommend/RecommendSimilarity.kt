package com.acgcompass.domain.recommend

import com.acgcompass.domain.matching.normalizeTitle
import com.acgcompass.domain.model.TagNoise
import com.acgcompass.domain.usecase.TasteTagTaxonomy

/**
 * 推荐排序共用的相似度 / 系列归并工具（纯 Kotlin，今晚看什么与探索队列共用）。
 */
object RecommendSimilarity {

    /** 参与多样性比较的标签数上限（Bangumi 标签按标注人数降序，前几个即主干题材）。 */
    const val MAX_SIMILARITY_TAGS: Int = 8

    /**
     * 作品的「题材指纹」：清洗后去掉年份 / 媒介格式 / 厂商等元数据标签，取前 [MAX_SIMILARITY_TAGS] 个。
     * 只用于衡量两部作品题材是否雷同，不参与口味打分。
     */
    fun genreFingerprint(rawTags: List<String>): Set<String> = rawTags.asSequence()
        .map { TagNoise.clean(it).lowercase() }
        .filter { it.isNotEmpty() && !TasteTagTaxonomy.isMeta(it) && !TasteTagTaxonomy.isStudio(it) }
        .distinct()
        .take(MAX_SIMILARITY_TAGS)
        .toSet()

    /** Jaccard 相似度；任一侧为空返回 0（无信息不视为相似）。 */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        val inter = small.count { it in large }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * 系列归并键：把「第二季 / 2nd Season / Ⅱ / 剧场版 / OVA / 续篇」等季度与衍生标记剥掉，
     * 使同一系列的不同季度得到相同的键，供「同系列最多占一个名额」使用。
     *
     * 宁可漏并（不同副标题的续作各算一部）也不乱并：只做标记剥离，不做前缀模糊匹配；
     * 剥离后为空则回退到规范化标题本身。
     */
    fun franchiseKey(title: String): String {
        val normalized = normalizeTitle(title)
        var key = normalized
        for (pattern in SEASON_PATTERNS) key = pattern.replace(key, " ")
        key = key.replace(Regex("\\s+"), " ").trim()
        // 末尾的独立序号（"xxx 2" / "xxx ii"）也是季度标记。
        key = TRAILING_ORDINAL.replace(key, "").trim()
        return key.ifBlank { normalized }
    }

    private val SEASON_PATTERNS: List<Regex> = listOf(
        Regex("第\\s*[0-9一二三四五六七八九十]+\\s*(季|期|部|章|クール|シーズン)"),
        Regex("\\b(the\\s+)?(final|[0-9]+(st|nd|rd|th))\\s+season\\b"),
        Regex("\\bseason\\s*[0-9]+\\b"),
        Regex("\\bpart\\s*[0-9]+\\b"),
        Regex("\\bs[0-9]{1,2}\\b"),
        Regex("(最终季|最終季|完结篇|完結編|续篇|続編|续集|新系列|剧场版|劇場版|总集篇|総集編|特别篇|番外篇)"),
        Regex("\\b(ova|oad|ona|movie|the movie|specials?)\\b"),
    )

    private val TRAILING_ORDINAL = Regex("\\s+([0-9]{1,2}|ii|iii|iv|ⅱ|ⅲ|ⅳ)$")
}

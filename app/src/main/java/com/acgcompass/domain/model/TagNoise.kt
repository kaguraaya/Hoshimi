package com.acgcompass.domain.model

/**
 * 标签噪声过滤：识别年份、季度、媒介格式、改编来源和地区等非内容元数据标签。
 *
 * 口味画像统计（`BangumiSyncManager`）与「今晚看什么」动态标签筛选（`RecommenderViewModel`）共用同一套
 * 规则，保证用户在不同入口看到的「有效题材/情绪标签」口径一致：这些元数据每部作品几乎都有，计入会
 * 让无意义标签霸榜、污染信号，故统一剔除。
 */
object TagNoise {

    /** 标签清洗：下划线/连字符转空格、折叠多余空白、trim。 */
    fun clean(raw: String): String = raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

    /**
     * 是否为「非内容」噪声标签——年份/季度（如 2024、2024年10月）、媒介格式（TV/OVA/剧场版）、
     * 改编来源（漫画改/轻小说改/原创）、地区（日本）等。这些是元数据而非题材/情绪信号。
     */
    fun isNoise(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty()) return true
        if (NOISE_DATE_REGEX.matches(t)) return true
        return t.lowercase() in NOISE_TAGS
    }

    /** 年份/季度型噪声标签正则（如 2024、2024年、2024年10月、2024-10、10月）。 */
    private val NOISE_DATE_REGEX = Regex(
        "^(\\d{4}|\\d{4}年|\\d{4}年\\d{1,2}月|\\d{4}[-./]\\d{1,2}|\\d{1,2}月)$",
    )

    /** 媒介格式/改编来源/地区等非内容噪声标签（小写比较）。 */
    private val NOISE_TAGS: Set<String> = setOf(
        "tv", "ova", "oad", "ona", "web", "sp", "pv", "cm", "mv", "op", "ed",
        "剧场版", "剧场", "电影", "短片", "特别篇", "动画", "日本动画", "tv动画", "动画化",
        "漫画改", "轻小说改", "小说改", "游戏改", "gal改", "galgame改", "eroge改", "原创", "漫改",
        "改编", "18禁游戏改", "手游改", "日本", "国产", "中国", "美国",
    )
}

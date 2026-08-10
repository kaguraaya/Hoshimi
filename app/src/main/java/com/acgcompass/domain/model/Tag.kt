package com.acgcompass.domain.model

/**
 * 标签分类法：
 * - [CONTENT_TYPE]：内容类型（动画 / 漫画 / 小说 / 游戏 / VN 等）。
 * - [STATUS]：状态（连载 / 完结 / 搁置 等）。
 * - [LENGTH]：篇幅（短篇 / 中篇 / 长篇）。
 * - [MOOD]：心情标签（治愈 / 致郁 / 热血 等）。
 * - [RISK]：风险标签（上头 / 烧脑 / 刀 等）。
 */
enum class TagCategory {
    CONTENT_TYPE,
    STATUS,
    LENGTH,
    MOOD,
    RISK,

    /** 社区 / 主题标签（如 催泪 / 校园 / 恋爱），来自 Bangumi 等社区条目标签，用于展示与口味分析。 */
    CONTENT,
    ;

    companion object {
        /** 从持久化字符串解析；未知值返回 `null`，由调用方决定是否丢弃该标签。 */
        fun fromStorage(raw: String?): TagCategory? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 作品标签，由 [category] 分类与 [name] 名称组成。
 */
data class Tag(
    val category: TagCategory,
    val name: String,
)

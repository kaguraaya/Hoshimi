package com.acgcompass.domain.tastecode

/**
 * 口味码：把一个人的口味压缩成一段可以分享的文本（也能做成二维码），对方在自己的应用里打开后看到两人的口味对比。
 *
 * 只放能算出对比、又不暴露隐私的内容：没有短评、账号、完整的看过列表；代表作由用户在生成前确认，可以逐部去掉或全部关掉。
 * 两边看到的契合度只由两份口味码计算，所以谁打开都一样。
 */
data class TasteCode(
    /** 生成时间（epoch 日）。 */
    val generatedOnDay: Long,
    /** 口味算法版本：版本不同时对比照常进行，界面提示「算法版本不同，结果仅供参考」。 */
    val algorithmVersion: Int,
    /** 可选昵称，最多 [MAX_NICKNAME] 字；为空时显示「Ta」。 */
    val nickname: String? = null,
    val style: ScoringStyle,
    /** 各口味维度（[com.acgcompass.domain.taste.TasteCategory.key]）的偏好强度，0–1。 */
    val dimensions: Map<String, Float> = emptyMap(),
    /** 最喜欢的标签，强度 0–1，按强度降序。 */
    val liked: List<WeightedTag> = emptyList(),
    /** 最回避的标签，强度 0–1，按强度降序。 */
    val avoided: List<WeightedTag> = emptyList(),
    /** 与大众分歧最大的标签：正数 = 比大众多给的分，负数 = 少给的分（10 分制）。 */
    val divergence: List<WeightedTag> = emptyList(),
    /** 看过的作品按年代分布的占比，顺序见 [ERA_LABELS]，合计约为 1。 */
    val eraShares: List<Float> = emptyList(),
    /** 代表作（Bangumi 条目 + 评分）。 */
    val works: List<RepresentativeWork> = emptyList(),
) {
    companion object {
        const val MAX_NICKNAME = 12
        const val MAX_LIKED = 24
        const val MAX_AVOIDED = 12
        const val MAX_DIVERGENCE = 10
        const val MAX_WORKS = 30

        /** 年代分档（[eraShares] 的顺序）。 */
        val ERA_LABELS: List<String> =
            listOf("1989 年及以前", "1990 年代", "2000–2004", "2005–2009", "2010–2014", "2015–2019", "2020–2024", "2025 年起")
    }
}

/** 打分风格。 */
data class ScoringStyle(
    /** 打过分的部数。 */
    val ratedCount: Int,
    /** 均分（10 分制）。 */
    val mean: Float,
    /** 评分的标准差：越大说明打分越拉得开。 */
    val spread: Float,
    /** 宽严：均分比同一批作品的社区均分高多少（正 = 手松）。 */
    val leniency: Float,
    /** 跟随口碑的程度：社区分每高 1 分，你大约多给几分（1 = 和大众同步）。 */
    val followsCrowd: Float,
)

/** 一个口味标签。[category] 是 [com.acgcompass.domain.taste.TasteCategory.key]，用来把题材、角色属性、创作者分开显示。 */
data class WeightedTag(val tag: String, val weight: Float, val category: String? = null)

/** 标签在对比页上的分组。 */
enum class TagGroup(val label: String, val categories: Set<String>) {
    GENRE("题材", setOf("topic", "combo", "device", "source")),
    CHARACTER("角色属性", setOf("xp", "character")),
    CREATOR("创作者", setOf("staff", "cv")),
    OTHER("其它", emptySet()),
    ;

    companion object {
        fun of(category: String?): TagGroup = entries.firstOrNull { category in it.categories } ?: OTHER
    }
}

/** 代表作入选的原因，生成页按它分组显示。 */
enum class RepresentativeReason { TOP_RATED, MOST_DIVERGENT, RECENT }

data class RepresentativeWork(val subjectId: Int, val rating: Int, val reason: RepresentativeReason)

package com.acgcompass.domain.taste

import com.acgcompass.domain.model.MediaType

/** 单个社区标签的标注人数（q_xk 归一与组合挖掘的输入）。 */
data class TagCount(val name: String, val count: Int)

enum class WorkFeatureCompleteness {
    /** 收藏响应内嵌的临时标签；可供首显，但完整画像必须先用条目详情校验。 */
    EMBEDDED,
    BASIC,
    EXTENDED,
}

/**
 * `work_features` 表对应的作品结构化特征领域模型。
 *
 * 把一部作品在 Bangumi 上的**社区标签（带标注人数）+ staff/角色/CV + 社区评分/票数 + 集数/时长/平台**
 * 归一为纯领域结构，供口味画像构建（[com.acgcompass.domain.taste]）与今晚推荐复用，避免每次现算现拉。
 *
 * - [tagCounts]：社区标签 + 标注人数；`q_xk` 归一以 [maxTagCount] 为分母。
 * - [staff]/[characters]/[cv]：结构化真实名（用于 [TagClassifier] 交叉验证与 staff/声优/角色实体维度）。
 * - [bangumiScore]/[bangumiVotes]：社区评分弱引导项与今晚硬过滤。
 * - [eps]/[durationMin]/[platform]：今晚推荐的 runtime 估计与时间拟合。
 */
data class WorkFeature(
    val subjectId: String,
    val tagCounts: List<TagCount> = emptyList(),
    val staff: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val cv: List<String> = emptyList(),
    /** 结构化开发商、发行商或制作公司名称；用于阻止公司名落入题材。 */
    val sources: List<String> = emptyList(),
    val bangumiScore: Float? = null,
    val bangumiVotes: Int = 0,
    val eps: Int = 0,
    val durationMin: Int = 0,
    val platform: String? = null,
    val mediaType: MediaType? = null,
    /** 作品标题变体（原名 / 中文名 / 别名），用于 [TagClassifier] 的 self-title 泄漏标签剔除。 */
    val titleVariants: List<String> = emptyList(),
    val completeness: WorkFeatureCompleteness = WorkFeatureCompleteness.EXTENDED,
    val baseDataUpdatedAt: Long = 0L,
    val extendedDataUpdatedAt: Long? = null,
    val updatedAt: Long = 0L,
    val coverUrl: String? = null,
    /** 放送 / 发售日期（`YYYY-MM-DD`）。 */
    val airDate: String? = null,
) {
    /** subject 内最大标注人数（q_xk = log(1+count)/log(1+maxTagCount) 的分母）。 */
    val maxTagCount: Int get() = tagCounts.maxOfOrNull { it.count } ?: 0

    /** 是否有可用于画像/评分的有效特征（至少有标签或评分）。 */
    val hasSignal: Boolean get() = tagCounts.isNotEmpty() || (bangumiScore ?: 0f) > 0f
}

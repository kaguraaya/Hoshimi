package com.acgcompass.domain.funfeature

import com.acgcompass.domain.model.BacklogItem

/**
 * 安利债务：你欠群友 N 部番没看。
 *
 * - [owedCount]：被群友安利过（被安利次数 ≥ 1）且仍躺在待补池（即尚未补完）的作品数，恒为非负。
 * - [message]：可直接展示的文案；为 0 时给出「清白」兜底，绝不伪造数值。
 */
data class RecommendationDebt(
    val owedCount: Int,
    val message: String,
)

/**
 * 计算安利债务——纯函数。
 *
 * 待补池中的条目都视为「尚未补完」（待补即欠的番）。一条待补条目计入债务当且仅当其对应作品的
 * 被安利次数 ≥ [minRecommended]（默认 1），即「确实是群友安利过的番」。
 *
 * @param backlog 待补池条目（每个 [BacklogItem.workId] 至多一条，去重由仓库保证）。
 * @param recommendedCounts 作品 id → 被安利次数。缺失的作品视为 0 次。
 * @param minRecommended 计入债务的最小被安利次数阈值（含），默认 1。
 * @return [RecommendationDebt]：欠番数量与可读文案。
 */
fun computeRecommendationDebt(
    backlog: List<BacklogItem>,
    recommendedCounts: Map<String, Int>,
    minRecommended: Int = 1,
): RecommendationDebt {
    val owed = backlog.count { item ->
        (recommendedCounts[item.workId] ?: 0) >= minRecommended
    }
    val message = if (owed <= 0) {
        "暂时没有欠群友的番，清白一身"
    } else {
        "你欠群友 $owed 部番没看"
    }
    return RecommendationDebt(owedCount = owed, message = message)
}

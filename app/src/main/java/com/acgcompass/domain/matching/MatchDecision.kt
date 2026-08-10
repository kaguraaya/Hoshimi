package com.acgcompass.domain.matching

import com.acgcompass.domain.model.WorkMatch

/**
 * 多源匹配决策结果。
 *
 * 由 [decideMatch] 在拿到一组带置信度的源候选（[WorkMatch]）后产出，告诉上层是「自动合并」
 * 还是「需要用户手动确认」。这是 **纯领域决策**，不涉及持久化；`overrideMatch(userOverridden=true)`
 * 的落地由仓库层负责。
 */
public sealed interface MatchDecision {

    /** 参与本次决策的全部候选，按置信度从高到低排序，供 UI 展示与用户在需确认时挑选。 */
    public val candidates: List<WorkMatch>

    /**
     * 自动合并：[best] 的置信度 ≥ [MATCH_THRESHOLD]，可直接合并到同一规范化 `Work`。
     *
     * @property best       置信度最高且达到阈值的候选。
     * @property candidates 全部候选（含 [best]），按置信度降序。
     */
    public data class AutoMerge(
        val best: WorkMatch,
        override val candidates: List<WorkMatch>,
    ) : MatchDecision

    /**
     * 低置信，需用户手动确认：没有任何候选达到 [MATCH_THRESHOLD]（含候选为空的情况）。
     *
     * @property candidates 全部候选，按置信度降序；可能为空（无任何匹配，应提示用户手动搜索）。
     */
    public data class NeedsConfirmation(
        override val candidates: List<WorkMatch>,
    ) : MatchDecision
}

/**
 * 根据多个源候选的置信度决定自动合并或需手动确认。纯函数。
 *
 * 规则：
 * - 候选按 [WorkMatch.matchConfidence] 降序排列。
 * - 若最高置信度的候选 **≥** [threshold]，返回 [MatchDecision.AutoMerge]（best 为该候选）。
 * - 否则（含候选为空）返回 [MatchDecision.NeedsConfirmation]，要求用户手动选择正确条目。
 *
 * 注意：本函数不改写任何 `SourceRef`，也不处理 `userOverridden`——手动纠正的持久化（纠正后
 * 后续同步不再自动改写）由仓库层持久化实现。
 *
 * @param candidates 源候选列表（可为空 / 含重复来源，由调用方组织）。
 * @param threshold  自动合并阈值，默认 [MATCH_THRESHOLD]。
 */
public fun decideMatch(candidates: List<WorkMatch>, threshold: Double = MATCH_THRESHOLD): MatchDecision {
    val ranked = candidates.sortedByDescending { it.matchConfidence }
    val best = ranked.firstOrNull()
    return if (best != null && best.matchConfidence >= threshold) {
        MatchDecision.AutoMerge(best = best, candidates = ranked)
    } else {
        MatchDecision.NeedsConfirmation(candidates = ranked)
    }
}

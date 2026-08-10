package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.Consensus
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId

/**
 * 评分聚合用例（9.4，降级缺失策略 ）。
 *
 * 纯 Kotlin、无 Android / IO 依赖，便于单元与属性测试。职责：
 *
 * 1. **不伪造、缺失即标记**：输入的 [perSource] 中为 `null` 的源表示「暂无数据」，聚合结果
 *    原样保留该 `null`，**绝不**用其它源的分数回填。
 * 2. **样本充足才下结论**：仅当有效评分样本数 >= [MIN_VALID_SAMPLES] 时才计算
 *    [Consensus]（评分稳定度 / 争议程度 / 补番优先级）；样本不足时 `consensus = null`，
 *    不给出确定结论（「不伪造客观结论」）。
 * 3. **跨源可比**：各源评分先经 [normalizeToTen] 归一到统一的 10 分制后再比较分歧
 *    （Bangumi/Jikan/MAL 为 10 分制，AniList/VNDB 为 100 分制）。
 *
 * 争议程度：归一后分数的极差（max - min）>= [CONTROVERSY_THRESHOLD]（2.0 / 10 分制）视为
 * 「评分分歧较大」；稳定度与极差成反比。
 */
class AggregateRatingsUseCase {

    /**
     * 聚合每源评分。[perSource] 的键为参与聚合的源，值为该源评分；值为 `null` 表示该源缺失。
     *
     * 返回的 [RatingAggregate.perSource] 与输入 **键集合一致**、缺失源仍为 `null`（不回填）。
     */
    operator fun invoke(perSource: Map<SourceId, RatingEntry?>): RatingAggregate {
        // 防御性拷贝：保持输入键集合与缺失标记（null）不变，绝不新增/回填任何源。
        val preserved: Map<SourceId, RatingEntry?> = LinkedHashMap(perSource)
        return RatingAggregate(
            perSource = preserved,
            consensus = computeConsensus(preserved),
        )
    }

    /**
     * 计算社区共识；有效样本不足 [MIN_VALID_SAMPLES] 时返回 `null`（不下结论）。
     */
    private fun computeConsensus(perSource: Map<SourceId, RatingEntry?>): Consensus? {
        // 仅采用「该源存在且分值有效」的样本；缺失源（null）与无效分值不参与，且不被填充。
        val valid: List<Pair<SourceId, RatingEntry>> = perSource.entries
            .mapNotNull { (source, entry) ->
                entry?.takeIf { isValidScore(source, it.score) }?.let { source to it }
            }

        if (valid.size < MIN_VALID_SAMPLES) {
            // 样本不足，不伪造结论。
            return null
        }

        val normalizedScores: List<Float> = valid.map { (source, entry) -> normalizeToTen(source, entry.score) }
        val n = normalizedScores.size
        val avg = normalizedScores.average().toFloat()
        val min = normalizedScores.min()
        val max = normalizedScores.max()
        val spread = max - min
        val variance = normalizedScores.map { (it - avg) * (it - avg) }.average().toFloat()
        val stdDev = kotlin.math.sqrt(variance)
        val totalVotes = valid.sumOf { (_, entry) -> entry.voteCount.toLong().coerceAtLeast(0L) }

        // 稳定度与争议度**相互独立**，不再简单互补到 100%。
        // 稳定度 = 多源一致性 + 评分人数充足度 + 来源数量充足度（越多越稳定）。
        val agreement = (1f - (stdDev / MAX_STD_DEV)).coerceIn(0f, 1f)
        val voteFactor = (kotlin.math.ln(1.0 + totalVotes) / kotlin.math.ln(1.0 + VOTE_FULL))
            .toFloat().coerceIn(0f, 1f)
        val sourceFactor = (n.toFloat() / SOURCE_FULL).coerceIn(0f, 1f)
        val stability = (0.40f * agreement + 0.35f * voteFactor + 0.25f * sourceFactor).coerceIn(0f, 1f)
        // 争议度 = 跨源极差 + 标准差离散度（与稳定度不构成 1-x 关系）。
        val controversy = ((spread / SPREAD_FULL) * 0.6f + (stdDev / MAX_STD_DEV) * 0.4f).coerceIn(0f, 1f)
        // 补番优先级（社区维度）：平均分为主，叠加评分人数充足度与稳定度。
        val priority = ((avg / TEN_SCALE_MAX) * 0.6f + voteFactor * 0.25f + stability * 0.15f).coerceIn(0f, 1f)

        return Consensus(
            stability = stability,
            controversy = controversy,
            priority = priority,
        )
    }

    companion object {
        /** 计算共识所需的最小有效样本数；低于此值不下结论。 */
        const val MIN_VALID_SAMPLES: Int = 2

        /** 10 分制满分。 */
        const val TEN_SCALE_MAX: Float = 10f

        /** 100 分制满分（AniList / VNDB）。 */
        const val HUNDRED_SCALE_MAX: Float = 100f

        /** 归一后的极差达到该值（10 分制）即视为评分分歧较大。 */
        const val CONTROVERSY_THRESHOLD: Float = 2.0f

        /** 稳定度/争议度模型常数。 */
        /** 标准差达到该值（10 分制）视为离散度满档。 */
        const val MAX_STD_DEV: Float = 3.0f

        /** 评分人数达到该值视为「评分人数充足」满档（对数缩放）。 */
        const val VOTE_FULL: Double = 50_000.0

        /** 参与共识的来源数量达到该值视为「来源充足」满档。 */
        const val SOURCE_FULL: Float = 4f

        /** 跨源极差达到该值（10 分制）视为争议满档。 */
        const val SPREAD_FULL: Float = 5f

        /**
         * 将各源原始分数归一到统一的 10 分制。
         *
         * - Bangumi / Jikan / MAL：原生 10 分制，原样返回。
         * - AniList / VNDB：原生 100 分制，除以 10。
         *
         * 结果裁剪到 `[0, 10]`，防止脏数据越界。
         */
        fun normalizeToTen(source: SourceId, rawScore: Float): Float {
            val onTenScale = when (source) {
                SourceId.BANGUMI, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL -> rawScore
                SourceId.ANILIST, SourceId.VNDB -> rawScore / 10f
            }
            return onTenScale.coerceIn(0f, TEN_SCALE_MAX)
        }

        /**
         * 判断某源原始分值是否为「有效评分」：有限且落在该源量纲的 `(0, 满分]` 内。
         * 0 / 负数 / NaN / 越界视为「暂无有效评分」，不参与共识计算。
         */
        fun isValidScore(source: SourceId, rawScore: Float): Boolean {
            if (rawScore.isNaN() || rawScore.isInfinite()) return false
            val max = when (source) {
                SourceId.BANGUMI, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL -> TEN_SCALE_MAX
                SourceId.ANILIST, SourceId.VNDB -> HUNDRED_SCALE_MAX
            }
            return rawScore > 0f && rawScore <= max
        }
    }
}

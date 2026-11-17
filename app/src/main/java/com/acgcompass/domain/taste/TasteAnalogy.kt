package com.acgcompass.domain.taste

/**
 * 相似作品类比：你给题材最接近的几部作品打了多少分（相对你自己的打分尺度）。
 *
 * @property z 加权后的标准分：0 = 和你的平均水平一样，+1 = 高出你一个标准差。
 * @property meanRating 这几部作品你打的平均分（按相似度加权）。
 * @property count 参与类比的作品数。
 */
data class TasteAnalogy(val z: Double, val meanRating: Double, val count: Int, val topSimilarity: Double = 0.0)

/**
 * 取标签向量最接近的 [ANALOGY_NEIGHBORS] 部已评分作品，按相似度的平方加权平均你的标准分，
 * 再按相似度总量收缩：相似作品越多、越像，越敢给出偏离平均的结论；没有相近作品时回到 0。
 *
 * Bangumi15M 盲测（400 位用户、5 折）：找出「比社区预期高 1 分以上」的作品，AUC 0.59，
 * 只看社区分时 0.44、预计评分 0.51。所以口味匹配度以它为主。
 */
fun PersonalDivergenceModel.analogy(feature: WorkFeature): TasteAnalogy? {
    val vector = vectorOf(feature)
    val neighbors = if (vector.isEmpty()) {
        emptyList()
    } else {
        examples.asSequence()
            .filter { it.first.subjectId != feature.subjectId }
            .map { (example, v) -> example to LinearSolver.dot(vector, v) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(ANALOGY_NEIGHBORS)
            .toList()
    }
    if (neighbors.isEmpty()) return null
    val std = ratingStd.coerceAtLeast(MIN_ANALOGY_STD)
    val weights = neighbors.map { it.second * it.second }
    val total = weights.sum()
    val z = neighbors.indices.sumOf { i -> weights[i] * (neighbors[i].first.rating - meanRating) / std } /
        (total + ANALOGY_SHRINK)
    val mean = neighbors.indices.sumOf { i -> weights[i] * neighbors[i].first.rating } / total
    return TasteAnalogy(z = z, meanRating = mean, count = neighbors.size, topSimilarity = neighbors.first().second)
}

/**
 * 有条件地大胆：只有找到了很像的已评分作品（最高相似度 ≥ 0.4，到 0.7 时满额），才把预计评分往
 * 「你给这些作品打的分」那边推，最多推 0.25 个标准差 × 类比标准分。
 * Bangumi15M 盲测：最高相似度 ≥ 0.6 的作品里，这样做使「与大众相差 1.5 分以上」的评分误差从 1.49 降到 1.40，
 * 整体误差只多 0.01；没有相近作品时不动，仍以社区基线为主。
 */
fun RatingPrediction.withAnalogy(analogy: TasteAnalogy?, ratingStd: Double): RatingPrediction {
    val strength = analogy?.let { ((it.topSimilarity - BOLD_FROM) / (BOLD_FULL - BOLD_FROM)).coerceIn(0.0, 1.0) } ?: 0.0
    val shift = BOLD_WEIGHT * strength * (analogy?.z ?: 0.0) * ratingStd.coerceAtLeast(MIN_ANALOGY_STD)
    if (kotlin.math.abs(shift) < MIN_SHIFT) return this
    return copy(
        rating = (rating + shift).coerceIn(MIN_PREDICTED, MAX_PREDICTED),
        divergence = divergence + shift,
        factors = (factors + DivergenceFactor(ANALOGY_LABEL, shift)).sortedByDescending { kotlin.math.abs(it.delta) },
    )
}

private const val MIN_PREDICTED = 1.0
private const val MAX_PREDICTED = 10.0
internal const val BOLD_FROM = 0.4
internal const val BOLD_FULL = 0.7
internal const val BOLD_WEIGHT = 0.25
private const val MIN_SHIFT = 0.05
private const val ANALOGY_LABEL = "相似作品你的评分"
internal const val ANALOGY_NEIGHBORS = 8
internal const val ANALOGY_SHRINK = 0.3
internal const val MIN_ANALOGY_STD = 0.6

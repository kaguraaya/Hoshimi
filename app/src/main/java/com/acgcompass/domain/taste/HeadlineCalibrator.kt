package com.acgcompass.domain.taste

import kotlin.math.sqrt

/**
 * 在本地作品池上给口味匹配度定个人刻度：对池里的作品算原始匹配分，取中位数和标准差。
 * 作品池是你搜索、浏览、加入过的作品（已评分的除外），代表「你会考虑看的那些」。
 */
object HeadlineCalibrator {
    /** 池里至少这么多部作品才定个人刻度；不够时用固定刻度。 */
    const val MIN_POOL = 30

    /** 参与定刻度的作品上限（按 id 取固定的一批，结果稳定、不会每次重建都抖动）。 */
    const val MAX_POOL = 300

    private const val MIN_SPREAD = 0.25

    fun fit(
        profile: AdvancedTasteProfile,
        pool: List<WorkFeature>,
        compute: ComputeTasteMatchUseCase,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
    ): HeadlineCalibration? {
        if (profile.divergence == null || pool.size < MIN_POOL) return null
        val raw = profile.copy(headlineCalibration = null)
        val zs = pool.asSequence()
            .filter { it.subjectId !in profile.ratedSubjectScores }
            .distinctBy { it.subjectId }
            .sortedBy { it.subjectId.hashCode() }
            .take(MAX_POOL)
            .mapNotNull { compute(it, raw, null, tagOverrides, withNeighbors = false).headlineZ }
            .toList()
        return if (zs.size < MIN_POOL) null else scaleOf(zs)
    }

    private fun scaleOf(zs: List<Double>): HeadlineCalibration {
        val sorted = zs.sorted()
        val median = sorted[sorted.size / 2]
        val mean = zs.average()
        val sd = sqrt(zs.sumOf { (it - mean) * (it - mean) } / (zs.size - 1))
        return HeadlineCalibration(center = median, spread = sd.coerceAtLeast(MIN_SPREAD), poolSize = zs.size)
    }
}

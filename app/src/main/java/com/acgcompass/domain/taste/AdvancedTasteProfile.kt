package com.acgcompass.domain.taste

/**
 * 某标签大类下用户的正向和负向偏好向量 `U_c^+ / U_c^-`。
 *
 * - [positive]：tag(已清洗小写) → `U_c^+(k)`（用户明确喜欢该标签的强度）。
 * - [negative]：tag → `U_c^-(k)`（用户明确不喜欢的强度）。
 * - [positiveL1]/[negativeL1]：各自 L1 范数（`sim_c` 归一分母 `||U_c^+||_1`）。
 */
data class CategoryPreference(
    val positive: Map<String, Double> = emptyMap(),
    val negative: Map<String, Double> = emptyMap(),
) {
    val positiveL1: Double = positive.values.sumOf { kotlin.math.abs(it) }
    val negativeL1: Double = negative.values.sumOf { kotlin.math.abs(it) }
    val isEmpty: Boolean get() = positive.isEmpty() && negative.isEmpty()
}

/** 一个被挖掘出的题材/装置组合及其强度（`strength(C)=signed_support/|C|^0.3`）。 */
data class TopicCombo(
    val tags: Set<String>,
    val strength: Double,
)

/**
 * 用户内分数校准参数。
 *
 * - [mu]：训练样本 raw `z` 的中位数（温度化 logistic 的中心）。
 * - [tau]：`max(0.18, std(z_train))`（温度）。
 * - [isotonicPoints]：可选的单调校准锚点（z 升序 → 目标分 [0,1]）；样本≥25 时拟合，否则为 `null`。
 */
data class TasteCalibration(
    val mu: Double = 0.0,
    val tau: Double = TasteScoringParams.LOGISTIC_TAU_FLOOR,
    val isotonicPoints: List<Pair<Double, Double>>? = null,
)

/** 画像样本对各维度的覆盖率（置信度计算用）。 */
data class TasteCoverage(
    val tag: Double = 0.0,
    val staff: Double = 0.0,
    val comment: Double = 0.0,
    val time: Double = 0.0,
)

/**
 * 口味画像核心模型：十二维正负向量、题材组合、校准参数、覆盖率和已评分集合。
 *
 * 由 [BuildTasteProfileUseCase] 从「用户收藏（评分/短评/时间） × [WorkFeature]」构建，供
 * [ComputeTasteMatchUseCase] 评分。纯领域结构，可序列化缓存（taste_profile_cache）。
 */
data class AdvancedTasteProfile(
    val categories: Map<TasteCategory, CategoryPreference> = emptyMap(),
    val combos: List<TopicCombo> = emptyList(),
    /** 已评分作品 subjectId → 评分（用于「已评分同作偏置」`0.12·pref(r_x)`）。 */
    val ratedSubjectScores: Map<String, Int> = emptyMap(),
    val calibration: TasteCalibration = TasteCalibration(),
    val coverage: TasteCoverage = TasteCoverage(),
    /** 实际参与画像的真实样本数 `n_real`（置信度用）。 */
    val sampleCount: Int = 0,
    /** 用户平均分（10 分制，习惯展示用）；无评分为 0。 */
    val userAvgRating: Double = 0.0,
    val algorithmVersion: Int = TasteScoringParams.ALGORITHM_VERSION,
    val generatedAt: Long = 0L,
) {
    /** 画像是否可用于评分（有任一类偏好向量或任一组合）。 */
    val isUsable: Boolean
        get() = combos.isNotEmpty() || categories.values.any { !it.isEmpty }

    fun category(c: TasteCategory): CategoryPreference = categories[c] ?: CategoryPreference()
}

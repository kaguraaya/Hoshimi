package com.acgcompass.domain.taste

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 口味匹配度的三个分支，设置里可选。 */
enum class TasteMatchBranch(val key: String, val label: String, val description: String) {
    STANDARD(
        key = "standard",
        label = "常规",
        description = "以你给相似作品打的分为主，加一半预计评分和少量题材相似度",
    ),
    CONSERVATIVE(
        key = "conservative",
        label = "保守",
        description = "跟着预计评分走：预计你会打的分越高，匹配度越高",
    ),
    AGGRESSIVE(
        key = "aggressive",
        label = "激进",
        description = "以预计评分为底；你给相似作品打分高时大胆往上推，打分低时只轻轻往下扣",
    ),
    ;

    companion object {
        fun fromKey(key: String?): TasteMatchBranch = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * 一个分支的主分数公式。
 *
 * 原始分 z = [ratingWeight]·预计评分标准分 + [analogyUpWeight]·max(0, 类比标准分)
 *          + [analogyDownWeight]·min(0, 类比标准分) + [similarityWeight]·题材相似度标准分；
 * z ≥ 0 时 [center] + ([cap] − [center])·(1 − e^(−[rise]·z))，平滑地接近上限；
 * z < 0 时 [center] + [fall]·z，不低于 [floor]。单调，作品之间的排序只由 z 决定。
 */
@Serializable
data class HeadlineFormula(
    val ratingWeight: Double,
    val analogyUpWeight: Double,
    val analogyDownWeight: Double,
    val similarityWeight: Double,
    val center: Double,
    val rise: Double,
    val fall: Double,
    val cap: Double = DEFAULT_CAP,
    val floor: Double = DEFAULT_FLOOR,
) {
    fun z(ratingZ: Double, analogyZ: Double?, similarity: Int): Double {
        val similarityZ = ((similarity - SIMILARITY_CENTER) / SIMILARITY_SPREAD)
            .coerceIn(-MAX_SIMILARITY_Z, MAX_SIMILARITY_Z)
        val analogy = analogyZ ?: 0.0
        return ratingWeight * ratingZ +
            analogyUpWeight * analogy.coerceAtLeast(0.0) +
            analogyDownWeight * analogy.coerceAtMost(0.0) +
            similarityWeight * similarityZ
    }

    fun score(z: Double): Int {
        val value = if (z >= 0.0) center + (cap - center) * (1.0 - exp(-rise * z)) else center + fall * z
        return value.roundToInt().coerceIn(floor.roundToInt(), cap.roundToInt())
    }

    companion object {
        const val DEFAULT_CAP = 97.0
        const val DEFAULT_FLOOR = 5.0
        private const val SIMILARITY_CENTER = 50.0
        private const val SIMILARITY_SPREAD = 20.0
        private const val MAX_SIMILARITY_Z = 2.0
    }
}

/** 判定措辞的门槛（[TasteVerdict]）。 */
@Serializable
data class VerdictThresholds(val likelyLove: Int, val likelyGood: Int, val average: Int, val passLine: Double)

/** 相似作品类比与「有条件地大胆」（[analogy]、[withAnalogy]）。 */
@Serializable
data class AnalogyParams(
    val neighbors: Int,
    val shrink: Double,
    val minStd: Double,
    val boldFrom: Double,
    val boldFull: Double,
    val boldWeight: Double,
)

/** 个人分歧模型（[PersonalDivergenceModel]）。 */
@Serializable
data class DivergenceParams(
    val minSamples: Int,
    val maxSamples: Int,
    val maxAnalogyExamples: Int,
    val tagRidgeLambda: Double,
    val personalGain: Double,
    val franchiseWeight: Double,
    val axisWeight: Double,
    val franchiseShrink: Double,
    val axisRidgeLambda: Double,
    val slopePriorStrength: Double,
    val maxSlope: Double,
    val maxTags: Int,
)

/**
 * 口味算法的完整参数：统一的模板格式（[FORMAT]），以后用于导出、编辑后整份导入替换。
 *
 * 目前实际生效的是 [branches]（三个分支的主分数公式）；其余各节记录内置算法的全部可调参数，
 * 导入替换功能开放后按同一格式整份替换。格式说明见仓库 `docs/algorithm-template.md`。
 */
@Serializable
data class TasteAlgorithmConfig(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val name: String = "内置算法",
    val algorithmVersion: Int = TasteScoringParams.ALGORITHM_VERSION,
    val defaultBranch: String = TasteMatchBranch.STANDARD.key,
    val branches: Map<String, HeadlineFormula>,
    val verdict: VerdictThresholds,
    val analogy: AnalogyParams,
    val divergence: DivergenceParams,
    @SerialName("categoryWeights") val categoryWeights: Map<String, Double>,
    val scoring: Map<String, Double>,
) {
    fun formula(branch: TasteMatchBranch): HeadlineFormula =
        branches[branch.key] ?: DEFAULT.branches.getValue(branch.key)

    companion object {
        const val FORMAT = "hoshimi-taste-algorithm"
        const val FORMAT_VERSION = 1

        val DEFAULT: TasteAlgorithmConfig by lazy { builtIn() }

        val JSON: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        fun encode(config: TasteAlgorithmConfig): String = JSON.encodeToString(serializer(), config)

        fun decode(text: String): TasteAlgorithmConfig = JSON.decodeFromString(serializer(), text)
    }
}

/** 模板校验：格式、版本、三个分支齐全，数值在合理范围内。返回问题列表，空表示通过。 */
@Suppress("CyclomaticComplexMethod") // 逐项列出校验规则，拆开反而难核对
fun TasteAlgorithmConfig.validate(): List<String> {
    val problems = mutableListOf<String>()
    if (format != TasteAlgorithmConfig.FORMAT) problems += "format 应为 ${TasteAlgorithmConfig.FORMAT}"
    if (formatVersion != TasteAlgorithmConfig.FORMAT_VERSION) problems += "formatVersion 不支持：$formatVersion"
    TasteMatchBranch.entries.forEach { if (it.key !in branches) problems += "缺少分支 ${it.key}" }
    if (TasteMatchBranch.entries.none { it.key == defaultBranch }) problems += "defaultBranch 不存在：$defaultBranch"
    branches.forEach { (key, f) ->
        if (f.floor !in 0.0..f.center || f.center !in f.floor..f.cap || f.cap > MAX_PERCENT) {
            problems += "分支 $key 的 floor / center / cap 顺序不对"
        }
        if (f.rise <= 0.0 || f.fall <= 0.0) problems += "分支 $key 的 rise、fall 必须为正"
        val weights = listOf(f.ratingWeight, f.analogyUpWeight, f.analogyDownWeight, f.similarityWeight)
        if (weights.any { it < 0.0 }) problems += "分支 $key 的权重不能为负"
    }
    with(verdict) {
        if (!(average < likelyGood && likelyGood < likelyLove)) {
            problems += "verdict 门槛应满足 average < likelyGood < likelyLove"
        }
    }
    if (analogy.neighbors <= 0 || analogy.boldFull <= analogy.boldFrom) problems += "analogy 参数不合理"
    if (divergence.minSamples <= 0 || divergence.maxSamples < divergence.minSamples) {
        problems += "divergence 样本数不合理"
    }
    val known = TasteCategory.entries.mapTo(HashSet()) { it.key }
    val unknown = categoryWeights.keys.filter { it !in known }
    if (unknown.isNotEmpty()) problems += "未知的维度：$unknown"
    return problems
}

private const val MAX_PERCENT = 100.0

private fun builtIn() = TasteAlgorithmConfig(
    branches = builtInBranches(),
    verdict = VerdictThresholds(
        likelyLove = TasteVerdict.LIKELY_LOVE,
        likelyGood = TasteVerdict.LIKELY_GOOD,
        average = TasteVerdict.AVERAGE,
        passLine = TasteVerdict.PASS_LINE,
    ),
    analogy = AnalogyParams(
        neighbors = ANALOGY_NEIGHBORS,
        shrink = ANALOGY_SHRINK,
        minStd = MIN_ANALOGY_STD,
        boldFrom = BOLD_FROM,
        boldFull = BOLD_FULL,
        boldWeight = BOLD_WEIGHT,
    ),
    divergence = builtInDivergence(),
    categoryWeights = TasteCategory.entries.associate { it.key to it.defaultWeight.toDouble() },
    scoring = TasteScoringParams.tunables(),
)

/** 三个分支的参数表：数值就是算法本身，见 docs/algorithm-template.md。 */
@Suppress("MagicNumber")
private fun builtInBranches(): Map<String, HeadlineFormula> = mapOf(
    TasteMatchBranch.STANDARD.key to HeadlineFormula(
        ratingWeight = 0.5,
        analogyUpWeight = 1.0,
        analogyDownWeight = 1.0,
        similarityWeight = 0.25,
        center = 70.0,
        rise = 0.81,
        fall = 12.0,
    ),
    TasteMatchBranch.CONSERVATIVE.key to HeadlineFormula(
        ratingWeight = 1.0,
        analogyUpWeight = 0.0,
        analogyDownWeight = 0.0,
        similarityWeight = 0.0,
        center = 72.0,
        rise = 0.82,
        fall = 13.0,
    ),
    TasteMatchBranch.AGGRESSIVE.key to HeadlineFormula(
        ratingWeight = 1.0,
        analogyUpWeight = 2.0,
        analogyDownWeight = 0.3,
        similarityWeight = 0.25,
        center = 72.0,
        rise = 0.85,
        fall = 12.0,
    ),
)

private fun builtInDivergence(): DivergenceParams = with(PersonalDivergenceModel.Companion) {
    DivergenceParams(
        minSamples = MIN_SAMPLES,
        maxSamples = MAX_SAMPLES,
        maxAnalogyExamples = MAX_ANALOGY_EXAMPLES,
        tagRidgeLambda = TAG_RIDGE_LAMBDA,
        personalGain = PERSONAL_GAIN,
        franchiseWeight = FRANCHISE_WEIGHT,
        axisWeight = AXIS_WEIGHT,
        franchiseShrink = FRANCHISE_SHRINK,
        axisRidgeLambda = AXIS_RIDGE_LAMBDA,
        slopePriorStrength = SLOPE_PRIOR_STRENGTH,
        maxSlope = MAX_SLOPE,
        maxTags = MAX_TAGS,
    )
}

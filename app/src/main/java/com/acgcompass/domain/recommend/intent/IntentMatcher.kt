package com.acgcompass.domain.recommend.intent

import com.acgcompass.domain.taste.TagCount
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 标签代表性：一个标签在这部作品里是「核心气质」还是「沾了一点」。
 *
 * `tagRel = (0.45·countStrength + 0.30·rankStrength)/0.75 × 来源置信`
 * - `countStrength = sqrt(count/maxCount)`：标注人数相对本作最热标签的份额；
 * - `rankStrength = 1 − rank/total`：在本作标签里排第几；
 * - 来源置信：有标注人数为 1，只有标签顺序时为 0.85。
 *
 * 与文档原式的两处差异，都来自 Bangumi 真实标签的检验：
 * 1. 人数强度用**份额的平方根**而不是对数比。对数压缩得太狠——《灵能百分百》的「校园」只有 27 人标
 *    （最热标签 2394 人），对数比仍有 0.43，足以被当成「校园番」；平方根份额是 0.11。
 * 2. 置信项只做乘性修正、不做加性底分：否则任何出现过的标签都有 0.25 的保底，
 *    「排在最后、没几个人标」的标签也能过阈值。
 *
 * 长篇稀释：50 集以上的作品什么情绪都沾一点。情绪 / 基调 / 结构类意图在 ≥50 集时 ×0.65、
 * ≥100 集时 ×0.45，除非该标签本身排在前 10（那就确实是它的主气质）。
 */
class TagRepresentativeness(tags: List<TagCount>, private val episodes: Int = 0) {

    private data class Entry(val rank: Int, val strength: Double)

    private val entries: Map<String, Entry>

    init {
        val cleaned = tags
            .map { IntentTagDictionary.normalize(it.name) to it.count }
            .filter { it.first.isNotEmpty() }
            .distinctBy { it.first }
        val maxCount = cleaned.maxOfOrNull { it.second } ?: 0
        val hasCounts = maxCount > 0
        val total = cleaned.size
        entries = cleaned.withIndex().associate { (index, pair) ->
            val (name, count) = pair
            val rankStrength = (1.0 - index.toDouble() / total).coerceIn(0.0, 1.0)
            // 没有标注人数（只有标签顺序）时，用名次同时充当人数强度，并降低来源置信。
            val countStrength = if (hasCounts) sqrt(count.coerceAtLeast(0).toDouble() / maxCount) else rankStrength
            val confidence = if (hasCounts) 1.0 else RANK_ONLY_CONFIDENCE
            val base = (COUNT_WEIGHT * countStrength + RANK_WEIGHT * rankStrength) / (COUNT_WEIGHT + RANK_WEIGHT)
            name to Entry(rank = index + 1, strength = (base * confidence).coerceIn(0.0, 1.0))
        }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 标签代表性 ∈ [0,1]；作品没有该标签为 0。[category] 用于长篇稀释。 */
    fun of(tag: String, category: IntentCategory? = null): Double {
        val entry = entries[tag] ?: return 0.0
        val diluted = category in DILUTED_CATEGORIES && entry.rank > DILUTION_EXEMPT_RANK
        val factor = when {
            !diluted -> 1.0
            episodes >= VERY_LONG_EPISODES -> VERY_LONG_FACTOR
            episodes >= LONG_EPISODES -> LONG_FACTOR
            else -> 1.0
        }
        return entry.strength * factor
    }

    /** 一组标签里代表性最强的一个（不做稀释）；用于主气质冲突检测。 */
    fun strongest(tags: Set<String>): Pair<String, Double>? =
        tags.mapNotNull { t -> entries[t]?.let { t to it.strength } }.maxByOrNull { it.second }

    private companion object {
        const val COUNT_WEIGHT = 0.45
        const val RANK_WEIGHT = 0.30
        const val RANK_ONLY_CONFIDENCE = 0.85
        const val LONG_EPISODES = 50
        const val VERY_LONG_EPISODES = 100
        const val LONG_FACTOR = 0.65
        const val VERY_LONG_FACTOR = 0.45
        const val DILUTION_EXEMPT_RANK = 10
        val DILUTED_CATEGORIES = setOf(IntentCategory.EMOTION, IntentCategory.TONE, IntentCategory.STRUCTURE)
    }
}

/** 意图匹配的可解释结果。 */
data class IntentExplain(
    /** 达到代表性阈值的所选标签。 */
    val matchedStrongTags: List<String>,
    /** 沾边但没到阈值的所选标签。 */
    val matchedWeakTags: List<String>,
    /** 基本缺失的关键意图。 */
    val missingImportantTags: List<String>,
    val matchedCombos: List<String>,
    /** 与所选意图冲突、且比意图本身更能代表这部作品的主气质标签。 */
    val dominantConflicts: List<String>,
)

data class IntentMatch(
    /** 意图契合度 ∈ [0,1]。 */
    val fit: Double,
    /** 主气质冲突强度 ∈ [0,1]：冲突标签的代表性超出所选意图平均代表性的部分。 */
    val dominantConflict: Double,
    /** 今晚分上限（0–100）；意图基本不符的作品不允许靠高口味分挤进前列。无封顶为 `null`。 */
    val scoreCap: Int?,
    val explain: IntentExplain,
)

/**
 * 「今晚想看的标签」→ 作品的意图契合度（纯 Kotlin，可离线单测）。
 *
 * 旧逻辑是「命中所选标签的个数 / 所选个数」，有两个问题：
 * 1. **不看代表性**——选「催泪」会推出只是有几段催泪桥段的长篇热血番；
 * 2. **多标签是 OR**——选「校园 + 恋爱 + 日常 + 搞笑」，只命中「搞笑」的超能力战斗番也能拿到加分。
 *
 * 这里按文档「意图匹配 VNext」实现：单意图按代表性阈值归一 → 算术 + 几何覆盖（缺一项整体明显下降）
 * → 命中数不足 / 关键意图缺失惩罚 → 组合模板加成 → 主气质冲突 → 意图不符封顶。
 */
object IntentMatcher {

    fun match(selectedTags: Collection<String>, rep: TagRepresentativeness): IntentMatch? {
        val bundles = selectedTags.map(IntentTagDictionary::bundleFor).distinctBy { it.id }
        if (bundles.isEmpty()) return null

        val rawMatch = bundles.associate { it.id to rawMatch(it, rep) }
        val sat = bundles.associate { b ->
            b.id to (rawMatch.getValue(b.id) / b.representativeThreshold).coerceIn(0.0, 1.0)
        }
        val n = bundles.size
        val hits = sat.values.count { it >= HIT_SAT }
        val coverage = sat.values.average()
        val geoCoverage = exp(sat.values.sumOf { ln(GEO_FLOOR + it) } / n).coerceAtMost(1.0)
        val intentCoverage = ARITHMETIC_WEIGHT * coverage + GEOMETRIC_WEIGHT * geoCoverage +
            HIT_RATIO_WEIGHT * hits.toDouble() / n

        var penalty = 0.0
        if (n >= MULTI_TAG_MIN) {
            val required = ceil(n * REQUIRED_HIT_SHARE).toInt()
            penalty += HIT_SHORTFALL_PENALTY * (required - hits).coerceAtLeast(0)
        }
        val missingKey = bundles.filter { it.isKey && sat.getValue(it.id) < MISSING_SAT }
        penalty += missingKey.sumOf { it.missingPenalty }

        // 主气质冲突：所选意图（及命中的组合模板）的冲突标签里，最能代表这部作品的那个。
        val core = rawMatch.values.average()
        val conflictTags = bundles.flatMapTo(HashSet()) { it.conflictTags }
        val selectedIds = bundles.mapTo(HashSet()) { it.id }
        val activeCombos = IntentTagDictionary.COMBOS.filter { selectedIds.containsAll(it.requiredGroups.keys) }
        activeCombos.forEach { conflictTags += it.conflictTags }
        // 用户自己选的标签不算冲突（选了「战斗 + 日常」就别拿战斗去否定日常）。
        conflictTags -= bundles.flatMapTo(HashSet()) { it.requiredTags + it.synonymTags }
        val strongestConflict = rep.strongest(conflictTags)
        val conflictStrength = strongestConflict?.second ?: 0.0
        val dominantConflict = (conflictStrength - core).coerceIn(0.0, 1.0)

        val (comboBonus, matchedCombos) = comboBonus(activeCombos, sat, selectedIds, conflictStrength)

        val fit = (intentCoverage - penalty + comboBonus).coerceIn(0.0, 1.0)
        return IntentMatch(
            fit = fit,
            dominantConflict = dominantConflict,
            scoreCap = scoreCap(n, fit),
            explain = IntentExplain(
                matchedStrongTags = bundles.filter { sat.getValue(it.id) >= HIT_SAT }.map { it.id },
                matchedWeakTags = bundles.filter { sat.getValue(it.id) in MISSING_SAT..<HIT_SAT }.map { it.id },
                missingImportantTags = missingKey.map { it.id },
                matchedCombos = matchedCombos,
                dominantConflicts = if (dominantConflict > CONFLICT_REPORT_MIN) {
                    listOfNotNull(strongestConflict?.first)
                } else {
                    emptyList()
                },
            ),
        )
    }

    /** 组合模板加成：必选意图都达标（且所选的可选意图至少一项达标）才算命中；主气质冲突时扣回。 */
    private fun comboBonus(
        combos: List<IntentComboTemplate>,
        sat: Map<String, Double>,
        selectedIds: Set<String>,
        conflictStrength: Double,
    ): Pair<Double, List<String>> {
        var bonus = 0.0
        val matched = ArrayList<String>()
        for (combo in combos) {
            val requiredOk = combo.requiredGroups.all { (id, min) -> sat.getValue(id) >= min }
            val optionalSelected = combo.optionalGroups.filter { it in selectedIds }
            val optionalOk = optionalSelected.isEmpty() ||
                optionalSelected.any { sat.getValue(it) >= combo.optionalMinSat }
            if (requiredOk && optionalOk) {
                bonus += combo.bonus
                matched += combo.displayName
                if (conflictStrength >= COMBO_CONFLICT_STRENGTH) bonus -= combo.conflictPenalty
            }
        }
        return bonus to matched
    }

    /** 单意图原始匹配：直接等价标签 > 近义（×0.85）> 沾边（×0.45）。 */
    private fun rawMatch(b: IntentTagBundle, rep: TagRepresentativeness): Double = maxOf(
        b.requiredTags.maxOfOrNull { rep.of(it, b.category) } ?: 0.0,
        SYNONYM_FACTOR * (b.synonymTags.maxOfOrNull { rep.of(it, b.category) } ?: 0.0),
        WEAK_FACTOR * (b.weakTags.maxOfOrNull { rep.of(it, b.category) } ?: 0.0),
    )

    /** 意图不匹配封顶：标签选得越多、契合度越低，今晚分的上限压得越狠。 */
    private fun scoreCap(selectedCount: Int, fit: Double): Int? = when {
        selectedCount >= MULTI_TAG_MIN && fit < MULTI_POOR_FIT -> CAP_MULTI_POOR
        selectedCount >= MULTI_TAG_MIN && fit < MULTI_WEAK_FIT -> CAP_MULTI_WEAK
        selectedCount == 2 && fit < PAIR_POOR_FIT -> CAP_FEW_POOR
        selectedCount == 1 && fit < SINGLE_POOR_FIT -> CAP_FEW_POOR
        else -> null
    }

    private const val MULTI_POOR_FIT = 0.45
    private const val MULTI_WEAK_FIT = 0.60
    private const val PAIR_POOR_FIT = 0.40
    private const val SINGLE_POOR_FIT = 0.30
    private const val CAP_MULTI_POOR = 55
    private const val CAP_MULTI_WEAK = 68
    private const val CAP_FEW_POOR = 60

    private const val SYNONYM_FACTOR = 0.85
    private const val WEAK_FACTOR = 0.45
    private const val ARITHMETIC_WEIGHT = 0.45
    private const val GEOMETRIC_WEIGHT = 0.45
    private const val HIT_RATIO_WEIGHT = 0.10
    private const val GEO_FLOOR = 0.05

    /** 归一后达到此值算「命中」；低于 [MISSING_SAT] 算「基本缺失」。 */
    private const val HIT_SAT = 0.999
    private const val MISSING_SAT = 0.35
    private const val MULTI_TAG_MIN = 3
    private const val REQUIRED_HIT_SHARE = 0.75
    private const val HIT_SHORTFALL_PENALTY = 0.20
    private const val COMBO_CONFLICT_STRENGTH = 0.65
    private const val CONFLICT_REPORT_MIN = 0.10
}

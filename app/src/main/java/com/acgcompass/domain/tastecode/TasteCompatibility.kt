package com.acgcompass.domain.tastecode

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** 两份口味码对比的结果。 */
data class CompatibilityResult(
    /** 契合度 0–100。只由两份口味码计算，交换双方结果不变。 */
    val score: Int,
    /** 按契合度分档的称号。 */
    val tier: CompatibilityTier,
    /** 相处模式：由契合度、打分风格和冲突标签决定。 */
    val type: CompatibilityType,
    /** 两人都喜欢的标签（按两人强度之和降序，带分类）。 */
    val sharedLikes: List<WeightedTag>,
    /** 两人都回避的标签。 */
    val sharedAvoids: List<WeightedTag>,
    /** 只有一方喜欢、另一方没提到的标签：第一个是 [a] 独爱，第二个是 [b] 独爱。 */
    val onlyLiked: Pair<List<WeightedTag>, List<WeightedTag>>,
    /** 一方喜欢、另一方回避的标签：第一个是 [a] 喜欢、[b] 回避，第二个反过来。 */
    val clashes: Pair<List<String>, List<String>>,
    /** 两人都比大众更偏爱（同号且都明显）的标签。 */
    val sharedDivergence: List<String>,
    /** 两人都放进代表作的作品：(条目, a 的评分, b 的评分)，按评分差从大到小。 */
    val commonWorks: List<Triple<Int, Int, Int>>,
    /** 对方给了高分、自己代表作里没有的作品（[b] → [a] 的推荐候选；打开方再用本地模型过滤）。 */
    val picksForA: List<RepresentativeWork>,
    val picksForB: List<RepresentativeWork>,
    /** 各部分的分数（0–1），界面上展开「契合度怎么算的」时显示。 */
    val parts: Map<CompatibilityPart, Float>,
    /** 双方的口味算法版本不同。 */
    val algorithmMismatch: Boolean,
)

enum class CompatibilityPart(val label: String) {
    TAGS("喜好与雷点"),
    DIMENSIONS("口味维度"),
    ERAS("年代"),
    STYLE("打分风格"),
    WORKS("共同作品的评分"),
}

/** 相处模式：由契合度和打分风格的差距决定。 */
enum class CompatibilityType(val label: String, val description: String) {
    SOUL_TWINS("同频共振", "喜欢的、雷的都差不多，连打分的松紧都像"),
    SAME_TASTE_DIFFERENT_SCALE("同好不同调", "爱看的东西很像，只是一个打分松、一个打分紧"),
    KINDRED("大方向一致", "主线口味一致，细节上各有偏好"),
    COMPLEMENTARY("互补型", "交集不多也很少互相踩雷，正好互相安利"),
    DIFFERENT_WORLDS("各有天地", "口味差得比较远，看看对方的代表作也许有惊喜"),
}

/** 按契合度分档的称号，[minScore] 为这一档的下限。 */
@Suppress("MagicNumber") // 分档下限
enum class CompatibilityTier(val minScore: Int, val label: String, val tagline: String) {
    MIRROR(96, "镜像人格", "两份口味码几乎可以互相冒名顶替"),
    DESTINED(90, "天选同担", "你推的 Ta 也在推，你雷的 Ta 也绕着走"),
    OLD_FRIENDS(82, "默契老友", "不用多解释，一句「这部好看」就够了"),
    WATCH_BUDDIES(74, "追番搭子", "一起追番基本不会吵架"),
    KINDRED(66, "口味相投", "大方向一致，偶尔会为某一部争两句"),
    AGREE_TO_DIFFER(58, "求同存异", "有共同语言，也有各自的坚持"),
    PARTIAL(50, "半路同好", "在某几类作品上能聊到一起"),
    FATED(40, "有缘人", "交集不多，但很值得互相安利"),
    PARALLEL(28, "平行世界", "同一个次元，不同的频道"),
    FAR_APART(0, "次元壁两端", "口味差得很远，正好拓宽彼此的片单"),
    ;

    companion object {
        fun of(score: Int): CompatibilityTier = entries.first { score >= it.minScore }
    }
}

/** 口味相性：比较两份口味码。 */
@Suppress("TooManyFunctions") // 每个对比维度一个小函数
object TasteCompatibility {
    private const val TAG_FULL_COSINE = 0.6
    private const val STYLE_LENIENCY_RANGE = 3.0
    private const val STYLE_SLOPE_RANGE = 1.5
    private const val WORKS_RATING_RANGE = 5.0
    private const val MIN_COMMON_WORKS = 3
    private const val FULL_COMMON_WORKS = 10
    private const val DIVERGENCE_MIN = 0.2f
    private const val PICK_MIN_RATING = 8
    private const val MAX_LIST = 8
    private const val MAX_SHARED = 16
    private const val MAX_ONLY = 6
    private const val PERCENT = 100
    private const val TWINS_SCORE = 80
    private const val KINDRED_SCORE = 60
    private const val COMPLEMENTARY_SCORE = 40
    private const val STYLE_CLOSE = 0.75f
    private const val COMPLEMENTARY_MAX_CLASHES = 2

    /** 各部分在契合度里的分量：标签最重，其次共同作品的评分。 */
    @Suppress("MagicNumber")
    private val WEIGHTS = mapOf(
        CompatibilityPart.TAGS to 0.4,
        CompatibilityPart.DIMENSIONS to 0.15,
        CompatibilityPart.ERAS to 0.15,
        CompatibilityPart.STYLE to 0.1,
        CompatibilityPart.WORKS to 0.2,
    )

    fun compare(a: TasteCode, b: TasteCode): CompatibilityResult {
        val parts = LinkedHashMap<CompatibilityPart, Float>()
        parts[CompatibilityPart.TAGS] = tagScore(a, b)
        dimensionScore(a, b)?.let { parts[CompatibilityPart.DIMENSIONS] = it }
        eraScore(a, b)?.let { parts[CompatibilityPart.ERAS] = it }
        parts[CompatibilityPart.STYLE] = styleScore(a.style, b.style)
        val common = commonWorks(a, b)
        worksScore(common)?.let { parts[CompatibilityPart.WORKS] = it }
        // 共同作品少时这一项的分量按部数缩小，而不是把分数往中间拉。
        val weights = parts.keys.associateWith { part ->
            WEIGHTS.getValue(part) * if (part == CompatibilityPart.WORKS) worksTrust(common.size) else 1.0
        }
        val score = (parts.entries.sumOf { weights.getValue(it.key) * it.value } / weights.values.sum() * PERCENT)
            .toInt().coerceIn(0, PERCENT)
        val clashes = clashes(a, b) to clashes(b, a)
        return CompatibilityResult(
            score = score,
            tier = CompatibilityTier.of(score),
            type = typeOf(score, parts.getValue(CompatibilityPart.STYLE), clashes.first.size + clashes.second.size),
            sharedLikes = shared(a.liked, b.liked),
            sharedAvoids = shared(a.avoided, b.avoided),
            onlyLiked = onlyLiked(a, b) to onlyLiked(b, a),
            clashes = clashes,
            sharedDivergence = sharedDivergence(a, b),
            commonWorks = common.sortedByDescending { abs(it.second - it.third) },
            picksForA = picks(from = b, excluding = a),
            picksForB = picks(from = a, excluding = b),
            parts = parts,
            algorithmMismatch = a.algorithmVersion != b.algorithmVersion,
        )
    }

    /** 喜欢记正、回避记负的标签向量的余弦；余弦 0.6 以上记满分，负相关记 0。 */
    private fun tagScore(a: TasteCode, b: TasteCode): Float {
        val cosine = cosine(tagVector(a), tagVector(b))
        return (cosine / TAG_FULL_COSINE).coerceIn(0.0, 1.0).toFloat()
    }

    private fun tagVector(code: TasteCode): Map<String, Double> {
        val vector = HashMap<String, Double>()
        code.liked.forEach { vector[it.tag] = (vector[it.tag] ?: 0.0) + it.weight }
        code.avoided.forEach { vector[it.tag] = (vector[it.tag] ?: 0.0) - it.weight }
        return vector
    }

    private fun dimensionScore(a: TasteCode, b: TasteCode): Float? {
        if (a.dimensions.isEmpty() || b.dimensions.isEmpty()) return null
        return cosine(a.dimensions.mapValues { it.value.toDouble() }, b.dimensions.mapValues { it.value.toDouble() })
            .coerceIn(0.0, 1.0).toFloat()
    }

    /** 1 − 年代分布的总变差距离。 */
    private fun eraScore(a: TasteCode, b: TasteCode): Float? {
        if (a.eraShares.isEmpty() || a.eraShares.size != b.eraShares.size) return null
        val distance = a.eraShares.zip(b.eraShares).sumOf { (x, y) -> abs(x - y).toDouble() } / 2
        return (1 - distance).coerceIn(0.0, 1.0).toFloat()
    }

    private fun styleScore(a: ScoringStyle, b: ScoringStyle): Float {
        val leniency = 1 - min(1.0, abs(a.leniency - b.leniency) / STYLE_LENIENCY_RANGE)
        val slope = 1 - min(1.0, abs(a.followsCrowd - b.followsCrowd) / STYLE_SLOPE_RANGE)
        return ((leniency + slope) / 2).toFloat()
    }

    private fun commonWorks(a: TasteCode, b: TasteCode): List<Triple<Int, Int, Int>> {
        val bRatings = b.works.associate { it.subjectId to it.rating }
        return a.works.mapNotNull { work -> bRatings[work.subjectId]?.let { Triple(work.subjectId, work.rating, it) } }
    }

    /** 共同作品不足 [MIN_COMMON_WORKS] 部不计这一项；否则按平均评分差打分。 */
    private fun worksScore(common: List<Triple<Int, Int, Int>>): Float? {
        if (common.size < MIN_COMMON_WORKS) return null
        return (1 - min(1.0, common.map { abs(it.second - it.third) }.average() / WORKS_RATING_RANGE)).toFloat()
    }

    /** 共同作品这一项的分量：[FULL_COMMON_WORKS] 部及以上为全额。 */
    private fun worksTrust(count: Int): Double = min(1.0, count / FULL_COMMON_WORKS.toDouble())

    private fun typeOf(score: Int, style: Float, clashCount: Int): CompatibilityType = when {
        score >= TWINS_SCORE && style >= STYLE_CLOSE -> CompatibilityType.SOUL_TWINS
        score >= KINDRED_SCORE && style < STYLE_CLOSE -> CompatibilityType.SAME_TASTE_DIFFERENT_SCALE
        score >= KINDRED_SCORE -> CompatibilityType.KINDRED
        score >= COMPLEMENTARY_SCORE && clashCount <= COMPLEMENTARY_MAX_CLASHES -> CompatibilityType.COMPLEMENTARY
        else -> CompatibilityType.DIFFERENT_WORLDS
    }

    private fun shared(a: List<WeightedTag>, b: List<WeightedTag>): List<WeightedTag> {
        val bWeights = b.associate { it.tag to it.weight }
        return a.filter { it.tag in bWeights }
            .map { it.copy(weight = it.weight + bWeights.getValue(it.tag)) }
            .sortedByDescending { it.weight }
            .take(MAX_SHARED)
    }

    /** [likes] 喜欢、[other] 既不喜欢也不回避的标签。 */
    private fun onlyLiked(likes: TasteCode, other: TasteCode): List<WeightedTag> {
        val mentioned = (other.liked + other.avoided).map { it.tag }.toSet()
        return likes.liked.filter { it.tag !in mentioned }.take(MAX_ONLY)
    }

    private fun clashes(likes: TasteCode, avoids: TasteCode): List<String> {
        val avoided = avoids.avoided.map { it.tag }.toSet()
        return likes.liked.filter { it.tag in avoided }.map { it.tag }.take(MAX_LIST)
    }

    private fun sharedDivergence(a: TasteCode, b: TasteCode): List<String> {
        val bDeltas = b.divergence.associate { it.tag to it.weight }
        return a.divergence.filter { tag ->
            val other = bDeltas[tag.tag] ?: return@filter false
            tag.weight * other > 0 && abs(tag.weight) >= DIVERGENCE_MIN && abs(other) >= DIVERGENCE_MIN
        }.map { it.tag }
    }

    private fun picks(from: TasteCode, excluding: TasteCode): List<RepresentativeWork> {
        val mine = excluding.works.map { it.subjectId }.toSet()
        return from.works.filter { it.rating >= PICK_MIN_RATING && it.subjectId !in mine }
            .sortedByDescending { it.rating }
            .take(MAX_LIST)
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val dot = a.entries.sumOf { (key, value) -> value * (b[key] ?: 0.0) }
        val norm = sqrt(a.values.sumOf { it * it }) * sqrt(b.values.sumOf { it * it })
        return if (norm == 0.0) 0.0 else dot / norm
    }
}

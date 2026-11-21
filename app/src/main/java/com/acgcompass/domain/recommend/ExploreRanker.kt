package com.acgcompass.domain.recommend

import kotlin.math.abs

/**
 * 探索队列的组批与反馈学习（纯 Kotlin、无 Android / IO 依赖，可离线单测）。
 *
 * 如果只按口味匹配度降序取前 10，一批里常常全是同一题材甚至同一系列，滑动「暂不 / 加入」
 * 也不会让后面的卡片有任何变化。所以这里做三件事：
 *
 * 1. **反馈学习**：从历史与本次的左右滑里学标签级偏好（Beta 平滑的加入率），给候选一个
 *    ±[MAX_FEEDBACK_ADJUST] 的修正——连续划走同一题材，后面同题材的卡片会自动下沉。
 * 2. **多样性**：MMR 重排 + 同系列最多一张，避免一批十张雷同。
 * 3. **跨界名额**：每批留出约三成位置给「口碑扎实、但题材在你常看范围之外」的作品，
 *    让画像有机会长出新的方向，而不是永远在舒适区里打转。
 */
object ExploreRanker {

    /**
     * 组批输入。
     *
     * @property utilityBase 效用的基础分（0–100）：预计评分在你自己评分里的位置（个人分位），建不了评分模型时是满意度旧口径。
     * @property familiarity 画像熟悉度 ∈ [0,1]（[com.acgcompass.domain.taste.TasteMatchResult.familiarity]）。
     * @property community10 社区评分（0–10）；未知为 `null`。
     * @property votes 社区评分人数。
     * @property tags 题材指纹（[RecommendSimilarity.genreFingerprint]）。
     */
    data class Candidate(
        val id: String,
        val utilityBase: Int,
        val familiarity: Double = 1.0,
        val community10: Double? = null,
        val votes: Int = 0,
        val franchiseKey: String = "",
        val tags: Set<String> = emptySet(),
    )

    /**
     * 一条兴趣反馈：题材指纹、是加分还是减分、权重。
     * 默认权重：加入待补池按一次计，「暂不」按半次计（划走可能只是今天不想看，不等于讨厌这个题材）。
     * 收藏状态与时间衰减见 [InterestSignals]。
     */
    data class Feedback(val tags: Set<String>, val liked: Boolean, val weight: Double = if (liked) 1.0 else SKIP_WEIGHT)

    data class Ranked(val candidate: Candidate, val utility: Double, val discovery: Boolean)

    /** 反馈修正的最大幅度（效用量纲 0–1，即最多 ±12 个匹配度点）。 */
    const val MAX_FEEDBACK_ADJUST: Double = 0.12

    /** Beta 先验强度：一个标签要有约这么多次滑动，其加入率才与先验各占一半话语权。 */
    private const val PRIOR_STRENGTH = 3.0

    /** 一次「暂不」相当于多少次明确表态（原因未知的拒绝不能当成稳定的负面偏好）。 */
    internal const val SKIP_WEIGHT = 0.5

    /** 学习反馈所需的最少滑动次数（太少时不修正，避免一两次误触带偏整批）。 */
    private const val MIN_FEEDBACK = 4

    /** 多样性权重：效用里扣掉与已入选卡片的最大题材相似度的比例。 */
    /** 随机组批时的高分段宽度（批大小的倍数）与扰动强度（效用以 0–1 计，0.06 约等于匹配度差 6 分内常会互换）。 */
    private const val RANDOM_SHORTLIST_FACTOR = 6
    private const val EXPLORATION_TEMPERATURE = 0.06

    private const val DIVERSITY_WEIGHT = 0.18

    /** 每批跨界名额占比。 */
    private const val DISCOVERY_SHARE = 0.3

    /** 跨界候选的门槛：口碑分与票数都得站得住，且画像对它不熟、也没有明确反感。 */
    private const val DISCOVERY_MIN_COMMUNITY = 7.2
    private const val DISCOVERY_MIN_VOTES = 300
    private const val DISCOVERY_MAX_FAMILIARITY = 0.55
    private const val DISCOVERY_MIN_MATCH = 40

    /** 跨界排序：口碑分超出门槛多少算「满分」，以及口碑相对陌生度的权重。 */
    private const val DISCOVERY_QUALITY_RANGE = 1.5
    private const val DISCOVERY_QUALITY_WEIGHT = 0.6

    private const val MATCH_SCALE = 100.0
    private const val EPSILON = 1e-9

    /**
     * 标签级反馈模型：`adjust(tag) = (likes + k·p0)/(n + k) − p0`，`p0` 为整体加入率。
     * 正值 = 这个题材你更常加入，负值 = 更常划走。
     */
    class FeedbackModel internal constructor(private val tagAdjust: Map<String, Double>) {

        /** 候选的反馈修正：其题材指纹里有反馈记录的标签的平均偏移，按幅度封顶。 */
        fun adjust(tags: Set<String>): Double {
            val known = tags.mapNotNull { tagAdjust[it] }
            if (known.isEmpty()) return 0.0
            return (known.average() * FEEDBACK_GAIN).coerceIn(-MAX_FEEDBACK_ADJUST, MAX_FEEDBACK_ADJUST)
        }

        val isEmpty: Boolean get() = tagAdjust.isEmpty()

        private companion object {
            /** 加入率偏移（约 ±0.5）→ 效用修正的增益。 */
            const val FEEDBACK_GAIN = 0.4
        }
    }

    fun feedbackModel(feedback: List<Feedback>): FeedbackModel {
        val weights = feedback.map { it.weight.coerceAtLeast(0.0) }
        val totalWeight = weights.sum()
        if (feedback.size < MIN_FEEDBACK || totalWeight <= EPSILON) return FeedbackModel(emptyMap())
        val baseRate = feedback.indices.sumOf { if (feedback[it].liked) weights[it] else 0.0 } / totalWeight
        val likes = HashMap<String, Double>()
        val total = HashMap<String, Double>()
        for ((i, f) in feedback.withIndex()) {
            for (tag in f.tags) {
                total[tag] = (total[tag] ?: 0.0) + weights[i]
                if (f.liked) likes[tag] = (likes[tag] ?: 0.0) + weights[i]
            }
        }
        val adjust = total.mapValues { (tag, n) ->
            ((likes[tag] ?: 0.0) + PRIOR_STRENGTH * baseRate) / (n + PRIOR_STRENGTH) - baseRate
        }.filterValues { abs(it) > EPSILON }
        return FeedbackModel(adjust)
    }

    /** 候选效用（0–1 量纲）：个人分位 + 兴趣修正。 */
    fun utility(c: Candidate, model: FeedbackModel): Double = c.utilityBase / MATCH_SCALE + model.adjust(c.tags)

    private fun isDiscovery(c: Candidate): Boolean = (c.community10 ?: 0.0) >= DISCOVERY_MIN_COMMUNITY &&
        c.votes >= DISCOVERY_MIN_VOTES &&
        c.familiarity <= DISCOVERY_MAX_FAMILIARITY &&
        c.utilityBase >= DISCOVERY_MIN_MATCH

    /** 组批过程中的已选集合：负责「不重复」与「同系列至多一张」两条约束。 */
    private class Selection {
        val picked = ArrayList<Ranked>()
        private val ids = HashSet<String>()
        private val franchises = HashSet<String>()

        fun isFree(c: Candidate): Boolean = c.id !in ids && (c.franchiseKey.isBlank() || c.franchiseKey !in franchises)

        fun add(c: Candidate, utility: Double, discovery: Boolean): Ranked {
            val ranked = Ranked(c, utility, discovery)
            picked += ranked
            ids += c.id
            if (c.franchiseKey.isNotBlank()) franchises += c.franchiseKey
            return ranked
        }

        fun maxSimilarity(c: Candidate): Double =
            picked.maxOfOrNull { RecommendSimilarity.jaccard(c.tags, it.candidate.tags) } ?: 0.0
    }

    /**
     * 组一批探索卡片。
     *
     * 先按 MMR 选出「口味主力」，再从符合跨界门槛的候选里按「口碑 × 陌生度」补足跨界名额，
     * 最后把跨界卡片均匀穿插进主力序列（不会一上来就是陌生题材，也不会全堆在最后没人看到）。
     */
    /**
     * @param random 不为 null 时在更宽的高分段里按效用加权随机挑（Gumbel 噪声，分数越高越容易被挑中），
     *   每次进来的顺序不再和「为你排行」一模一样；为 null 时完全确定（测试用）。
     */
    fun buildBatch(
        candidates: List<Candidate>,
        model: FeedbackModel,
        batchSize: Int,
        random: kotlin.random.Random? = null,
    ): List<Ranked> {
        if (candidates.isEmpty() || batchSize <= 0) return emptyList()
        val discoverySlots = (batchSize * DISCOVERY_SHARE).toInt()
        val byUtility = candidates
            .map { it to utility(it, model) }
            .sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.id })

        val selection = Selection()
        val main = pickMain(byUtility, selection, batchSize - discoverySlots, batchSize, random)
        val discoveries = pickDiscoveries(candidates, model, selection, discoverySlots)
        // 跨界名额没用完（候选池太小 / 没有够格的）→ 用主力候选补满，不留空位。
        val filler = ArrayList<Ranked>()
        val missing = batchSize - main.size - discoveries.size
        for ((c, u) in byUtility) {
            if (filler.size >= missing) break
            if (selection.isFree(c)) filler += selection.add(c, u, discovery = false)
        }
        return interleave(main + filler, discoveries)
    }

    /** 主力：只在效用最高的一段里做 MMR（控制复杂度，也避免为了多样性捞出低分作品）。 */
    private fun pickMain(
        byUtility: List<Pair<Candidate, Double>>,
        selection: Selection,
        slots: Int,
        batchSize: Int,
        random: kotlin.random.Random? = null,
    ): List<Ranked> {
        val factor = if (random != null) RANDOM_SHORTLIST_FACTOR else SHORTLIST_FACTOR
        val shortlist = byUtility.take(maxOf(batchSize * factor, MIN_SHORTLIST))
        // 每个候选一份固定的随机扰动（同一批里不变），再和多样性惩罚一起比较。
        val noise = shortlist.associate { (c, _) ->
            c.id to (random?.let { gumbel(it) * EXPLORATION_TEMPERATURE } ?: 0.0)
        }
        val main = ArrayList<Ranked>(slots)
        while (main.size < slots) {
            val next = shortlist.filter { selection.isFree(it.first) }.maxWithOrNull(
                compareBy<Pair<Candidate, Double>> { (c, u) ->
                    u - DIVERSITY_WEIGHT * selection.maxSimilarity(c) + noise.getValue(c.id)
                }.thenByDescending { it.first.id },
            ) ?: break
            main += selection.add(next.first, next.second, discovery = false)
        }
        return main
    }

    /** 跨界：口碑越好、画像越陌生越优先；同样受系列去重约束。 */
    private fun pickDiscoveries(
        candidates: List<Candidate>,
        model: FeedbackModel,
        selection: Selection,
        slots: Int,
    ): List<Ranked> {
        val discoveries = ArrayList<Ranked>(slots)
        val pool = candidates.filter(::isDiscovery)
            .sortedWith(compareByDescending<Candidate> { discoveryScore(it, model) }.thenBy { it.id })
        for (c in pool) {
            if (discoveries.size >= slots) break
            if (selection.isFree(c)) discoveries += selection.add(c, utility(c, model), discovery = true)
        }
        return discoveries
    }

    private fun discoveryScore(c: Candidate, model: FeedbackModel): Double {
        val quality = (((c.community10 ?: 0.0) - DISCOVERY_MIN_COMMUNITY) / DISCOVERY_QUALITY_RANGE).coerceIn(0.0, 1.0)
        val unfamiliar = 1.0 - c.familiarity.coerceIn(0.0, 1.0)
        return DISCOVERY_QUALITY_WEIGHT * quality + (1.0 - DISCOVERY_QUALITY_WEIGHT) * unfamiliar + model.adjust(c.tags)
    }

    /** 把跨界卡片均匀插入主力序列：第一张永远是主力，之后大约每三张出现一张跨界。 */
    private fun interleave(main: List<Ranked>, discoveries: List<Ranked>): List<Ranked> {
        if (discoveries.isEmpty() || main.isEmpty()) return main + discoveries
        val out = ArrayList<Ranked>(main.size + discoveries.size)
        val step = maxOf(MIN_DISCOVERY_GAP, main.size / discoveries.size)
        var d = 0
        for ((i, m) in main.withIndex()) {
            out += m
            if ((i + 1) % step == 0 && d < discoveries.size) out += discoveries[d++]
        }
        while (d < discoveries.size) out += discoveries[d++]
        return out
    }

    /**
     * 本次滑动后重排**尚未看到**的卡片：`cards[0..keepUntil]` 原样保留（当前卡不能在用户眼前跳走），
     * 其后的卡片按最新反馈效用重排。跨界卡片不参与下沉排序以外的特殊处理——它们同样受反馈影响。
     */
    fun <T> rerankRemaining(
        cards: List<T>,
        keepUntil: Int,
        model: FeedbackModel,
        candidateOf: (T) -> Candidate,
    ): List<T> {
        val stable = (keepUntil + 1).coerceIn(0, cards.size)
        if (cards.size - stable < 2 || model.isEmpty) return cards
        val head = cards.take(stable)
        val tail = cards.drop(stable)
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<T>> { utility(candidateOf(it.value), model) }.thenBy { it.index },
            )
            .map { it.value }
        return head + tail
    }

    /** 两张跨界卡片之间至少隔开的主力卡片数。 */
    private const val MIN_DISCOVERY_GAP = 2
    private const val SHORTLIST_FACTOR = 4
    private const val MIN_SHORTLIST = 24
}

private const val GUMBEL_EPSILON = 1e-12

/** 标准 Gumbel 分布的一个样本：在效用上加这份噪声再取最大，等价于按 exp(效用 / 温度) 加权抽样。 */
private fun gumbel(random: kotlin.random.Random): Double {
    val u = random.nextDouble().coerceIn(GUMBEL_EPSILON, 1.0 - GUMBEL_EPSILON)
    return -kotlin.math.ln(-kotlin.math.ln(u))
}

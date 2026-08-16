package com.acgcompass.domain.recommend

import com.acgcompass.domain.taste.TasteScoringParams
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * 「今晚看什么」精排与三档选片（纯 Kotlin、无 Android / IO 依赖，可离线单测）。
 *
 * 若只从综合分前 12 名里随机抽 3 部再贴上 稳妥 / 探索 / 随机 的标签，三档没有语义差别，
 * 同系列的几季可能同时出现，时间预算也只能粗粒度硬过滤。这里用上 [TasteScoringParams] 里的
 * 今晚权重，并让三档各司其职：
 *
 * - **稳妥**：今晚分最高的一部（口味 + 当下意图 + 时间拟合 + 口碑 …）。
 * - **探索**：MMR 多样性重排——在高分候选里挑一部与「稳妥」题材拉开的，避免三部都是同一口味。
 * - **随机**：对剩余高分候选做 softmax 加权抽样，质量有保底但每次不同。
 *
 * 三档互不重复，且同一系列（[RecommendSimilarity.franchiseKey]）最多占一个名额。
 */
@Suppress("MagicNumber", "ReturnCount") // 系数与分段阈值即算法定义本身；提前返回表达「缺数据 → 中性」。
object TonightRanker {

    /**
     * 精排输入：一部通过硬过滤的候选。
     *
     * @property taste 个人口味分量 ∈ [0,1]（12 维引擎的预计满意度 / 100，或旧标签重合估计）。
     * @property intentFit 当晚意图契合度 ∈ [0,1]（[com.acgcompass.domain.recommend.intent.IntentMatcher]）；
     *   用户未选标签时为 `null`（该项权重让给口味）。
     * @property dominantConflict 主气质冲突强度 ∈ [0,1]。
     * @property scoreCap 意图不符封顶（0–100）；`null` = 不封顶。
     * @property community10 贝叶斯社区均分（0–10）；无评分为 `null`（按中性处理，不伪造）。
     * @property sessionFit 时间拟合度 ∈ [0,1]（[sessionFit]）。
     * @property completionEase 补完轻松度 ∈ [0,1]（[completionEase]）。
     * @property daysSinceExposure 距上次在「今晚看什么」被推荐的天数；从未推荐过为 `null`。
     * @property releaseYear 首播年份（新鲜度用）；未知为 `null`。
     * @property franchiseKey 系列归并键。
     * @property tags 题材指纹（[RecommendSimilarity.genreFingerprint]），多样性比较用。
     */
    data class Candidate(
        val id: String,
        val taste: Double,
        val intentFit: Double? = null,
        val dominantConflict: Double = 0.0,
        val scoreCap: Int? = null,
        val community10: Double? = null,
        val sessionFit: Double = NEUTRAL,
        val completionEase: Double = NEUTRAL,
        val daysSinceExposure: Double? = null,
        val releaseYear: Int? = null,
        val franchiseKey: String = "",
        val tags: Set<String> = emptySet(),
    )

    /** 三档角色。 */
    enum class Slot { SAFE, EXPLORE, WILDCARD }

    data class Pick(val candidate: Candidate, val slot: Slot, val score: Double)

    private const val NEUTRAL = 0.5

    /** 新鲜度：近 [FRESHNESS_WINDOW_YEARS] 年内线性衰减，更早的作品不继续扣分。 */
    private const val FRESHNESS_WINDOW_YEARS = 12.0

    /** 重复推荐惩罚的半衰期（天）：刚推过的明显降权，约两周后基本恢复。 */
    private const val REPEAT_HALF_LIFE_DAYS = 5.0

    /** 进入「探索 / 随机」候选的高分段规模。 */
    private const val TOP_POOL = 16

    /** 随机档 softmax 温度：越小越接近「总挑最高分」，越大越均匀。 */
    private const val WILDCARD_TEMPERATURE = 0.06

    /**
     * 今晚分（越大越值得今晚看，约 [0,1]）。各项权重取自 [TasteScoringParams] 的 `TONIGHT_W_*`；
     * 未选标签时意图权重并入口味，保证总权重不变。最后应用意图不符封顶：
     * 选了一组标签却基本对不上的作品，口味分再高也不许挤进前列。
     */
    fun score(c: Candidate, currentYear: Int): Double {
        val p = TasteScoringParams
        val wIntent = if (c.intentFit != null) p.TONIGHT_W_INTENT_TAG else 0.0
        val wTaste = p.TONIGHT_W_TASTE + (p.TONIGHT_W_INTENT_TAG - wIntent)
        val community = c.community10?.let { ((it - 5.0) / 4.0).coerceIn(0.0, 1.0) } ?: NEUTRAL
        val novelty = c.daysSinceExposure?.let { 1.0 - repeatDecay(it) } ?: 1.0
        val positive = wTaste * c.taste.coerceIn(0.0, 1.0) +
            wIntent * (c.intentFit ?: 0.0).coerceIn(0.0, 1.0) +
            p.TONIGHT_W_TIME_FIT * c.sessionFit.coerceIn(0.0, 1.0) +
            p.TONIGHT_W_COMMUNITY * community +
            p.TONIGHT_W_NOVELTY * novelty +
            p.TONIGHT_W_COMPLETION_EASE * c.completionEase.coerceIn(0.0, 1.0) +
            p.TONIGHT_W_FRESHNESS * freshness(c.releaseYear, currentYear)
        val penalty = (c.daysSinceExposure?.let { p.TONIGHT_W_REPEAT_PENALTY * repeatDecay(it) } ?: 0.0) +
            p.TONIGHT_W_DOMINANT_CONFLICT * c.dominantConflict.coerceIn(0.0, 1.0)
        val raw = positive - penalty
        return c.scoreCap?.let { minOf(raw, it / 100.0) } ?: raw
    }

    private fun repeatDecay(days: Double): Double = 2.0.pow(-days.coerceAtLeast(0.0) / REPEAT_HALF_LIFE_DAYS)

    private fun freshness(year: Int?, currentYear: Int): Double {
        if (year == null) return NEUTRAL
        val age = (currentYear - year).coerceAtLeast(0)
        return (1.0 - age / FRESHNESS_WINDOW_YEARS).coerceIn(0.0, 1.0)
    }

    /**
     * 时间拟合度 ∈ [0,1]：这段时间用来看这部作品合不合适。
     *
     * - 今晚能看完（总时长 ≤ 预算）：用 [TasteScoringParams.timeFit] 衡量「刚好填满」的程度，地板 0.6
     *   （能看完总归不差，但 3 小时只看一集短片不算最佳）。
     * - 看不完但单集放得下：能开个头，按「今晚能推进的比例」给 0.35–0.6。
     * - 单集都放不下（20 分钟配剧场版）：0.05。
     * - 时长未知：中性 0.5（不因元数据缺失误伤）。
     *
     * @param budgetMinutes 可用分钟；`null` = 不限（周末通宵），按 [WEEKEND_MINUTES] 计。
     */
    fun sessionFit(episodes: Int?, episodeMinutes: Int?, budgetMinutes: Int?): Double {
        val budget = budgetMinutes ?: WEEKEND_MINUTES
        val eps = episodes?.takeIf { it > 0 }
        val perEp = episodeMinutes?.takeIf { it > 0 }
            ?: if (eps != null) TasteScoringParams.RUNTIME_TV_PER_EP else null
        if (perEp == null) return NEUTRAL
        if (perEp > budget * SINGLE_EPISODE_SLACK) return 0.05
        if (eps == null) return NEUTRAL
        val total = eps * perEp
        return if (total <= budget * FINISH_SLACK) {
            0.6 + 0.4 * TasteScoringParams.timeFit(total, budget)
        } else {
            0.35 + 0.25 * (budget.toDouble() / total).coerceIn(0.0, 1.0)
        }
    }

    /** 单集时长是否放得进预算（硬过滤用）：未知时长一律放行。 */
    fun episodeFitsBudget(episodeMinutes: Int?, budgetMinutes: Int?): Boolean {
        val budget = budgetMinutes ?: return true
        val perEp = episodeMinutes?.takeIf { it > 0 } ?: return true
        return perEp <= budget * SINGLE_EPISODE_SLACK
    }

    /** 补完轻松度：已完结且短 → 高；长篇 / 未完结 → 低；未知 → 中性。 */
    fun completionEase(episodes: Int?, finished: Boolean?): Double {
        val lengthPart = when {
            episodes == null || episodes <= 0 -> NEUTRAL
            episodes <= 13 -> 1.0
            episodes <= 26 -> 0.7
            episodes <= 52 -> 0.4
            else -> 0.15
        }
        val finishedPart = when (finished) {
            true -> 1.0
            false -> 0.4
            null -> 0.7
        }
        return lengthPart * finishedPart
    }

    private const val WEEKEND_MINUTES = 600
    private const val SINGLE_EPISODE_SLACK = 1.25
    private const val FINISH_SLACK = 1.15

    /**
     * 三档选片。
     *
     * @param count 需要的部数（1 = 单一推荐模式，只出稳妥档）。
     * @param random 随机源（注入以便测试复现）。
     * @return 按 稳妥 → 探索 → 随机 顺序的结果；候选不足时档位缺省，绝不重复或凑数。
     */
    fun pick(candidates: List<Candidate>, count: Int, currentYear: Int, random: Random = Random.Default): List<Pick> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val scored = candidates
            .map { it to score(it, currentYear) }
            .sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.id })

        val picks = ArrayList<Pick>(count)
        val usedFranchises = HashSet<String>()
        fun take(entry: Pair<Candidate, Double>, slot: Slot) {
            picks += Pick(entry.first, slot, entry.second)
            entry.first.franchiseKey.takeIf { it.isNotBlank() }?.let { usedFranchises += it }
        }
        fun available(entry: Pair<Candidate, Double>): Boolean = picks.none { it.candidate.id == entry.first.id } &&
            (entry.first.franchiseKey.isBlank() || entry.first.franchiseKey !in usedFranchises)

        take(scored.first(), Slot.SAFE)
        if (count == 1) return picks

        val topPool = scored.take(TOP_POOL)

        // 探索档：MMR —— λ·相关度 − (1−λ)·与已选作品的最大题材相似度。
        val lambda = TasteScoringParams.MMR_LAMBDA
        topPool.filter(::available)
            .maxWithOrNull(
                compareBy<Pair<Candidate, Double>> { entry ->
                    val maxSim = picks.maxOf { RecommendSimilarity.jaccard(entry.first.tags, it.candidate.tags) }
                    lambda * entry.second - (1.0 - lambda) * maxSim
                }.thenByDescending { it.first.id },
            )
            ?.let { take(it, Slot.EXPLORE) }
        if (count == 2) return picks

        // 随机档：对剩余高分候选按 softmax(score/T) 加权抽样。
        val rest = topPool.filter(::available)
        weightedSample(rest, random)?.let { take(it, Slot.WILDCARD) }
        return picks
    }

    private fun weightedSample(entries: List<Pair<Candidate, Double>>, random: Random): Pair<Candidate, Double>? {
        if (entries.isEmpty()) return null
        val best = entries.maxOf { it.second }
        val weights = entries.map { exp((it.second - best) / WILDCARD_TEMPERATURE) }
        var r = random.nextDouble() * weights.sum()
        for (i in entries.indices) {
            r -= weights[i]
            if (r <= 0.0) return entries[i]
        }
        return entries.last()
    }
}

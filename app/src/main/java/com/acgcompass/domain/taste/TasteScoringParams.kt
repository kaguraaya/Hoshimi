package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * 口味匹配与推荐算法的默认参数和核心公式。
 *
 * 纯 Kotlin、无 Android/IO 依赖，便于单元 / 属性测试与离线回归。所有数值均直接取自文档
 * 「默认参数、复现实验」与各层公式，集中于此以避免散落、便于统一调参与 AB 对比。算法版本号
 * [ALGORITHM_VERSION] 随重大公式/参数变动递增，用于画像缓存失效与回归判定。
 */
object TasteScoringParams {

    /** 算法版本号（画像缓存键 / 回归判定用；公式或默认参数重大变动时 +1）。：IDF 逆频 + 中性点下移 + 口味门控社区融合 + 锚点上调。 */
    const val ALGORITHM_VERSION: Int = 3

    // region 样本权重

    /** 画像真实样本上限：50 部信息量最高作品（含全部最高分 / 最低分作品）。 */
    const val MAX_PROFILE_SAMPLES: Int = 50

    /** 时间半衰期（天）：约 18 个月，最近两年评分更重要但老作品不被抹掉。 */
    const val TIME_HALF_LIFE_DAYS: Double = 540.0

    /** 时间权重地板：再旧的评分也保留此最低话语权。 */
    const val TIME_WEIGHT_FLOOR: Double = 0.30

    /** 高分例外地板：10 分作品时间权重不低于此（防「人生锚点」被近因冲淡）。 */
    const val TIME_FLOOR_RATING_10: Double = 0.85

    /** 高分例外地板：9 分作品时间权重不低于此。 */
    const val TIME_FLOOR_RATING_9: Double = 0.70

    /** 极端分加权：评分 ∈ {1,2,9,10} 时样本权重乘此系数（其余 1.0）。 */
    const val EXTREME_WEIGHT: Double = 1.15

    /** 评论长度归一上限（字符）：超过即拿满评论长度加成。 */
    const val COMMENT_LEN_FULL: Int = 80

    /**
     * 显式评分 → 偏好值 `pref(r)`（文档「故意偏激」分段映射，服务「敢给敢拉开」）：
     * 10→+1.25, 9→+1.00, 8→+0.70, 7→+0.35, 6→+0.10, 5→-0.10, 4→-0.35, 3→-0.65, 2→-0.95, 1→-1.20。
     * 越界 / 缺失返回 0（中性，不参与正负向量）。
     */
    fun pref(rating: Int?): Double = when (rating) {
        10 -> 1.25
        9 -> 1.00
        8 -> 0.70
        7 -> 0.35
        6 -> 0.10
        5 -> -0.10
        4 -> -0.35
        3 -> -0.65
        2 -> -0.95
        1 -> -1.20
        else -> 0.0
    }

    /** 评分中心化斜率（每偏离中性点 1 分的偏好增量）。 */
    const val PREF_SLOPE: Double = 0.35

    /**
     * 评分中心化**中性点下移量**：`neutral = userMean − PREF_NEUTRAL_BIAS`。
     *
     * 纯均分中心化会对高均分用户过度惩罚接近均值的评分。下移中性点后，只有明显低于个人均值的评分
     * 才形成强负向信号。留一法验证中 0.5 的区分度优于 0 和 1.0。
     */
    const val PREF_NEUTRAL_BIAS: Double = 0.5

    /** 评分中心化偏好绝对值上限（对齐绝对 [pref] 的 10 分强度）。 */
    const val PREF_MAX_ABS: Double = 1.25

    /**
     * 评分 → 偏好值（**用户均分中心化**版）：以用户自己的平均分为中性点，
     * `pref = clip((rating − userMean)·[PREF_SLOPE], ±[PREF_MAX_ABS])`。
     *
     * 根因（真实数据 LOO 盲测 Spearman≈0）：绝对 [pref] 以 5.5 为中性点，对「均分偏高（如 7.0）」
     * 的用户，6–7 分（其实是平庸片）仍记正偏好 → 用户**看得多但只给中等分**的题材（如某长番系列）
     * 累积成强正向、主导画像，而**看得少却给高分**的挚爱题材被淹没 → 预测分与真实评分排序几乎不相关。
     * 中心化后 6–7 分≈0 贡献，只有高于自己均分的作品才推正偏好、低于的推负偏好，画像回归「相对偏爱」。
     */
    fun prefCentered(rating: Int?, neutral: Double, slope: Double = PREF_SLOPE): Double {
        if (rating == null) return 0.0
        return ((rating - neutral) * slope).coerceIn(-PREF_MAX_ABS, PREF_MAX_ABS)
    }

    /**
     * 时间权重 `w_time = 0.3 + 0.7·2^(-Δdays/540)`，再对 10/9 分施加高分例外地板。
     * @param ageDays 该评分距「最近一条评分」的天数（< 0 视为 0）。
     */
    fun timeWeight(ageDays: Double, rating: Int?): Double {
        val base = TIME_WEIGHT_FLOOR + 0.7 * Math.pow(2.0, -max(0.0, ageDays) / TIME_HALF_LIFE_DAYS)
        val floored = when (rating) {
            10 -> max(base, TIME_FLOOR_RATING_10)
            9 -> max(base, TIME_FLOOR_RATING_9)
            else -> base
        }
        return floored.coerceIn(0.0, 1.0)
    }

    /** 极端分加权：r ∈ {1,2,9,10} → [EXTREME_WEIGHT]，否则 1.0。 */
    fun extremeWeight(rating: Int?): Double =
        if (rating != null && rating in intArrayOf(1, 2, 9, 10)) EXTREME_WEIGHT else 1.0

    /** 评论置信权重：`1 + 0.1·I(comment非空) + 0.1·min(len,80)/80`（无评论自然退化为 1.0）。 */
    fun commentWeight(comment: String?): Double {
        val len = comment?.trim()?.length ?: 0
        if (len == 0) return 1.0
        return 1.0 + 0.1 + 0.1 * min(len, COMMENT_LEN_FULL).toDouble() / COMMENT_LEN_FULL
    }

    /** 单样本最终权重 `w_i = w_time · w_extreme · w_comment`。 */
    fun sampleWeight(ageDays: Double, rating: Int?, comment: String?): Double =
        timeWeight(ageDays, rating) * extremeWeight(rating) * commentWeight(comment)

    // endregion

    // region 画像聚合

    /**
     * subject 内标签强度归一 + 对数压缩：`q_xk = log(1+count_xk) / log(1+maxCount_x)`。
     * `maxCount` ≤ 0 时返回 0（该作品无有效标签计数）。
     */
    fun tagStrength(count: Int, maxCountInSubject: Int): Double {
        if (maxCountInSubject <= 0) return 0.0
        return ln(1.0 + count.coerceAtLeast(0)) / ln(1.0 + maxCountInSubject)
    }

    /** 题材组合挖掘：最小支持度（低于此视为偶然命中，丢弃）。 */
    const val COMBO_MIN_SUPPORT: Double = 1.8

    /** 题材组合挖掘：最大阶数（只做二元 / 三元，不做四元以上）。 */
    const val COMBO_MAX_ORDER: Int = 3

    /** 题材组合挖掘：组合至少出现在多少部正向作品中才保留。 */
    const val COMBO_MIN_POSITIVE_WORKS: Int = 2

    /** 组合强度 `strength(C) = signed_support(C) / |C|^0.3` 的阶数惩罚指数。 */
    const val COMBO_ORDER_PENALTY_EXP: Double = 0.3

    /** 组合命中强度：`strength = signed_support / size^0.3`（size 为组合元素个数）。 */
    fun comboStrength(signedSupport: Double, comboSize: Int): Double =
        signedSupport / Math.pow(comboSize.coerceAtLeast(1).toDouble(), COMBO_ORDER_PENALTY_EXP)

    // endregion

    // region 候选匹配

    /** 各类相似度负向惩罚系数 λ_c（正向减负向时的负向权重）。 */
    const val LAMBDA_NEGATIVE: Double = 0.75

    /**
     * 负向量最小支持度：标签至少需要在指定数量的已评分作品中出现，
     * 才有资格进入负向偏好向量。根因：评分中心化（[prefCentered]）以用户均分为中性点，任何**略低于
     * 自己均分**的作品其标签都会累积成负——对只看过 1 部的稀疏题材（如 百合 / 美食）尤其反直觉：
     * 一次「还行但没到我平均线」的观看就把整个题材打成「你不喜欢」，反向拉低匹配并给出负面理由。
     * 要求 ≥2 部作品一致提供负向证据才判负，可滤除这类偶然误判；真正被多部低分作品支撑的反口味
     * 题材（≥2 部）不受影响，仍正常参与负向惩罚。
     */
    const val MIN_NEGATIVE_SUPPORT: Int = 2

    /**
     * 画像标签 **IDF 逆频权重强度** γ（修 Spearman 区分度差）。0=关闭（旧行为）；1=全量 IDF。
     *
     * 根因：`TasteRawScorer.simCategory` 用 `pos/positiveL1` 归一，**高频通用标签**（该用户几乎每部都带的
     * 校园 / 恋爱 / 治愈 / 轻小说改 / 某常用工作室）在正向量里累计权重极大，主导 posPart；而**低频独特挚爱**
     * 标签（如《游戏人生》的 异世界 / 游戏 / 智斗、《命运石之门》的 科幻 / 时间旅行）因只出现在一两部作品、
     * 累计权重小被淹没。结果：套通用标签的平庸番虚高（中二病 rate7→79）、独特挚爱番塌低（NGNL rate10→48），
     * 真实数据 LOO Spearman 仅 0.291（文档目标 0.53）。IDF 按「标签在用户多少部作品出现」下调高频、保留低频，
     * 让「能区分你不同偏好」的标签主导排序。因 posPart 尺度不变，仅**相对**重加权生效——各标签 df 相同的合成
     * 测试几乎不受影响（防过拟合护栏保持稳定），仅在 df 分布倾斜的真实数据上拉开区分度。
     */
    const val PROFILE_IDF_STRENGTH: Double = 0.85

    /**
     * 标签 IDF 相对权重因子 ∈ (0,1]：`(1-γ) + γ·norm`，其中 `norm = ln(1+n/df)/ln(1+n) ∈ (0,1]`
     * （df=1 的独特标签→1.0；df=n 的全覆盖通用标签→最小）。γ=[PROFILE_IDF_STRENGTH] 控制 IDF 强度。
     * df / n / γ 非法（≤0）时返回 1.0（不改变权重）。
     */
    fun idfFactor(df: Int, n: Int, strength: Double = PROFILE_IDF_STRENGTH): Double {
        if (df <= 0 || n <= 0 || strength <= 0.0) return 1.0
        val safeDf = df.coerceAtMost(n).toDouble()
        val norm = (ln(1.0 + n.toDouble() / safeDf) / ln(1.0 + n.toDouble())).coerceIn(0.0, 1.0)
        val s = strength.coerceIn(0.0, 1.0)
        return ((1.0 - s) + s * norm).coerceIn(0.05, 1.0)
    }

    /** 防 0 除小量。 */
    const val EPSILON: Double = 1e-6

    /**
     * 社区评分弱引导：`clip((score-6.5)/2, -1, 1) · clip(log(1+votes)/log(20000), 0.3, 1)`。
     * 仅以 [TasteCategory.COMMUNITY] 的权重（0.03）进入总分，绝不主导个性化（此项进 rawZ）。
     */
    fun communityGuidance(bangumiScore10: Double?, votes: Int?): Double {
        if (bangumiScore10 == null || bangumiScore10 <= 0.0) return 0.0
        val scorePart = ((bangumiScore10 - 6.5) / 2.0).coerceIn(-1.0, 1.0)
        val v = votes?.coerceAtLeast(0) ?: 0
        val votePart = (ln(1.0 + v) / ln(20000.0)).coerceIn(0.3, 1.0)
        return scorePart * votePart
    }

    // region 口味门控社区融合（仅未评分作品的后置分数修正，不进 rawZ/校准）

    /**
     * 社区融合最大权重 `w_c^max`。仅在**画像信号最弱（分≈中性 50）**时逼近此上限，
     * 画像越有立场（分越远离 50）社区权重越小 → 强命中 / 强反口味仍由口味主导。
     *
     * 依据（用户真实 62 部 LOO 盲测）：纯标签内容信号排序上限仅 Spearman≈0.334，而社区评分与该用户
     * 打分相关高达 0.580——本人是「品质 / 共识驱动 + 跨题材」型（《Clannad》10 分但同社催泪的《AB!》《未闻
     * 花名》仅 5–6，纯标签无从区分；《东京喰种》《电锯人》等题材离群挚爱画像无同类支撑）。门控融合把社区分
     * 作为「口味没话说时」的补充证据，LOO Spearman 提升到≈0.55（达文档 0.53 目标），且经 [communityBlendedScore]
     * 的门控设计不会把强反口味作品抬高（守卫测试 anti≤55 仍成立）。
     */
    const val COMMUNITY_BLEND_MAX: Double = 0.80

    /** 门控陡度指数：`gate = (1-|score-50|/50)^EXP`，>1 使门更快关闭、更保护强立场口味。 */
    const val COMMUNITY_GATE_EXP: Double = 1.5

    /** 社区评分票数置信：`clip(log(1+votes)/log(20000), 0, 1)`（票少→不轻信社区，趋 0）。 */
    fun votesConfidence(votes: Int?): Double {
        val v = votes?.coerceAtLeast(0) ?: 0
        return (ln(1.0 + v) / ln(20000.0)).coerceIn(0.0, 1.0)
    }

    /**
     * 口味门控社区融合：以画像分 [profileScore]（5–95）为主，按「口味立场弱 + 社区票数足」的程度
     * 融入社区评分。**仅用于未评分作品**（已评分作品走 [ratedAnchorProbability] 显式锚）。
     *
     * `gate = (1 − |p−50|/50)^EXP`（口味越中性门越开）；`w_c = COMMUNITY_BLEND_MAX · gate · votesConf`；
     * `out = (1−w_c)·p + w_c·comm`，其中 `comm = clip(bangumiScore·10, 0, 100)`。
     * 无社区评分时原样返回画像分。
     */
    fun communityBlendedScore(profileScore: Int, bangumiScore10: Double?, votes: Int?): Int {
        if (bangumiScore10 == null || bangumiScore10 <= 0.0) return profileScore
        val comm = (bangumiScore10 * 10.0).coerceIn(0.0, 100.0)
        val gate = Math.pow((1.0 - abs(profileScore - 50.0) / 50.0).coerceIn(0.0, 1.0), COMMUNITY_GATE_EXP)
        val wc = COMMUNITY_BLEND_MAX * gate * votesConfidence(votes)
        val out = (1.0 - wc) * profileScore + wc * comm
        return out.toInt().coerceIn(5, 95)
    }

    // endregion

    /** 十二维线性融合权重（= 各 [TasteCategory.defaultWeight]，集中此处便于调参）。 */
    val FUSION_WEIGHTS: Map<TasteCategory, Double> =
        TasteCategory.entries
            .filter { it != TasteCategory.NOISE }
            .associateWith { it.defaultWeight.toDouble() }

    // endregion

    // region 分数校准

    /** 拟合 isotonic 校准所需的最小显式评分样本数；不足时退化为温度化 logistic。 */
    const val ISOTONIC_MIN_SAMPLES: Int = 25

    /** 温度化 logistic 的温度地板 `τ_u = max(0.18, std(z_train))`（冷启动 / logistic 兜底用）。 */
    const val LOGISTIC_TAU_FLOOR: Double = 0.18

    /**
     * 校准温度地板：候选池校准下 combo 已归一化，rawZ 落在 ~[-0.3, 1] 的小尺度，
     * [LOGISTIC_TAU_FLOOR]=0.18 会过大而压平区分度；此地板仅防 `std→0` 退化，实际温度以候选池 std 为准。
     */
    const val CALIBRATION_TAU_FLOOR: Double = 0.05

    /** 分数拉开：基准分。 */
    const val SPREAD_BASE: Double = 50.0

    /** 分数拉开：最大偏移（最终分落在 [5,95]）。 */
    const val SPREAD_MAX_OFFSET: Double = 45.0

    /** 分数拉开：放大系数（>1，敢拉开）。 */
    const val SPREAD_GAIN: Double = 1.18

    /** 已评分同作偏置：`bias = 0.12·pref(r_x)`，加在校准前 raw 分上。 */
    const val RATED_BIAS_COEFF: Double = 0.12

    /** 温度化 logistic：`p = σ((z-μ)/τ)`。 */
    fun logistic(z: Double, mu: Double, tau: Double): Double {
        val t = if (tau <= 0.0) LOGISTIC_TAU_FLOOR else tau
        return 1.0 / (1.0 + exp(-(z - mu) / t))
    }

    /** 分数拉开：`50 + sign(100p-50)·min(45, 1.18·|100p-50|)`，结果四舍五入到 [5,95] 整数前的浮点。 */
    fun spread(probability: Double): Double {
        val centered = 100.0 * probability - SPREAD_BASE
        return SPREAD_BASE + sign(centered) * min(SPREAD_MAX_OFFSET, SPREAD_GAIN * abs(centered))
    }

    /** 已评分同作偏置项 `0.12·pref(r)`（保留兼容；显示锚定改用 [ratedAnchorProbability] 融合）。 */
    fun ratedBias(rating: Int?): Double = RATED_BIAS_COEFF * pref(rating)

    /**
     * 已评分作品的显示锚定权重。显式评分作为主锚，长期画像用于修正。
     * 单纯使用 `0.12·pref` 偏置不足以表达与整体画像重合较少的高评分作品，因此显示概率主要
     * 锚定到用户评分，同时保留画像修正空间。
     */
    const val RATED_ANCHOR_WEIGHT: Double = 0.88

    /**
     * 显式评分 → 锚定概率 `p_anchor`（经 [RATED_ANCHOR_WEIGHT] 融合画像 + [spread] 后近似落点：
     * 10→95 / 9→90 / 8→82 / 7→67 / 6→54 / 5→44 / 4→32 / 3→20 / 2→9 / 1→5）。
     *
     * 映射使高评分在融合后保持明确的正向锚点，同时让低评分保留足够区分度。
     * 越界或缺失评分返回 0.5，表示不使用评分锚定。
     */
    fun ratedAnchorProbability(rating: Int?): Double = when (rating) {
        10 -> 0.97
        9 -> 0.92
        8 -> 0.84
        7 -> 0.70
        6 -> 0.57
        5 -> 0.45
        4 -> 0.35
        3 -> 0.25
        2 -> 0.15
        1 -> 0.07
        else -> 0.5
    }

    /**
     * 置信度（独立输出，不混进主分）：
     * `clip(0.25 + 0.20·log(1+n)/log(51) + 0.20·cov_tag + 0.15·cov_staff + 0.10·cov_comment + 0.10·cov_time, 0, 1)`。
     */
    fun confidence(
        realSampleCount: Int,
        tagCoverage: Double,
        staffCoverage: Double,
        commentCoverage: Double,
        timeCoverage: Double,
    ): Double {
        val n = realSampleCount.coerceAtLeast(0)
        val raw = 0.25 +
            0.20 * (ln(1.0 + n) / ln(51.0)) +
            0.20 * tagCoverage.coerceIn(0.0, 1.0) +
            0.15 * staffCoverage.coerceIn(0.0, 1.0) +
            0.10 * commentCoverage.coerceIn(0.0, 1.0) +
            0.10 * timeCoverage.coerceIn(0.0, 1.0)
        return raw.coerceIn(0.0, 1.0)
    }

    // endregion

    // region 今晚看什么（文档「今晚看什么最终算法」）

    /** 今晚推荐默认最低 Bangumi 分（硬过滤）。 */
    const val TONIGHT_MIN_BANGUMI_SCORE: Double = 6.0

    /** 今晚推荐默认最低口味匹配度（硬过滤，0–100）。 */
    const val TONIGHT_MIN_TASTE_SCORE: Int = 55

    /** 时间拟合度硬过滤下限。 */
    const val TONIGHT_MIN_TIME_FIT: Double = 0.55

    /** 重复推荐冷却天数。 */
    const val TONIGHT_REPEAT_COOLDOWN_DAYS: Int = 14

    /** 单系列默认最多保留进入末轮的部数。 */
    const val TONIGHT_MAX_PER_FRANCHISE: Int = 1

    /** runtime 估计兜底（分钟 / 集 或 单部）：TV 每集、SP、OVA、剧场版。 */
    const val RUNTIME_TV_PER_EP: Int = 24
    const val RUNTIME_SP: Int = 45
    const val RUNTIME_OVA: Int = 30
    const val RUNTIME_MOVIE: Int = 90

    /** 时间拟合度 `exp(-|need-user| / max(25, 0.35·user))`。 */
    fun timeFit(needMinutes: Int, userMinutes: Int): Double {
        if (userMinutes <= 0) return 0.0
        val denom = max(25.0, 0.35 * userMinutes)
        return exp(-abs(needMinutes - userMinutes).toDouble() / denom)
    }

    /** 多样性重排 MMR 系数 λ（越大越偏相关度、越小越偏多样性）。 */
    const val MMR_LAMBDA: Double = 0.78

    /** 今晚分各项权重（`tonightRaw` 线性融合）。 */
    const val TONIGHT_W_TASTE: Double = 0.42
    const val TONIGHT_W_INTENT_TAG: Double = 0.18
    const val TONIGHT_W_TIME_FIT: Double = 0.10
    const val TONIGHT_W_COMMUNITY: Double = 0.08
    const val TONIGHT_W_NOVELTY: Double = 0.08
    const val TONIGHT_W_COMPLETION_EASE: Double = 0.07
    const val TONIGHT_W_FRESHNESS: Double = 0.07
    const val TONIGHT_W_REPEAT_PENALTY: Double = 0.20
    const val TONIGHT_W_FRANCHISE_PENALTY: Double = 0.12
    const val TONIGHT_W_EXAM_RISK: Double = 0.08

    // endregion
}

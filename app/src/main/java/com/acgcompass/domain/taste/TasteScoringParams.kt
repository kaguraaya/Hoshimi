package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 口味匹配与推荐算法的默认参数和核心公式。
 *
 * 纯 Kotlin、无 Android/IO 依赖，便于单元 / 属性测试与离线回归。所有数值均直接取自文档
 * 「默认参数、复现实验」与各层公式，集中于此以避免散落、便于统一调参与 AB 对比。算法版本号
 * [ALGORITHM_VERSION] 随重大公式/参数变动递增，用于画像缓存失效与回归判定。
 */
object TasteScoringParams {

    /**
     * 算法版本号（画像缓存键 / 回归判定用；公式或默认参数重大变动时 +1）。
     * - v3：IDF 逆频 + 中性点下移 + 口味门控社区融合 + 锚点上调。
     * - v4：社区分以站点中位为中性点拉开 + 候选证据量收缩（标签稀少的作品不被当成反口味）+
     *   画像样本上限放宽。
     * - v6：个人分歧模型（[PersonalDivergenceModel]）——残差对标签的岭回归 + 系列一致性 + 取向维度，
     *   预测评分并给出分歧归因；满意度优先用它。Bangumi15M 盲测 Spearman 0.561 → 0.607。
     * - v5：新增「预计满意度」（[satisfactionScore]，经 Bangumi15M 真实用户盲测定权），推荐排序改用它；
     *   撤掉 v4 的「按个人与社区相关度调权重」（真实数据未见增益）。
     */
    const val ALGORITHM_VERSION: Int = 6

    // region 样本权重

    /**
     * 画像真实样本上限 120：评分多的用户也能把中间分作品纳入画像（上限太小时只剩「极端分 + 最近几部」，
     * 负向 / 中性证据会缺失）。时间权重已负责近因，上限只为约束组合挖掘的内存与耗时。
     */
    const val MAX_PROFILE_SAMPLES: Int = 120

    /**
     * 单部作品参与题材组合挖掘的标签数上限（按标注强度取前 N）。三元组合数随标签数立方增长，
     * 尾部低标注标签多为噪声，截断后既控内存又让组合更聚焦主干题材。
     */
    const val COMBO_MAX_TAGS_PER_WORK: Int = 16

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

    /** 社区分映射的中性点：Bangumi 动画有排名条目的评分中位约 6.6，此分对应匹配度 50（不加不减）。 */
    const val COMMUNITY_NEUTRAL_SCORE: Double = 6.6

    /** 社区分映射的尺度：`50 + 45·tanh((score − 6.6)/1.3)`，8.5→≈90、7.5→≈77、7.0→≈64、6.0→≈30。 */
    const val COMMUNITY_SCORE_SCALE: Double = 1.3

    /**
     * 社区评分 → 匹配度量纲（5–95）。v3 用 `score·10`，结果挤在 55–85 且中位作品（6.6）也给 66 分——
     * 「画像没话说 + 口碑平平」的作品被系统性抬到「可能会喜欢」。v4 以站点中位为中性 50 并用 tanh 拉开，
     * 与画像分同一量纲（中心 50、两端饱和），单调性不变。
     */
    fun communityTargetScore(bangumiScore10: Double): Double =
        (SPREAD_BASE + SPREAD_MAX_OFFSET * tanh((bangumiScore10 - COMMUNITY_NEUTRAL_SCORE) / COMMUNITY_SCORE_SCALE))
            .coerceIn(SPREAD_BASE - SPREAD_MAX_OFFSET, SPREAD_BASE + SPREAD_MAX_OFFSET)

    /**
     * 口味门控社区融合（**显示用的口味匹配度**）：以画像分 [profileScore]（5–95）为主，按「口味立场弱 +
     * 社区票数足」的程度融入社区评分。**仅用于未评分作品**（已评分作品走 [ratedAnchorProbability] 显式锚）。
     *
     * `gate = (1 − |p−50|/50)^EXP`（口味越中性门越开）；`w_c = COMMUNITY_BLEND_MAX · gate · votesConf`；
     * `out = (1−w_c)·p + w_c·comm`，其中 `comm = `[communityTargetScore]。无社区评分时原样返回画像分。
     *
     * 门控保证「强命中 / 强反口味由口味主导」——这是匹配度的语义，但也让它不是最好的评分预测器
     * （Bangumi15M 750 位用户 5 折盲测：匹配度 Spearman≈0.49，而 [satisfactionScore] ≈0.56）。
     * 推荐排序请用后者。
     */
    fun communityBlendedScore(profileScore: Int, bangumiScore10: Double?, votes: Int?): Int {
        if (bangumiScore10 == null || bangumiScore10 <= 0.0) return profileScore
        val comm = communityTargetScore(bangumiScore10)
        val gate = Math.pow((1.0 - abs(profileScore - 50.0) / 50.0).coerceIn(0.0, 1.0), COMMUNITY_GATE_EXP)
        val wc = COMMUNITY_BLEND_MAX * gate * votesConfidence(votes)
        val out = (1.0 - wc) * profileScore + wc * comm
        return out.roundToInt().coerceIn(5, 95)
    }

    // endregion

    // region 预计满意度（v5，推荐排序用）

    /** 社区分标准化的中心与尺度（Bangumi 动画评分的站点水平）。 */
    const val SATISFACTION_COMMUNITY_STD: Double = 0.9

    /** 个人画像在满意度里的权重：评分少时 0.20，评分达到 [SATISFACTION_FULL_SAMPLES] 时 0.30。 */
    const val SATISFACTION_PERSONAL_MIN: Double = 0.20
    const val SATISFACTION_PERSONAL_MAX: Double = 0.30
    const val SATISFACTION_MIN_SAMPLES: Int = 20
    const val SATISFACTION_FULL_SAMPLES: Int = 60

    /** 社区分的贝叶斯票数收缩：`votes/(votes+30)`，只压制「十几个人打出的极端分」。 */
    const val SATISFACTION_VOTE_PRIOR: Double = 30.0

    /** 画像 z 分截断与满意度的 tanh 尺度。 */
    const val SATISFACTION_Z_CLIP: Double = 3.0
    const val SATISFACTION_Z_SCALE: Double = 1.5

    /** 没有社区评分时，只凭画像给出的满意度要保守：画像 z 分乘此系数。 */
    const val SATISFACTION_PROFILE_ONLY_GAIN: Double = 0.6

    /** 评分数 → 个人画像权重（线性插值，两端截断）。 */
    fun satisfactionPersonalWeight(sampleCount: Int): Double {
        val span = (SATISFACTION_FULL_SAMPLES - SATISFACTION_MIN_SAMPLES).toDouble()
        val t = ((sampleCount - SATISFACTION_MIN_SAMPLES) / span).coerceIn(0.0, 1.0)
        return SATISFACTION_PERSONAL_MIN + (SATISFACTION_PERSONAL_MAX - SATISFACTION_PERSONAL_MIN) * t
    }

    /**
     * **预计满意度**（5–95）：这部作品你看完大概会有多满意。回答的是「看完会不会给高分」，
     * 与「像不像你的口味」（匹配度）是两个问题——一部口碑极好的陌生题材，匹配度不高，满意度可以很高。
     *
     * `z = (1−w)·z_社区 + w·z_画像·证据量`，`score = 50 + 45·tanh(z/1.5)`：
     * - `z_社区 = (社区分 − 6.6)/0.9 · votes/(votes+30)`；
     * - `z_画像` = 画像 rawZ 相对候选池的标准分（截断到 ±3）；
     * - `w` 随评分数从 0.20 升到 0.30。
     *
     * 权重来自 Bangumi15M 抽样 750 位真实用户的 5 折盲测（每位用户的预测分 vs 真实评分 Spearman 均值）：
     * 仅社区分 0.547、仅画像 0.406、原门控融合 0.465–0.485，本公式 0.562–0.567，Top10 命中率
     * 0.67→0.71。实验中「按个人与社区相关度调权重」「残差建画像」「协同过滤向量」都没有带来
     * 超过 0.01 的增益，故不引入。
     *
     * @param profileZ 画像标准分；画像不可用时传 `null`（只用社区分）。
     */
    fun satisfactionScore(
        profileZ: Double?,
        evidence: Double,
        bangumiScore10: Double?,
        votes: Int?,
        sampleCount: Int,
    ): Int {
        val personal = profileZ?.coerceIn(-SATISFACTION_Z_CLIP, SATISFACTION_Z_CLIP)?.times(evidence.coerceIn(0.0, 1.0))
        val community = bangumiScore10?.takeIf { it > 0.0 }?.let {
            val v = (votes ?: 0).coerceAtLeast(0).toDouble()
            (it - COMMUNITY_NEUTRAL_SCORE) / SATISFACTION_COMMUNITY_STD * (v / (v + SATISFACTION_VOTE_PRIOR))
        }
        val w = satisfactionPersonalWeight(sampleCount)
        val z = when {
            personal != null && community != null -> (1.0 - w) * community + w * personal
            community != null -> (1.0 - w) * community
            personal != null -> SATISFACTION_PROFILE_ONLY_GAIN * personal
            else -> 0.0
        }
        return (SPREAD_BASE + SPREAD_MAX_OFFSET * tanh(z / SATISFACTION_Z_SCALE)).roundToInt().coerceIn(5, 95)
    }

    // endregion

    /** 预计评分 → 满意度（5–95）：`50 + 45·tanh(((r̂ − 你的均分)/你的评分标准差)/1.5)`。 */
    fun satisfactionFromRating(predictedRating: Double, userMean: Double, userStd: Double): Int {
        val z = (predictedRating - userMean) / userStd.coerceAtLeast(EPSILON)
        return (SPREAD_BASE + SPREAD_MAX_OFFSET * tanh(z / SATISFACTION_Z_SCALE)).roundToInt().coerceIn(5, 95)
    }

    // region 候选证据量（v4）

    /**
     * 候选作品拿满证据量所需的有效内容标签数（题材 / 情节 / 角色类型 / 来源 / 梗 / 年代）。
     * 取 2：只收缩「完全没有标签 / 只有一个标签」的作品，正常条目（十几个标签）不受影响。
     */
    const val EVIDENCE_FULL_TAGS: Int = 2

    /**
     * 候选证据量 ∈ [0,1]：`max(min(1, 有效内容标签数 / 2), 画像熟悉度)`。
     *
     * 校准以候选池 rawZ 中位为中性点，而**没有标签**（新番 / 冷门 / 特征未补齐）的作品 rawZ≈0，
     * 低于池中位 → 被判成二三十分的「反口味」。但「没有信息」不是「不喜欢」：按证据量把画像概率向
     * 中性 0.5 收缩，让这类作品回到中性区间、交由社区口碑门控补位，并同步下调置信度。
     *
     * 取与 [familiarity] 的较大值：哪怕只有一个标签，只要它正好是画像有明确立场的标签（比如你反复
     * 打低分的题材），这就是充分证据，不收缩。
     */
    fun candidateEvidence(contentTagCount: Int, familiarity: Double = 0.0): Double = maxOf(
        (contentTagCount.coerceAtLeast(0).toDouble() / EVIDENCE_FULL_TAGS).coerceIn(0.0, 1.0),
        familiarity.coerceIn(0.0, 1.0),
    )

    /** 按证据量把概率向中性 0.5 收缩。 */
    fun shrinkToNeutral(probability: Double, evidence: Double): Double =
        0.5 + (probability - 0.5) * evidence.coerceIn(0.0, 1.0)

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

    /**
     * 今晚分各项权重（`tonightRaw` 线性融合）。按「意图匹配 VNext」修订：当晚意图从 0.18 提到 0.32、
     * 口味从 0.42 降到 0.34——用户今晚明确说想看什么，不能被长期口味压过去。正向项合计 1.0。
     */
    const val TONIGHT_W_TASTE: Double = 0.34
    const val TONIGHT_W_INTENT_TAG: Double = 0.32
    const val TONIGHT_W_TIME_FIT: Double = 0.10
    const val TONIGHT_W_COMMUNITY: Double = 0.07
    const val TONIGHT_W_NOVELTY: Double = 0.06
    const val TONIGHT_W_COMPLETION_EASE: Double = 0.05
    const val TONIGHT_W_FRESHNESS: Double = 0.06
    const val TONIGHT_W_REPEAT_PENALTY: Double = 0.18
    const val TONIGHT_W_FRANCHISE_PENALTY: Double = 0.14
    const val TONIGHT_W_EXAM_RISK: Double = 0.12

    /** 主气质冲突惩罚：作品的主气质与所选意图相冲（选校园恋爱日常，作品主打超能力战斗）。 */
    const val TONIGHT_W_DOMINANT_CONFLICT: Double = 0.18

    // endregion
}

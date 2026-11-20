package com.acgcompass.domain.taste

import com.acgcompass.core.common.runCatchingCancellable
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 画像构建单样本输入：用户对某作品的**评分 / 短评 / 评定时间** × 该作品的 [WorkFeature]。
 * [rating] 为 null（仅想看 / 在看未评分）时 `pref=0`，不贡献正负向量、不参与组合。
 */
data class TasteSample(
    val subjectId: String,
    val rating: Int?,
    val comment: String?,
    val updatedAtMillis: Long?,
    val feature: WorkFeature,
)

/**
 * 隐式兴趣信号：没有评分、但表达了态度的行为（想看 / 在看 / 搁置 / 抛弃、探索队列的加入与暂不）。
 *
 * 它们说明的是**兴趣**（想不想看），不是看完后的满意度——Bangumi15M 盲测里它们对预测评分没有可见增益，
 * 所以不进评分预测模型；但对「像不像你会想看的东西」（口味匹配度的画像向量）是有效信息，以很小的权重计入：
 * 单条信号的强度不到一条正式评分的四分之一，不参与校准、不参与组合挖掘、不计入样本数。
 */
data class ImplicitSignal(val feature: WorkFeature, val kind: Kind) {
    enum class Kind(val pref: Double, val weight: Double) {
        WISH(pref = 0.25, weight = 0.4),
        WATCHING(pref = 0.30, weight = 0.4),
        ON_HOLD(pref = -0.15, weight = 0.4),
        DROPPED(pref = -0.50, weight = 0.5),
        EXPLORE_LIKED(pref = 0.25, weight = 0.4),
        EXPLORE_SKIPPED(pref = -0.10, weight = 0.3),
    }
}

/**
 * 由用户收藏和作品特征构建 [AdvancedTasteProfile]；实现为可单元测试的纯函数：
 *
 * 1. **样本选择**：最多 50；强制纳入「最高分(≥9) / 最低分(≤2) / 有短评」的全部作品，其余按时间近因补齐。
 * 2. **样本加权**：`w_i = w_time·w_extreme·w_comment`；偏好 `pref(r_i)`（[TasteScoringParams.pref]）。
 * 3. **逐标签累加**：`U_c(k) += w_i·pref_i·q_ik`（社区标签 q_xk 对数压缩；结构化 staff/CV/角色 q=1）。
 *    短评经轻量关键词抽取并入 [TasteCategory.COMMENT]。
 * 4. **正负拆分**：`U_c(k)>0 → U_c^+`，`<0 → U_c^-`。
 * 5. **组合挖掘**：正向作品的题材+装置标签生成 2/3 元组合，过支持度 / 正向作品数门槛后计 `strength`。
 * 6. **校准**：对训练样本求 raw `z` 分布，得温度化 logistic 的 `μ=median(z)`、`τ=max(0.18, std(z))`。
 */
class BuildTasteProfileUseCase @Inject constructor() {

    // 画像构建的主流程集中在一处，便于对照算法文档阅读。
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    operator fun invoke(
        samples: List<TasteSample>,
        now: Long,
        calibrationPool: List<WorkFeature> = emptyList(),
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        idfStrength: Double = TasteScoringParams.PROFILE_IDF_STRENGTH,
        neutralBias: Double = TasteScoringParams.PREF_NEUTRAL_BIAS,
        prefSlope: Double = TasteScoringParams.PREF_SLOPE,
        implicitSignals: List<ImplicitSignal> = emptyList(),
    ): AdvancedTasteProfile {
        val rated = samples.filter { it.rating != null }
        if (rated.isEmpty()) {
            return AdvancedTasteProfile(generatedAt = now)
        }

        val selected = selectSamples(rated)
        val referenceTime = selected.maxOfOrNull { it.updatedAtMillis ?: 0L }?.takeIf { it > 0L } ?: now
        // 评分偏好以用户均分为中性点，再下移 [neutralBias]（默认 < userMean）。
        // 这样可避免均分偏高时把用户平均线附近的作品误判为强烈反口味。
        val userMean = rated.mapNotNull { it.rating }.average()
        val neutral = userMean - neutralBias

        // 各大类 tag/name → 累加偏好值（正负混合，后续拆分）。
        val acc = HashMap<TasteCategory, HashMap<String, Double>>()
        fun accumulate(cat: TasteCategory, key: String, delta: Double) {
            if (key.isBlank() || delta == 0.0) return
            val m = acc.getOrPut(cat) { HashMap() }
            m[key] = (m[key] ?: 0.0) + delta
        }

        // 统计每个维度标签出现在多少部不同的已评分作品中，用于过滤偶然负向标签。
        val tagSupport = HashMap<TasteCategory, HashMap<String, Int>>()
        fun countSupport(cat: TasteCategory, key: String) {
            if (key.isBlank()) return
            val m = tagSupport.getOrPut(cat) { HashMap() }
            m[key] = (m[key] ?: 0) + 1
        }

        // 组合挖掘累加器。
        val comboSupport = HashMap<Set<String>, Double>()
        val comboSigned = HashMap<Set<String>, Double>()
        val comboPosWorks = HashMap<Set<String>, Int>()

        var tagCovered = 0
        var staffCovered = 0
        var commentCovered = 0
        var timeCovered = 0

        // 校准去偏：留存每样本的「特征化 + 有符号贡献」，供 calibrate 做**留一式** raw z，
        // 消除「训练样本自身贡献画像 U_c^+ → 其 z 被抬高 → μ 偏高 → 未评分作品普遍塌到十几分」的样本内偏置。
        val perSample = ArrayList<CalibSample>(selected.size)

        for (s in selected) {
            val rating = s.rating ?: continue
            val pref = TasteScoringParams.prefCentered(rating, neutral, prefSlope)
            val ageDays = ageDaysOf(referenceTime, s.updatedAtMillis)
            val w = TasteScoringParams.sampleWeight(ageDays, rating, s.comment)
            val contribution = w * pref

            val f = TasteRawScorer.featurize(s.feature, tagOverrides)
            perSample += CalibSample(s.feature, f, contribution)
            if (f.byCategory.isNotEmpty()) tagCovered++
            if (s.feature.staff.isNotEmpty()) staffCovered++
            if (!s.comment.isNullOrBlank()) commentCovered++
            if ((s.updatedAtMillis ?: 0L) > 0L) timeCovered++

            for ((cat, tagMap) in f.byCategory) {
                for ((k, q) in tagMap) {
                    accumulate(cat, k, contribution * q)
                    // 该标签在本部作品出现 → 支持度 +1（每部至多计一次，tagMap 的 key 已在作品内去重）。
                    countSupport(cat, k)
                }
            }
            // 短评关键词 → COMMENT 维度（q=1）。
            for (kw in CommentKeywords.extract(s.comment).toSet()) {
                accumulate(TasteCategory.COMMENT, kw, contribution)
                countSupport(TasteCategory.COMMENT, kw)
            }

            // 组合挖掘只看正向作品（pref>0）。
            if (pref > 0.0 && f.topicDeviceTags.size >= 2) {
                for (combo in enumerateCombos(strongestTopicDeviceTags(f))) {
                    comboSupport[combo] = (comboSupport[combo] ?: 0.0) + w
                    comboSigned[combo] = (comboSigned[combo] ?: 0.0) + contribution
                    comboPosWorks[combo] = (comboPosWorks[combo] ?: 0) + 1
                }
            }
        }

        // 隐式兴趣信号：只轻推内容维度的标签向量（已评分作品以评分为准，不重复计入）。
        val ratedIds = rated.mapTo(HashSet()) { it.subjectId }
        for (signal in implicitSignals.distinctBy { it.feature.subjectId }) {
            if (signal.feature.subjectId in ratedIds) continue
            val contribution = signal.kind.weight * signal.kind.pref
            val f = TasteRawScorer.featurize(signal.feature, tagOverrides)
            for (cat in IMPLICIT_CATEGORIES) {
                for ((k, q) in f.byCategory[cat].orEmpty()) {
                    accumulate(cat, k, contribution * q)
                    countSupport(cat, k)
                }
            }
        }

        val nSelected = selected.size
        val categories = acc.mapValues { (cat, m) -> toCategoryPreference(m, tagSupport[cat], nSelected, idfStrength) }
            .filterValues { !it.isEmpty }

        val combos = comboSupport.entries.mapNotNull { (tags, support) ->
            val signed = comboSigned[tags] ?: 0.0
            val posWorks = comboPosWorks[tags] ?: 0
            if (support < TasteScoringParams.COMBO_MIN_SUPPORT) return@mapNotNull null
            if (signed <= 0.0) return@mapNotNull null
            if (posWorks < TasteScoringParams.COMBO_MIN_POSITIVE_WORKS) return@mapNotNull null
            TopicCombo(tags, TasteScoringParams.comboStrength(signed, tags.size))
        }.sortedByDescending { it.strength }

        val coreProfile = AdvancedTasteProfile(categories = categories, combos = combos)
        val calibration = calibrate(
            perSample, acc, tagSupport, categories, coreProfile, calibrationPool, tagOverrides, nSelected, idfStrength,
        )

        val n = selected.size
        val coverage = TasteCoverage(
            tag = tagCovered.toDouble() / n,
            staff = staffCovered.toDouble() / n,
            comment = commentCovered.toDouble() / n,
            time = timeCovered.toDouble() / n,
        )

        return AdvancedTasteProfile(
            categories = categories,
            combos = combos,
            ratedSubjectScores = rated.mapNotNull { s -> s.rating?.let { s.subjectId to it } }.toMap(),
            calibration = calibration,
            coverage = coverage,
            sampleCount = n,
            userAvgRating = rated.mapNotNull { it.rating }.average(),
            divergence = PersonalDivergenceModel.fit(rated, calibrationPool, tagOverrides),
            generatedAt = now,
        )
    }

    /**
     * 样本选择：强制保留高分/低分/有短评，其余按近因补齐到 [TasteScoringParams.MAX_PROFILE_SAMPLES]。
     *
     * 强制样本自身也按近因排序、且最多占上限的 [FORCED_SAMPLE_SHARE]：否则重度用户的强制样本一旦
     * 超过上限，就会按收藏表的任意顺序截断，并把全部中间分作品挤出画像（只剩两极 → 中心化后负向证据失真）。
     */
    private fun selectSamples(rated: List<TasteSample>): List<TasteSample> {
        val max = TasteScoringParams.MAX_PROFILE_SAMPLES
        val unique = rated.distinctBy { it.subjectId }
        if (unique.size <= max) return unique
        val byRecency = compareByDescending<TasteSample> { it.updatedAtMillis ?: 0L }
        val (forcedAll, ordinaryAll) = unique.partition { s ->
            val r = s.rating ?: return@partition false
            r >= 9 || r <= 2 || !s.comment.isNullOrBlank()
        }
        val forcedSorted = forcedAll.sortedWith(byRecency)
        val ordinarySorted = ordinaryAll.sortedWith(byRecency)
        // 普通样本至少保住 (1 − FORCED_SAMPLE_SHARE) 的名额；任一侧不足时名额让给另一侧。
        val ordinaryQuota = minOf(ordinarySorted.size, max - (max * FORCED_SAMPLE_SHARE).toInt())
        val forced = forcedSorted.take(max - ordinaryQuota)
        val ordinary = ordinarySorted.take(max - forced.size)
        return forced + ordinary
    }

    /** 参与组合挖掘的题材 / 装置标签：按标注强度取前 [TasteScoringParams.COMBO_MAX_TAGS_PER_WORK]。 */
    private fun strongestTopicDeviceTags(f: TasteRawScorer.Featurized): Set<String> {
        val limit = TasteScoringParams.COMBO_MAX_TAGS_PER_WORK
        if (f.topicDeviceTags.size <= limit) return f.topicDeviceTags
        val topic = f.byCategory[TasteCategory.TOPIC].orEmpty()
        val device = f.byCategory[TasteCategory.DEVICE].orEmpty()
        return f.topicDeviceTags
            .sortedWith(compareByDescending<String> { maxOf(topic[it] ?: 0.0, device[it] ?: 0.0) }.thenBy { it })
            .take(limit)
            .toSet()
    }

    /**
     * 把有符号累加的偏好 [merged] 拆成正 / 负向量。
     *
     * 负向量需通过最小支持度过滤；标签至少在 [TasteScoringParams.MIN_NEGATIVE_SUPPORT] 部
     * 已评分作品中出现，才允许进入负向量。这样滤除「只看过 1 部、评分略低于均分」的稀疏题材（如 百合 /
     * 美食）被均分中心化偶然打成负、反而拉低匹配的问题；正向量不设门槛（正向偏爱不需要多部佐证也可信）。
     * [support] 为 null（如无支持度信息）时不过滤，保持向后兼容。
     */
    private fun toCategoryPreference(
        merged: Map<String, Double>,
        support: Map<String, Int>? = null,
        sampleCount: Int = 0,
        idfStrength: Double = 0.0,
    ): CategoryPreference {
        val positive = HashMap<String, Double>()
        val negative = HashMap<String, Double>()
        for ((k, v) in merged) {
            when {
                v > 0.0 -> {
                    // 正向标签按 IDF 逆频降权高频通用标签（df=该标签出现在多少部已评分作品）。
                    // df 缺失时按 1（当作独特标签，不降权）；idfStrength=0 时 factor 恒 1（回退旧行为）。
                    val df = support?.get(k) ?: 1
                    val idf = TasteScoringParams.idfFactor(df, sampleCount, idfStrength)
                    positive[k] = v * idf
                }
                v < 0.0 -> {
                    val supp = support?.get(k) ?: Int.MAX_VALUE
                    if (supp >= TasteScoringParams.MIN_NEGATIVE_SUPPORT) negative[k] = -v
                }
            }
        }
        return CategoryPreference(positive, negative)
    }

    /** 枚举 2 元与 3 元组合（题材+装置标签，已去重排序保证集合键稳定）。 */
    private fun enumerateCombos(tags: Set<String>): List<Set<String>> {
        val list = tags.toList()
        if (list.size < 2) return emptyList()
        val out = ArrayList<Set<String>>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                out += setOf(list[i], list[j])
                if (TasteScoringParams.COMBO_MAX_ORDER >= 3) {
                    for (k in j + 1 until list.size) out += setOf(list[i], list[j], list[k])
                }
            }
        }
        return out
    }

    /** 单样本校准输入：特征化结果 + 该样本对画像的**有符号贡献** `w·pref`（留一去偏用）。 */
    private data class CalibSample(
        val feature: WorkFeature,
        val featurized: TasteRawScorer.Featurized,
        val contribution: Double,
    )

    /**
     * 温度化 logistic 校准（**候选池校准**版）：`μ=median(z_pool)`、`τ=max(0.05, std(z_pool))`。
     *
     * 根因：`simCombo` 归一化后已消除「combo 项爆炸主导 rawZ」，但若仍用训练样本 z 定中心，
     * 训练样本因自身贡献画像（自我重合）rawZ 系统性高于外部作品，μ 偏高 → 未评分作品仍普遍偏低。
     * 改用**未评分候选池**（用户会去搜索 / 浏览的外部作品全体，取自 `work_features` 缓存）的 rawZ 分布
     * 定 μ/τ：池中位即「典型未评分作品」的中心，据此校准后未评分作品自然落在 50 分附近、按契合度拉开
     * （完美命中→90+、部分契合→中段、中性→50 下、反口味→低分）。已评分作品另走显式评分锚定，不受影响。
     * 池不足 [CALIB_MIN_POOL] 时回退训练样本留一 z（小数据兜底，无更好参照）。
     */
    private fun calibrate(
        perSample: List<CalibSample>,
        acc: Map<TasteCategory, Map<String, Double>>,
        tagSupport: Map<TasteCategory, Map<String, Int>>,
        baseCategories: Map<TasteCategory, CategoryPreference>,
        core: AdvancedTasteProfile,
        pool: List<WorkFeature>,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
        sampleCount: Int = 0,
        idfStrength: Double = 0.0,
    ): TasteCalibration {
        val poolZs = if (pool.size >= CALIB_MIN_POOL) {
            pool.mapNotNull { f ->
                runCatchingCancellable {
                    TasteRawScorer.rawScore(
                        f,
                        core,
                        tagOverrides,
                    )
                }.getOrNull()
            }
        } else {
            emptyList()
        }
        val usePool = poolZs.size >= CALIB_MIN_POOL
        val zs = when {
            usePool -> poolZs
            perSample.isNotEmpty() -> perSample.map { cs ->
                TasteRawScorer.rawScore(
                    cs.featurized,
                    cs.feature,
                    leaveOneOutProfile(cs, acc, tagSupport, baseCategories, core, sampleCount, idfStrength),
                )
            }
            else -> return TasteCalibration()
        }
        // 候选池充足时以未评分样本中位数为 μ；回退到已评分样本的 LOO 分布时使用低分位数，
        // 以补偿已评分样本的选择偏差，避免系统性压低未评分作品。
        val mu = quantile(zs, if (usePool) 0.5 else CALIB_FALLBACK_MU_QUANTILE)
        val mean = zs.average()
        val variance = if (zs.size > 1) zs.sumOf { (it - mean) * (it - mean) } / (zs.size - 1) else 0.0
        val tau = maxOf(TasteScoringParams.CALIBRATION_TAU_FLOOR, sqrt(variance))
        return TasteCalibration(mu = mu, tau = tau, isotonicPoints = null)
    }

    /**
     * 构造「扣除样本 [cs] 自身贡献」的留一画像：仅重算 [cs] 命中的单标签维度（其余维度沿用全局）。
     * 题材组合因需 ≥2 正向作品支撑、单样本影响小，近似沿用全局 combos；社区口碑项不依赖画像，无需去偏。
     */
    private fun leaveOneOutProfile(
        cs: CalibSample,
        acc: Map<TasteCategory, Map<String, Double>>,
        tagSupport: Map<TasteCategory, Map<String, Int>>,
        baseCategories: Map<TasteCategory, CategoryPreference>,
        core: AdvancedTasteProfile,
        sampleCount: Int = 0,
        idfStrength: Double = 0.0,
    ): AdvancedTasteProfile {
        if (cs.contribution == 0.0) return core
        val overridden = HashMap(baseCategories)
        for ((cat, tagMap) in cs.featurized.byCategory) {
            val accCat = acc[cat] ?: continue
            val looAccCat = HashMap(accCat)
            for ((k, q) in tagMap) {
                val newVal = (looAccCat[k] ?: 0.0) - cs.contribution * q
                if (abs(newVal) < 1e-12) looAccCat.remove(k) else looAccCat[k] = newVal
            }
            val pref = toCategoryPreference(looAccCat, tagSupport[cat], sampleCount, idfStrength)
            if (pref.isEmpty) overridden.remove(cat) else overridden[cat] = pref
        }
        return core.copy(categories = overridden)
    }

    private fun median(values: List<Double>): Double = quantile(values, 0.5)

    /** 线性插值分位数（q∈[0,1]）；空表返回 0，单元素返回该值。 */
    private fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        if (lo == hi) return sorted[lo]
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun ageDaysOf(referenceTime: Long, updatedAt: Long?): Double {
        if (updatedAt == null || updatedAt <= 0L) return 0.0
        val diffMs = (referenceTime - updatedAt).coerceAtLeast(0L)
        return diffMs.toDouble() / MILLIS_PER_DAY
    }

    private companion object {
        const val MILLIS_PER_DAY: Double = 24.0 * 3600 * 1000

        /** 候选池校准最小样本数：池 ≥ 此值才用其 rawZ 分布定 μ/τ，否则回退训练样本留一 z。 */
        const val CALIB_MIN_POOL: Int = 20

        /** 池不足回退时 μ 取已评分 LOO z 的低分位数（补偿幸存者偏差：未评分作品 rawZ 中心低于已评分作品）。 */
        const val CALIB_FALLBACK_MU_QUANTILE: Double = 0.4

        /** 隐式信号只影响这些内容维度（不碰 staff / 声优 / 角色等实体维度：兴趣信号太弱，不足以归因到人）。 */
        val IMPLICIT_CATEGORIES: List<TasteCategory> =
            listOf(TasteCategory.TOPIC, TasteCategory.DEVICE, TasteCategory.XP, TasteCategory.SOURCE)

        /** 强制样本（极端分 / 有短评）最多占画像样本上限的比例，其余名额留给按近因补齐的普通样本。 */
        const val FORCED_SAMPLE_SHARE: Double = 0.7
    }
}

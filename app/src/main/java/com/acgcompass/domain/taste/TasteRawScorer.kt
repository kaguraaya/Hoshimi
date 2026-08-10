package com.acgcompass.domain.taste

/**
 * 十二维原始相似度融合 `z(x)`；实现为无状态纯函数。
 *
 * 由 [BuildTasteProfileUseCase]（求训练样本 z 分布做校准）与 [ComputeTasteMatchUseCase]（评分候选）
 * 共用，保证「画像构建」与「评分」口径完全一致（避免半路径不一致）。不含校准 / 分数拉开 / 已评分偏置。
 */
object TasteRawScorer {

    /**
     * 作品按大类的特征化结果。
     * - [byCategory]：大类 → (清洗后标签/名 → 强度 q)；同标签取最大 q。
     * - [topicDeviceTags]：题材 + 情节装置的清洗标签集合，用于题材组合命中。
     */
    data class Featurized(
        val byCategory: Map<TasteCategory, Map<String, Double>>,
        val topicDeviceTags: Set<String>,
    )

    /**
     * 把一部作品的社区标签（带计数）+ 结构化 staff/CV/角色 特征化为各大类的 tag→q。
     *
     * [overrides]（清洗后标签 → 维度）为 AI 升维分类缓存，仅作用于本地规则兜底为题材的未知标签（见
     * [TagClassifier.classify]）。缺省空表 → 行为与之前完全一致，AI 未配置 / 未分类时全回退本地。
     */
    fun featurize(feature: WorkFeature, overrides: Map<String, TasteCategory> = emptyMap()): Featurized {
        val ctx = TagClassifier.Context.of(
            staff = feature.staff,
            characters = feature.characters,
            cv = feature.cv,
            sources = feature.sources,
            titles = feature.titleVariants,
        )
        val maxCount = feature.maxTagCount
        val byCat = HashMap<TasteCategory, HashMap<String, Double>>()
        val topicDevice = HashSet<String>()

        fun add(cat: TasteCategory, key: String, q: Double) {
            if (key.isBlank() || q <= 0.0) return
            val m = byCat.getOrPut(cat) { HashMap() }
            m[key] = maxOf(m[key] ?: 0.0, q)
        }

        for (tc in feature.tagCounts) {
            val cleaned = TagClassifier.clean(tc.name)
            if (cleaned.isEmpty()) continue
            val cat = TagClassifier.classify(tc.name, ctx, overrides)
            if (cat == TasteCategory.NOISE) continue
            val q = TasteScoringParams.tagStrength(tc.count, maxCount)
            // 时间标签归入 5 年区间，使同年代不同季度聚合为年代偏好。
            val key = if (cat == TasteCategory.TIME) TagClassifier.timeBucket(cleaned) else cleaned
            add(cat, key, q)
            if (cat == TasteCategory.TOPIC || cat == TasteCategory.DEVICE) topicDevice += key
        }
        // 结构化真实名按「出现即满强度」并入对应维度（无标注人数，q=1.0）。
        for (s in feature.staff) add(TasteCategory.STAFF, TagClassifier.clean(s), 1.0)
        for (c in feature.cv) add(TasteCategory.CV, TagClassifier.clean(c), 1.0)
        for (ch in feature.characters) add(TasteCategory.CHARACTER, TagClassifier.clean(ch), 1.0)
        for (source in feature.sources) add(TasteCategory.SOURCE, TagClassifier.clean(source), 1.0)

        // 实体维度（STAFF/CV/CHARACTER）为身份权威；若同一名称也被社区标签误分进内容维度
        // （TOPIC/DEVICE/XP/MEME/SOURCE/TIME），从内容维度剔除，消除「同名既在题材又在制作阵容」的双重
        // 归类与重复计分。以结构化名为准（它们来自作品真实 staff/角色/CV 字段，比社区标签可信）。
        dedupeEntityNamesFromContent(byCat, topicDevice)

        return Featurized(byCat.mapValues { it.value.toMap() }, topicDevice)
    }

    /** 参与实体名称去重的内容维度。 */
    private val CONTENT_CATEGORIES: List<TasteCategory> = listOf(
        TasteCategory.TOPIC,
        TasteCategory.DEVICE,
        TasteCategory.XP,
        TasteCategory.MEME,
        TasteCategory.SOURCE,
        TasteCategory.TIME,
    )

    /**
     * 以结构化实体维度为权威，从内容维度剔除同名键，消除双重归类。
     * 同步把被剔除的键移出 [topicDevice]，避免人名混入题材组合命中。就地修改 [byCat] / [topicDevice]。
     */
    private fun dedupeEntityNamesFromContent(
        byCat: HashMap<TasteCategory, HashMap<String, Double>>,
        topicDevice: HashSet<String>,
    ) {
        val entityNames = buildSet {
            addAll(byCat[TasteCategory.STAFF]?.keys.orEmpty())
            addAll(byCat[TasteCategory.CV]?.keys.orEmpty())
            addAll(byCat[TasteCategory.CHARACTER]?.keys.orEmpty())
        }
        if (entityNames.isEmpty()) return
        for (cat in CONTENT_CATEGORIES) {
            val m = byCat[cat] ?: continue
            for (name in entityNames) {
                if (m.remove(name) != null && (cat == TasteCategory.TOPIC || cat == TasteCategory.DEVICE)) {
                    topicDevice -= name
                }
            }
            if (m.isEmpty()) byCat.remove(cat)
        }
    }

    /**
     * 实体 / 创作者维度：**只做加分**（文档：声优「只做加分项，不做强召回」、角色实体「仅对同类角色偏好
     * 生效」、staff/创作者偏好为个性化增益）。这些名字**稀疏**（每个通常只出现在 1–2 部作品），偶然落在
     * 用户均分下方产生的「负向证据」并不可信——一个声优配过几十部作品、一个角色只属于一部番，用「这个
     * 声优 / 角色出现在你评分略低于均分的作品里」来**扣分**是纯噪声，且会给出「−声优·諸星すみれ」
     * 「−角色·逢坂大河」这类反直觉理由。故这些维度评分时不参与负向惩罚（λ=0），只在命中正向偏好时加分。
     */
    val POSITIVE_ONLY_CATEGORIES: Set<TasteCategory> = setOf(
        TasteCategory.STAFF,
        TasteCategory.CV,
        TasteCategory.CHARACTER,
    )

    /** 该大类评分实际使用的负向惩罚系数：实体/创作者维度（[POSITIVE_ONLY_CATEGORIES]）恒 0，其余用传入 λ。 */
    fun lambdaFor(cat: TasteCategory, lambda: Double): Double = if (cat in POSITIVE_ONLY_CATEGORIES) 0.0 else lambda

    /** 单大类相似度 `sim_c = Σ q·U^+ /(||U^+||+ε) - λ·Σ q·U^- /(||U^-||+ε)`。 */
    fun simCategory(catTags: Map<String, Double>, pref: CategoryPreference, lambda: Double): Double {
        if (catTags.isEmpty() || pref.isEmpty) return 0.0
        var pos = 0.0
        var neg = 0.0
        for ((k, q) in catTags) {
            pref.positive[k]?.let { pos += q * it }
            pref.negative[k]?.let { neg += q * it }
        }
        val posPart = pos / (pref.positiveL1 + TasteScoringParams.EPSILON)
        val negPart = neg / (pref.negativeL1 + TasteScoringParams.EPSILON)
        return posPart - lambda * negPart
    }

    /**
     * 题材组合命中分（归一化）：`sim_combo = Σ_{命中} strength / (Σ_{全部} strength + ε) ∈ [0,1]`。
     *
     * `strength=signed_support/|C|^0.3` 会随样本规模增长，因此组合项必须归一化为命中偏好的比例，
     * 与单标签 `posPart` 保持同一量级，避免组合项主导最终分数。
     */
    fun simCombo(topicDeviceTags: Set<String>, combos: List<TopicCombo>): Double {
        if (topicDeviceTags.isEmpty() || combos.isEmpty()) return 0.0
        var hit = 0.0
        var total = 0.0
        for (combo in combos) {
            if (combo.tags.isEmpty()) continue
            total += combo.strength
            if (topicDeviceTags.containsAll(combo.tags)) hit += combo.strength
        }
        return if (total > 0.0) hit / (total + TasteScoringParams.EPSILON) else 0.0
    }

    /**
     * 十二维线性融合 raw `z(x)`（候选作品对画像的综合相似度，未校准）。
     * 候选无用户短评，故 [TasteCategory.COMMENT] 项恒 0（评论语义只在画像侧建模）。
     */
    fun rawScore(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        overrides: Map<String, TasteCategory> = emptyMap(),
    ): Double = rawScore(featurize(feature, overrides), feature, profile)

    /** 复用已 featurize 的结果计算 raw z（批量评分时省一次 featurize）。 */
    fun rawScore(f: Featurized, feature: WorkFeature, profile: AdvancedTasteProfile): Double {
        var z = 0.0
        val lambda = TasteScoringParams.LAMBDA_NEGATIVE
        for (cat in TasteCategory.entries) {
            when (cat) {
                TasteCategory.COMBO ->
                    z += cat.defaultWeight * simCombo(f.topicDeviceTags, profile.combos)
                TasteCategory.COMMUNITY ->
                    z += cat.defaultWeight * TasteScoringParams.communityGuidance(
                        feature.bangumiScore?.toDouble(),
                        feature.bangumiVotes,
                    )
                TasteCategory.COMMENT, TasteCategory.NOISE -> Unit
                else -> {
                    val catTags = f.byCategory[cat] ?: continue
                    z += cat.defaultWeight * simCategory(catTags, profile.category(cat), lambdaFor(cat, lambda))
                }
            }
        }
        return z
    }
}

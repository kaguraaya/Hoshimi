package com.acgcompass.feature.detail

import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteVerdict
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import com.acgcompass.domain.usecase.PersonalTasteScorer
import kotlin.math.roundToInt

/** 决策区：综合口味匹配、社区共识与补完成本派生推荐 / 不推荐理由与适合心情。 */
internal fun Work.toDecisionUiModel(
    consensus: ConsensusUiModel,
    tasteMatch: TasteMatchUiModel,
    completionCost: CompletionCostUiModel,
    radar: SpoilerRadarResult?,
): DecisionUiModel {
    val recommend = buildList {
        if (tasteMatch is TasteMatchUiModel.Available && tasteMatch.fraction >= 0.66f) {
            add("与口味画像高度匹配")
        }
        if (consensus is ConsensusUiModel.Available && consensus.priority.fraction >= 0.66f) {
            add("社区补番优先级较高")
        }
        if (completionCost.available && completionCost.bucketLabel == "今晚可看完") {
            add("篇幅短，今晚即可看完")
        }
        // 社区共识较稳 → 口碑较稳定（最小数据兜底）。
        if (consensus is ConsensusUiModel.Available && consensus.stability.fraction >= 0.6f) {
            add("社区评分较稳定，口碑分歧较小")
        }
    }.distinct().ifEmpty {
        // 完全无上述信号时，用作品题材标签给出可参考的推荐线索（不伪造结论）。
        val genreTags = tags
            .filter { it.category == TagCategory.CONTENT || it.category == TagCategory.MOOD }
            .map(Tag::name).distinct().take(3)
        if (genreTags.isNotEmpty()) {
            listOf("题材偏向：${genreTags.joinToString("、")}，可据此判断是否合口味")
        } else {
            emptyList()
        }
    }
    val notRecommend = buildList {
        if (tasteMatch is TasteMatchUiModel.Available && tasteMatch.fraction < 0.33f) {
            add("与口味画像匹配度较低")
        }
        if (consensus is ConsensusUiModel.Available && consensus.controversy.fraction >= 0.66f) {
            add("社区争议较大，口碑分歧明显")
        }
        if (completionCost.available && completionCost.bucketLabel == "长期坑") {
            add("体量较大，需要长期投入")
        }
        // 情绪风险标签 → 需注意；雷达争议/雷点补充。
        tags.filter { it.category == TagCategory.RISK }.map(Tag::name).take(2)
            .forEach { add("注意：$it") }
    }.distinct()
    // 评论里的好评点、争议点和需留意的地方只在「评论摘要」里完整列一次（同一份无剧透分析），这里只指过去，
    // 避免两处各摘几条、看起来说法不一致。
    val radarPoints = radar?.output
    val hasPros = radarPoints?.pros.orEmpty().any { it.isNotBlank() && !it.startsWith("（") }
    val hasCons = (radarPoints?.controversies.orEmpty() + radarPoints?.pitfalls.orEmpty())
        .any { it.isNotBlank() && !it.startsWith("（") }
    val recommendWithPointer = if (hasPros) recommend + REVIEW_PROS_POINTER else recommend
    val notRecommendWithPointer = if (hasCons) notRecommend + REVIEW_CONS_POINTER else notRecommend
    // 适合心情优先使用 MOOD 标签；缺失时从内容标签推断，仍为空则展示高频社区标签。
    val moodTags = tags.filter { it.category == TagCategory.MOOD }.map(Tag::name)
    val moods = (moodTags + inferMoodsFromTags(tags)).distinct().ifEmpty {
        tags.filter { it.category == TagCategory.CONTENT }.map(Tag::name).distinct().take(6)
    }
    val (priorityLevel, priorityReason) = computeBacklogPriority(tasteMatch, consensus, completionCost, tags)
    return DecisionUiModel(
        tasteMatch = tasteMatch,
        recommendReasons = recommendWithPointer,
        notRecommendReasons = notRecommendWithPointer,
        // 无剧透评价雷达：已生成时展示无剧透摘要、生成方式、剧透等级与来源标注；
        // 未生成（如配置 AI 但成本未确认）时仍标记「暂无数据」，绝不伪造。
        spoilerRadarSummary = radar.toRadarSummary(),
        suitableMoods = moods,
        completionCostText = if (completionCost.available) {
            "${completionCost.bucketLabel} · ${completionCost.detailText}"
        } else {
            NO_DATA
        },
        spoilerRadarGenerator = radar?.let {
            if (it.generator == AiGenerator.AI) "AI 总结" else "规则生成"
        },
        spoilerRadarLevel = radar?.effectiveLevel?.label,
        spoilerRadarSources = radar?.summarySources?.map { it.label }.orEmpty(),
        spoilerRadarConfidenceText = radar?.let {
            "置信度 ${(it.confidence * 100).roundToInt()}%"
        },
        priorityLevel = priorityLevel,
        priorityReason = priorityReason,
    )
}

/**
 * 补番优先级模型（可解释）。以「预计评分在你自己看过的作品里的位置」为主，再看口味匹配、社区共识、
 * 补完成本与情绪风险标签，输出 低/中/高 + 理由。
 * 理由跟着等级走：高只列拉高的因素，低先列拉低的因素（有明显的加分项时写成「……，但……」），
 * 中两边都写。无任何有效信号（无口味、无社区共识）时返回 (null, null)，不伪造。
 */
internal fun computeBacklogPriority(
    tasteMatch: TasteMatchUiModel,
    consensus: ConsensusUiModel,
    completionCost: CompletionCostUiModel,
    tags: List<Tag>,
): Pair<String?, String?> {
    val factors = priorityFactors(tasteMatch, consensus, completionCost)
    val weight = factors.sumOf { it.weight.toDouble() }.toFloat()
    if (weight <= 0f) return null to null
    var score = (factors.sumOf { (it.value * it.weight).toDouble() }.toFloat() / weight).coerceIn(0f, 1f)
    // 情绪风险标签作为减项（致郁/压抑/高上头等），不归零，仅下调。
    val riskTags = tags.filter { it.category == TagCategory.RISK }.map(Tag::name)
    val risk = riskTags.takeIf { it.isNotEmpty() }?.let { "注意情绪风险（${it.take(2).joinToString("、")}）" }
    if (risk != null) score = (score - RISK_PENALTY).coerceIn(0f, 1f)
    val uncertain = (tasteMatch as? TasteMatchUiModel.Available)?.uncertain == true
    val level = when {
        score >= HIGH_PRIORITY && !uncertain -> "高"
        score >= MID_PRIORITY -> "中"
        else -> "低"
    }
    val note = "预计评分把握不大".takeIf { uncertain && score >= HIGH_PRIORITY }
    return level to priorityReason(level, factors, listOfNotNull(risk, note).joinToString("，").ifEmpty { null })
}

/** 一项优先级因素：[value] ∈ [0,1]，高于 0.5 往上拉、低于 0.5 往下拉。 */
internal class PriorityFactor(val value: Float, val weight: Float, val up: String, val down: String) {
    val pullsUp: Boolean get() = value >= FACTOR_STRONG_UP
    val pullsDown: Boolean get() = value <= FACTOR_STRONG_DOWN
}

internal fun priorityFactors(
    tasteMatch: TasteMatchUiModel,
    consensus: ConsensusUiModel,
    completionCost: CompletionCostUiModel,
): List<PriorityFactor> {
    val factors = mutableListOf<PriorityFactor>()
    val rank = (tasteMatch as? TasteMatchUiModel.Available)?.rankPercent
    if (rank != null) {
        // 有评分模型时口味只算一次：预计评分在你看过的作品里的位置（匹配度和它来自同一个预测，不重复计入）。
        // 社区评分已经包含在预计评分里，这里不单独加分。
        factors += PriorityFactor(
            rank / 100f,
            W_TASTE + W_RANK,
            "预计评分在你看过的作品里偏高",
            "预计评分在你看过的作品里偏低",
        )
    } else {
        if (tasteMatch is TasteMatchUiModel.Available) {
            // 没有评分模型：用画像匹配分，50% 以下视为不加分，90% 视为满分；社区评价补位。
            val taste = ((tasteMatch.fraction - TASTE_FLOOR) / TASTE_SPAN).coerceIn(0f, 1f)
            factors += PriorityFactor(taste, W_TASTE, "口味匹配较高", "口味匹配一般")
        }
        if (consensus is ConsensusUiModel.Available) {
            factors += PriorityFactor(consensus.priority.fraction, W_COMMUNITY, "社区评价较高", "社区评价一般")
        }
    }
    if (completionCost.available) {
        val costScore = when (completionCost.bucketLabel) {
            "今晚可看完" -> 1f
            "周末补完" -> 0.6f
            else -> 0.25f
        }
        factors += PriorityFactor(costScore, W_COST, "篇幅短易补完", "体量较大需长期投入")
    }
    return factors
}

internal fun priorityReason(level: String, factors: List<PriorityFactor>, risk: String?): String {
    val ups = factors.filter { it.pullsUp }.sortedByDescending { it.weight }.map { it.up }
    val downs = factors.filter { it.pullsDown }.sortedByDescending { it.weight }.map { it.down } +
        listOfNotNull(risk)
    val text = when (level) {
        "高" -> ups.joinToString(" · ")
        "低" -> when {
            downs.isEmpty() -> ""
            ups.isEmpty() -> downs.joinToString(" · ")
            else -> "${ups.first()}，但${downs.joinToString("、")}"
        }
        else -> when {
            ups.isNotEmpty() && downs.isNotEmpty() -> "${ups.joinToString("、")}，但${downs.joinToString("、")}"
            else -> (ups + downs).joinToString(" · ")
        }
    }
    return text.ifEmpty { "各项信号都在中间，仅供参考" }
}

internal const val TASTE_FLOOR = 0.5f

internal const val TASTE_SPAN = 0.4f

internal const val W_RANK = 0.4f

internal const val W_TASTE = 0.25f

internal const val W_COMMUNITY = 0.2f

internal const val W_COST = 0.15f

internal const val HIGH_PRIORITY = 0.7f

internal const val MID_PRIORITY = 0.45f

internal const val RISK_PENALTY = 0.1f

internal const val FACTOR_STRONG_UP = 0.6f

internal const val FACTOR_STRONG_DOWN = 0.4f

/**
 * 从作品内容标签按关键词推断「适合心情」。匹配不到任何关键词时返回空（不伪造）。
 * 关键词 → 心情标签的映射覆盖常见题材/情绪信号。
 */
internal fun inferMoodsFromTags(tags: List<Tag>): List<String> {
    val names = tags.map { it.name }
    fun hit(keys: List<String>): Boolean = names.any { n -> keys.any { n.contains(it) } }
    val result = linkedSetOf<String>()

    // 强情绪信号（催泪/致郁）优先级最高，先判定，避免被「日常/校园」等中性题材稀释
    // （如 clannad after story 同时含「日常」与「催泪」，应识别为催泪而非日常番）。
    val strongEmotion = hit(listOf("催泪", "感动", "致郁", "虐", "悲剧", "眼泪", "感人"))
    if (strongEmotion) result += "催泪/致郁"

    // 其余题材/情绪信号（顺序即展示优先级）。
    val rules = listOf(
        listOf("热血", "战斗", "运动", "燃") to "热血",
        listOf("恋爱", "爱情", "纯爱", "百合", "后宫") to "恋爱",
        listOf("搞笑", "喜剧", "欢乐", "吐槽") to "搞笑",
        listOf("悬疑", "推理", "烧脑", "悬念", "惊悚", "恐怖") to "悬疑/烧脑",
        listOf("科幻", "未来", "机战", "机甲") to "科幻",
        listOf("奇幻", "魔法", "异世界", "冒险") to "奇幻冒险",
        listOf("音乐", "乐队", "偶像") to "音乐",
        listOf("催眠", "电波", "意识流", "实验") to "电波",
    )
    for ((keys, mood) in rules) {
        if (hit(keys)) result += mood
    }

    // 「治愈/放松」：含明确治愈信号即可；但若仅由「日常/轻松」触发且已判定强情绪，则不加入
    // 强情绪标签优先于放松类标签，避免将催泪作品归为日常放松向。
    val explicitHealing = hit(listOf("治愈", "温馨"))
    val casualOnly = hit(listOf("日常", "轻松"))
    if (explicitHealing || (casualOnly && !strongEmotion)) result += "治愈/放松"

    return result.toList()
}

/** 把雷达结果折叠为一句话无剧透摘要；空结果或空文本回退「暂无数据」（不伪造）。 */
internal fun SpoilerRadarResult?.toRadarSummary(): String {
    if (this == null) return NO_DATA
    val impression = output.overallImpression.trim()
    val timing = output.watchTiming.trim()
    return when {
        impression.isNotEmpty() && timing.isNotEmpty() -> "$impression（$timing）"
        impression.isNotEmpty() -> impression
        timing.isNotEmpty() -> timing
        else -> NO_DATA
    }
}

/** 决策助手里指向「评论摘要」的一行：完整的好评点 / 争议点只在那里列一次。 */
internal const val REVIEW_PROS_POINTER = "评论里的好评点见下方「评论摘要」"
internal const val REVIEW_CONS_POINTER = "争议点和需留意的地方见下方「评论摘要」"

/** 解释里列出的主要依据条数。 */
private const val MAIN_BASIS_COUNT = 2

/** 文件级单例：个性化口味评分器（无状态、纯函数）。详情页与「今晚看什么」推荐共用同一打分口径。 */
internal val personalTasteScorer = PersonalTasteScorer()

/**
 * 将 12 维口味引擎结果映射为详情页模型。分数为 5–95 的校准匹配度；定性按分段，理由取贡献最大的
 * 几个大类标签；低置信（样本少）与已评分时补充说明（绝不伪造）。
 */
internal fun TasteMatchResult.toTasteMatchUi(): TasteMatchUiModel {
    // 已评分作品优先显示回测匹配度（建画像时不知道这部作品），回测还没算完时先显示当前画像下的分。
    val shown = backtest?.match?.takeIf { isRated } ?: score
    val fraction = (shown / 100f).coerceIn(0f, 1f)
    val predicted = (if (isRated) backtest?.predicted else prediction?.rating)
        ?: TasteVerdict.PASS_LINE.takeIf { isRated && fromRatingModel }
    val qualitative = TasteVerdict.of(shown, predicted, uncertain = !isRated && prediction?.isUncertain == true)
    val lowConf = confidence < 0.4
    // 逐条呈现分维度贡献（含声优 / staff / 来源等，不再只压成一行），正/负向分色；取贡献绝对值最大的前几项。
    val reasonUis = reasons
        .sortedByDescending { kotlin.math.abs(it.contribution) }
        .take(4)
        .map { TasteMatchReasonUi(text = it.label, positive = it.contribution >= 0.0) }
    val reasonCore = matchReasonCore(predicted, reasonUis)
    // 置信度独立成文案（不再塞进理由长句）。
    val confidenceText = when {
        confidence < 0.4 -> "置信度偏低（样本较少，仅供参考）"
        confidence < 0.7 -> "置信度中等"
        else -> "置信度较高"
    }
    return TasteMatchUiModel.Available(
        percentText = "$shown%",
        qualitativeText = if (lowConf) "$qualitative（低置信）" else qualitative,
        fraction = fraction,
        reason = reasonCore,
        reasons = reasonUis,
        confidenceText = confidenceText,
        // 已评分作品不展示预测块（你已经给出了答案），回测值单独一行。
        prediction = prediction?.takeIf { !isRated }?.toUi(),
        rankPercent = satisfaction.takeIf { fromRatingModel },
        uncertain = !isRated && prediction?.isUncertain == true,
        backtestText = if (isRated && fromRatingModel) backtestLine() else null,
    )
}

/** 圈子分达到这个值就说「这部是你常看的那类」。 */
private const val CIRCLE_CORE = 1.0

/** 圈子分的一句话：是不是你常看的那类作品。 */
private val TasteMatchResult.circleText: String
    get() = when {
        circleZ == null -> ""
        circleZ >= CIRCLE_CORE -> "（这部是）"
        circleZ >= 0.0 -> "（这部和你常看的有些重合）"
        else -> "（这部不太是）"
    }

/**
 * 匹配度的说明文字：说清匹配度看什么、和预计评分是两回事；按人权重的模型再说预计评分、区间和主要依据；
 * 旧公式说类比与题材；没有评分模型时说画像。
 */
private fun TasteMatchResult.matchReasonCore(predicted: Double?, reasonUis: List<TasteMatchReasonUi>): String {
    val gated = prediction?.takeIf { it.low != null && it.high != null && !isRated }
    val tail = "共参考你评过分的 $modelSamples 部作品。" + TasteVerdict.familiarityNote(tasteSimilarity) + "（下方是题材依据）"
    return when {
        gated != null -> {
            val analogyText = analogy?.let {
                "你给题材相近的 ${it.count} 部作品平均打 ${"%.1f".format(it.meanRating)} 分。"
            }.orEmpty()
            val mainBasis = gated.factors.take(MAIN_BASIS_COUNT)
                .joinToString("、") { "${it.label} ${"%+.1f".format(it.delta)}" }
            val basisText =
                if (mainBasis.isNotEmpty()) "大众基线 ${"%.1f".format(gated.baseline)} 分，主要依据：$mainBasis。" else ""
            "匹配度看的是这部对你口味有多契合：是不是你常看的那类$circleText、题材相近的作品你打了几分、题材像不像。" +
                "它和下面的预计评分是两回事——预计评分是你看完大概会打几分。" + analogyText +
                gapNote(gated.rating, gated.userMean) + basisText + tail
        }
        isRated && fromRatingModel ->
            "匹配度看的是这部对你口味有多契合：是不是你常看的那类$circleText、题材相近的作品你打了几分、题材像不像。$tail"
        predicted != null -> {
            // 把百分数的含义说出来：以相似作品类比为主，再参考评分和题材相似度。
            val basis = if (isRated) "你的评分" else "预计评分"
            val analogyText = analogy?.let {
                "你给题材相近的 ${it.count} 部作品平均打 ${"%.1f".format(it.meanRating)} 分；"
            }.orEmpty()
            analogyText + "主要看相近作品你打了几分，也参考$basis（高于你看过的 $satisfaction% 作品）和题材相似度；" + tail
        }
        reasonUis.any { it.positive } -> "综合你的长期口味给出（下方为主要依据）"
        reasonUis.isNotEmpty() -> "主要是这些维度拉低了匹配（下方）"
        else -> "与你的口味画像重合较少，仅供参考"
    }
}

/**
 * 口味匹配度：委托共享 [PersonalTasteScorer]，与推荐器同一口径。
 * 以作品标签与口味画像高/低分标签的**加权重合**为主导（题材标签全权重、年份/厂商等元数据弱化），
 * 社区评分仅作轻量先验。无画像或数据不足时返回 [TasteMatchUiModel.Unavailable]。
 */
internal fun Work.toTasteMatch(
    advancedMatch: TasteMatchResult?,
    advancedProfile: AdvancedTasteProfile?,
): TasteMatchUiModel {
    // 12 维引擎为详情页主路径（已校准 + 分数拉开 + 已评分偏置 + 全维度标签）。
    advancedMatch?.let { return it.toTasteMatchUi() }
    // 引擎无结果时按四态区分原因（弃用旧版只看题材的回退，不再笼统「暂无可匹配标签」）：
    return when {
        advancedProfile == null ->
            TasteMatchUiModel.Unavailable("口味画像尚未就绪：请在「我的 → 口味画像」从 Bangumi 同步评分，或稍候自动构建")
        !advancedProfile.isUsable ->
            TasteMatchUiModel.Unavailable("口味样本不足：多给几部作品评分后，匹配会更准")
        tags.isEmpty() ->
            TasteMatchUiModel.Unavailable("该作品暂无可用标签（已排除噪声），暂无法计算口味匹配")
        else ->
            TasteMatchUiModel.Unavailable("正在获取这部作品的标签，几秒后会自动出分")
    }
}

/** 取各源有效评分归一化到 10 分制后的平均值；无任一有效源返回 null。 */
internal fun RatingAggregate?.representativeScore10(): Float? {
    val perSource = this?.perSource ?: return null
    val normalized = perSource.mapNotNull { (source, entry) ->
        if (entry == null || !AggregateRatingsUseCase.isValidScore(source, entry.score)) {
            null
        } else {
            when (source) {
                SourceId.BANGUMI, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL -> entry.score
                SourceId.ANILIST, SourceId.VNDB -> entry.score / 10f
            }
        }
    }
    return normalized.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

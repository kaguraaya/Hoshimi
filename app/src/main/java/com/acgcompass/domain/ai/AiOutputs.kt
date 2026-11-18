package com.acgcompass.domain.ai

import kotlinx.serialization.Serializable

/**
 * AI 任务的固定输出 schema。
 *
 * 设计要点：
 * - 所有字段均给出**安全默认值**（空串 / 空列表 / `0f`），使 [com.acgcompass.data.remote.ai] 的
 *   `Json`（`ignoreUnknownKeys` + `coerceInputValues`）即便面对低质量模型的残缺输出也能完成反序列化、
 *   不崩溃。「必需字段是否齐全」由 `AiEngine` 基于 JSON 键存在性单独校验，缺失即触发
 *   「修复成指定格式」二次请求。
 * - [confidence] 为 `0f..1f` 的置信度；模型无法判断时应输出低置信而非编造。
 * - 文本字段在 `AiEngine` 的剧透过滤后处理（post-pass）中会被净化，任何被禁用的剧透 token 都会被
 *   抽象化（支撑 ）。
 */

/**
 * 防剧透雷达输出（Spoiler_Radar）。
 *
 * 维度固定：总体印象、优点、争议、雷点、适合人群、不适合人群、观看时机、置信度、数据来源。
 * 仅总结风格 / 节奏 / 争议 / 雷点，**绝不**泄露关键剧情 / 角色结局 / 反转 / 真相。
 */
@Serializable
data class SpoilerRadarOutput(
    val overallImpression: String = "",
    val pros: List<String> = emptyList(),
    val controversies: List<String> = emptyList(),
    val pitfalls: List<String> = emptyList(),
    val suitableFor: List<String> = emptyList(),
    val notSuitableFor: List<String> = emptyList(),
    val watchTiming: String = "",
    val confidence: Float = 0f,
    val sources: List<String> = emptyList(),
)

/**
 * 口味画像输出（Taste_Profile）。
 *
 * 样本不足时应给出低 [confidence] 并由提示词约束措辞采用「可能 / 倾向于」。
 */
@Serializable
data class TasteProfileOutput(
    val highScoreTags: List<String> = emptyList(),
    val lowScoreTags: List<String> = emptyList(),
    val commonReviewWords: List<String> = emptyList(),
    val droppedTypes: List<String> = emptyList(),
    val scoringHabit: ScoringHabit = ScoringHabit(),
    val titles: List<String> = emptyList(),
    val confidence: Float = 0f,
)

/**
 * 评分习惯子结构（[TasteProfileOutput.scoringHabit]）。
 *
 * @property strictness      评分严格度的定性描述（如「偏严格 / 中庸 / 宽松」）。
 * @property averageScore    平均分（缺失为 `0f`）。
 * @property highScoreRarity 高分稀有度的定性描述。
 * @property commonScoreBand 常见分段（如「7-8 分」）。
 */
@Serializable
data class ScoringHabit(
    val strictness: String = "",
    val averageScore: Float = 0f,
    val highScoreRarity: String = "",
    val commonScoreBand: String = "",
)

/**
 * 今晚推荐输出（Recommender）。
 *
 * 常规模式给出三类推荐：[safe]（稳妥）/ [gamble]（探索）/ [wildcard]（随机）。
 * 单一推荐模式仅给出唯一 [pick] 与明确理由。两种形态共用本结构，未使用的位置为 `null`；
 * 究竟需要哪些字段由 `AiEngine` 依据任务变体校验。所有推荐均带可解释 [Pick.reason]。
 */
@Serializable
data class TonightRecommenderOutput(
    val safe: Pick? = null,
    val gamble: Pick? = null,
    val wildcard: Pick? = null,
    val pick: Pick? = null,
    val confidence: Float = 0f,
)

/**
 * 单条推荐（[TonightRecommenderOutput] 的元素）。
 *
 * @property workId 推荐作品的规范化 id。
 * @property reason 推荐理由（可解释，非纯随机）。
 */
@Serializable
data class Pick(
    val workId: String = "",
    val reason: String = "",
)

/**
 * AI 口味匹配输出。
 *
 * @property matchScore 匹配度 0–100（模型给出；无法判断时给较低值并降低 [confidence]，不编造）。
 * @property likedReasons 可能喜欢的理由（基于标签/类型/评分/简介，非剧透）。
 * @property riskReasons 可能不喜欢/需注意的理由。
 * @property confidence 置信度 0–1。
 */
@Serializable
data class TasteMatchOutput(
    val matchScore: Int = 0,
    val likedReasons: List<String> = emptyList(),
    val riskReasons: List<String> = emptyList(),
    val confidence: Float = 0f,
    /** 预计用户会打的分（1–10，一位小数）；0 = 模型未给出（旧缓存 / 不支持）。 */
    val predictedRating: Float = 0f,
    /** 与大众评分相比会更高 / 更低的一句话原因；没有明显分歧时为空串。 */
    val divergenceReason: String = "",
    /** 从用户的分歧评分与短评里归纳出的「打分最看重的角度」（2–4 个短语）。 */
    val ratingAngles: List<String> = emptyList(),
)

@Serializable
data class RouteMapOutput(
    val nodes: List<RouteNodeOutput> = emptyList(),
    val confidence: Float = 0f,
    val routeConfirmed: Boolean = false,
)

/**
 * 路线节点（[RouteMapOutput] 的元素）。
 *
 * @property workId         节点作品 id。
 * @property relationType   关联类型（续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇等的原始描述）。
 * @property recommendation 观看建议；取值见 [RouteRecommendation]，以字符串承载以容忍模型措辞差异。
 * @property orderIndex     建议观看顺序序号；路线未确认时该值不具约束力（不应被当作权威顺序）。
 */
@Serializable
data class RouteNodeOutput(
    val workId: String = "",
    val relationType: String = "",
    val recommendation: String = RouteRecommendation.OPTIONAL.name,
    val orderIndex: Int = 0,
)

/**
 * 标签升维分类输出：把一批社区标签分别归入口味维度。
 *
 * 仅对本地规则「其余视为题材」兜底的**未知标签**做补充分类，把它们从笼统的 `topic` 细化到 `device`/`xp`/
 * `meme`/`source`/`time`/`noise` 等更精确维度；已被本地词典 / 交叉验证命中的标签不受影响（AI 不可用时全回退本地）。
 *
 * @property items       每个输入标签 → 维度 key 的分类结果。
 * @property confidence  整体置信度 0–1（模型无法判断时给低值，不编造）。
 */
@Serializable
data class TagClassifyOutput(
    val items: List<TagDimensionAssignment> = emptyList(),
    val confidence: Float = 0f,
)

/**
 * 单条标签升维结果（[TagClassifyOutput] 的元素）。
 *
 * @property tag       原始标签（清洗前，回写时以清洗后小写为缓存键）。
 * @property dimension 维度 key，取值见 [com.acgcompass.domain.taste.TasteCategory] 的 `key`
 *                     （topic/device/xp/character/staff/cv/source/time/meme/noise）。
 */
@Serializable
data class TagDimensionAssignment(
    val tag: String = "",
    val dimension: String = "",
)

/** 今日补番签：当天「宜」「忌」各一句。 */
@Serializable
data class DailyDrawOutput(
    val should: String = "",
    val shouldNot: String = "",
)

/** VNDB 原文简介的简体中文翻译；只承载忠实译文与资料充分度。 */
@Serializable
data class VndbTranslationOutput(
    val text: String = "",
    val confidence: Float = 0f,
)

/**
 * 路线节点观看建议：必看 / 可选 / 可跳过 / 总集篇回顾。
 */
enum class RouteRecommendation {
    MUST,
    OPTIONAL,
    SKIP,
    RECAP,
    ;

    companion object {
        /** 从模型输出字符串解析；未知 / `null` 回退为 [OPTIONAL]（保守，不编造强约束）。 */
        fun fromRaw(raw: String?): RouteRecommendation =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OPTIONAL
    }
}

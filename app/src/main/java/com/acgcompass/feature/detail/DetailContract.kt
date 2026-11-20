package com.acgcompass.feature.detail

import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.visualnovel.VisualNovelDetail

/**
 * 作品详情页（Detail_Screen）的 UI 契约。
 *
 * 把领域层的 [Work] 与 [RatingAggregate] 折叠为表现层可直接渲染的纯数据模型。映射逻辑为
 * **纯函数**（无 Android / IO 依赖），便于单元测试，并集中体现两条核心不变式：
 *
 * - **缺失即标记、绝不伪造**：任一字段 / 平台缺失时显示「[NO_DATA]」，不隐藏整块或编造数值。
 * - **样本不足不下结论**：社区共识为 `null` 时呈现 [ConsensusUiModel.Insufficient]（低置信 / 暂无），
 *   不给出确定性的客观结论。
 *
 * 个人区、决策区与详情 Tab 均由本契约建模。
 * 本契约覆盖顶部信息区、评分区 / 社区共识卡、个人区、决策区、详情 Tab 与补完成本分类。
 */
data class DetailUiState(
    val header: DetailHeader,
    val ratings: List<PlatformRatingUiModel>,
    val consensus: ConsensusUiModel,
    val personal: PersonalUiModel,
    val decision: DecisionUiModel,
    val tabs: List<DetailTabUiModel>,
    val completionCost: CompletionCostUiModel,
    /** 结构化观看路线。 */
    val routeEntries: List<RouteEntryUi> = emptyList(),
    /** Bangumi 社区标签（按热度序），用于详情页「标签」区展示。空表示暂无、不展示该区。 */
    val tags: List<String> = emptyList(),
    val vndbDetail: VisualNovelDetail? = null,
    val vndbLoading: Boolean = false,
    val vndbError: String? = null,
    val isVndb: Boolean = false,
    val vndbBangumiLinked: Boolean = false,
)

/**
 * 顶部信息区：封面、主标题（中文 / 规范化名）与信息行集合
 * （原名 / 别名 / 类型 / 年份 / 状态 / 集数·卷数·游玩时长）。
 */
data class DetailHeader(
    val coverUrl: String?,
    val originalCoverUrl: String? = null,
    val title: String,
    val infoRows: List<DetailInfoRow>,
    val sensitiveMedia: Boolean = false,
)

/**
 * 顶部信息区的一行键值。[isMissing] 为 `true` 时 [value] 即「[NO_DATA]」，UI 据此弱化展示
 * （缺失即标记）。
 */
data class DetailInfoRow(
    val label: String,
    val value: String,
    val isMissing: Boolean,
)

/**
 * 单平台评分。[scoreText] 为 `null` 表示该平台「暂无数据」，UI 仍渲染该行而**不隐藏**
 * 整个评分区（partial-missing）。
 *
 * @property platform 平台展示名（如「Bangumi」「MAL·Jikan」）。
 * @property scoreText 评分文案（已按平台量纲格式化）；缺失时为 `null`。
 * @property voteText 评分人数文案；缺失时为 `null`。
 */
data class PlatformRatingUiModel(
    val platform: String,
    val scoreText: String?,
    val voteText: String?,
) {
    /** 该平台是否有有效评分；为 `false` 时 UI 显示「[NO_DATA]」。 */
    val available: Boolean get() = scoreText != null
}

/**
 * 社区共识卡。样本充足时呈现稳定度 / 争议度 / 优先级；不足时为 [Insufficient]，
 * 不伪造客观结论。
 */
sealed interface ConsensusUiModel {

    /** 样本充足，给出三项共识指标（均为已格式化的展示文案）。 */
    data class Available(
        val stability: ConsensusMetric,
        val controversy: ConsensusMetric,
        val priority: ConsensusMetric,
    ) : ConsensusUiModel

    /** 有效样本不足，低置信 / 暂无共识（不下结论）。 */
    data object Insufficient : ConsensusUiModel
}

/**
 * 单项共识指标：百分比文案 + 定性描述 + 归一化到 `[0,1]` 的进度值（供进度条渲染）。
 */
data class ConsensusMetric(
    val label: String,
    val percentText: String,
    val qualitativeText: String,
    val fraction: Float,
)

/**
 * 个人区：我的状态 / 评分 / 进度 / 短评 / 标签 + 待补池开关。
 *
 * 各文本字段缺失时为 `null`，UI 渲染「[NO_DATA]」占位（绝不伪造个人记录）。
 * [inBacklog] 决定「加入 / 移出待补池」按钮的形态。
 *
 * @property statusText 我的收藏 / 观看状态（如 想看 / 在看 / 看过）。
 * @property ratingText 我的评分文案（如「8 / 10」）。
 * @property progressText 我的进度文案（如「已看 12 集」）。
 * @property reviewText 我的短评。
 * @property tags 用户在自己的收藏记录中填写的标签；仅供编辑和同步，不与作品社区标签混用。
 * @property inBacklog 该作品是否已在待补池。
 * @property inDustMuseum 该作品是否已落入吃灰区。
 * @property inLibrary 该作品是否已纳入我的库。
 */
data class PersonalUiModel(
    val statusText: String?,
    val ratingText: String?,
    val progressText: String?,
    val reviewText: String?,
    val tags: List<String>,
    val inBacklog: Boolean,
    val inDustMuseum: Boolean = false,
    val inLibrary: Boolean = false,
    /** 作品总集数（用于进度编辑显示「共 N 话」并限制上限）；缺失为 null。 */
    val totalEpisodes: Int? = null,
    /** 进度是否可编辑/上传——仅动画（书籍/漫画/游戏不显示进度）。 */
    val progressEditable: Boolean = true,
    /** 是否 Bangumi 关联（有 Bangumi 词条/评分）。否则只展示「加入待补池」，不展示我的状态/评分。 */
    val recordEditable: Boolean = true,
    /** 该条记录是否仅自己可见，用于状态展示和编辑回显。 */
    val isPrivate: Boolean = false,
)

/**
 * 决策区：口味匹配度、推荐 / 不推荐理由、无剧透评价雷达入口、适合心情与补完成本。
 *
 * @property tasteMatch 口味匹配度（来自口味画像；未生成画像时为 [TasteMatchUiModel.Unavailable]）。
 * @property recommendReasons 推荐理由（基于已有信号派生，可空集合）。
 * @property notRecommendReasons 不推荐 / 需注意理由（可空集合）。
 * @property spoilerRadarSummary 无剧透评价雷达摘要；未接入时为 [NO_DATA]（保留入口）。
 * @property suitableMoods 适合心情标签（来自作品 MOOD 标签）。
 * @property completionCostText 补完成本一句话摘要。
 */
data class DecisionUiModel(
    val tasteMatch: TasteMatchUiModel,
    val recommendReasons: List<String>,
    val notRecommendReasons: List<String>,
    val spoilerRadarSummary: String,
    val suitableMoods: List<String>,
    val completionCostText: String,
    /** 雷达生成方式标注：「AI 总结」/「规则生成」；未生成时为 `null`。 */
    val spoilerRadarGenerator: String? = null,
    /** 实际生效剧透等级标签（当前恒为「无剧透」）；未生成时为 `null`。 */
    val spoilerRadarLevel: String? = null,
    /** 摘要来源标注（短评 / Reviews / 标签 / AI）；未生成时为空。 */
    val spoilerRadarSources: List<String> = emptyList(),
    /** 雷达置信度文案；低样本时偏低，未生成时为 `null`（不伪造）。 */
    val spoilerRadarConfidenceText: String? = null,
    /** 补番优先级等级（低/中/高）；无足够信号时为 `null`（不伪造）。 */
    val priorityLevel: String? = null,
    /** 补番优先级理由（综合口味匹配/社区评价/补完成本/情绪风险）。 */
    val priorityReason: String? = null,
)

/**
 * 口味匹配度。措辞采用「可能 / 倾向于」而非绝对结论；未生成口味画像时
 * 为 [Unavailable]，不伪造匹配。
 */
/**
 * 评分预测展示模型。三个数字并排回答「这个分数到底算高还是低」：预计你会打几分、社区评分是多少、
 * 你自己的平均分是多少——7.1 分对一个均分 7.8 的人来说并不高，这正是「预计评分不低、匹配度却一般」的原因。
 *
 * @property predictedText 预计评分（如「7.1」）。
 * @property communityText 社区评分（如「6.6」）；没有时为 null。
 * @property userMeanText 你的平均分。
 * @property selfSummary 相对你自己：这个预计评分在你的评分里算高、算低还是中等。
 * @property communitySummary 相对社区：比社区高 / 低多少，其中多少来自打分习惯、多少来自个人口味。
 * @property factors 个人口味部分的归因（如「悬疑 +0.3」），只列有实际影响的。
 */
data class RatingPredictionUi(
    val predictedText: String,
    val communityText: String?,
    val userMeanText: String,
    val selfSummary: String,
    val communitySummary: String,
    val factors: List<TasteMatchReasonUi>,
)

/** 由领域预测结果生成展示模型（详情页 / 探索卡片 / 今晚看什么共用同一口径的文案）。 */
fun com.acgcompass.domain.taste.RatingPrediction.toUi(): RatingPredictionUi {
    val self = relativeToSelf
    val vs = vsCommunity
    val habit = habitOffset
    return RatingPredictionUi(
        predictedText = "%.1f".format(rating),
        communityText = community?.let { "%.1f".format(it) },
        userMeanText = "%.1f".format(userMean),
        selfSummary = when {
            self >= SELF_HIGH -> "在你的评分里算高分"
            self >= SELF_ABOVE -> "比你的平均分略高"
            self <= -SELF_HIGH -> "明显低于你的平均分"
            self <= -SELF_ABOVE -> "比你的平均分略低，对你来说偏一般"
            else -> "接近你的平均分，中规中矩"
        },
        communitySummary = when {
            vs == null || habit == null -> "这部作品还没有社区评分，按你的平均水平估计"
            kotlin.math.abs(vs) < NOTABLE_DIVERGENCE -> "和社区评分基本一致"
            else -> buildString {
                append("比社区评分").append(if (vs > 0) "高 " else "低 ").append("%.1f".format(kotlin.math.abs(vs)))
                append("：打分习惯 ").append("%+.1f".format(habit))
                append(
                    "，个人口味 ",
                ).append(if (kotlin.math.abs(divergence) < MIN_SHOWN) "≈0" else "%+.1f".format(divergence))
            }
        },
        factors = factors.map {
            TasteMatchReasonUi(
                text = "${it.label} %+.1f".format(it.delta),
                positive = it.delta >= 0.0,
            )
        },
    )
}

internal const val NOTABLE_DIVERGENCE = 0.25
internal const val MIN_SHOWN = 0.05
internal const val SELF_ABOVE = 0.35
internal const val SELF_HIGH = 0.9

sealed interface TasteMatchUiModel {

    /**
     * 已有口味画像，给出非绝对措辞的匹配度。
     *
     * @property percentText 匹配度百分比文案。
     * @property qualitativeText 定性描述（如「可能会喜欢」）。
     * @property fraction 归一化 `[0,1]` 进度值（供进度条渲染）。
     * @property reason 匹配理由（基于高分标签重合等，非绝对措辞）。
     */
    data class Available(
        val percentText: String,
        val qualitativeText: String,
        val fraction: Float,
        val reason: String,
        /** 分维度贡献理由（+ 命中 / − 拉低），供详情页逐条展示可解释性；空则不展示。 */
        val reasons: List<TasteMatchReasonUi> = emptyList(),
        /** 置信度文案（如「置信度中等」）；null 则不展示。 */
        val confidenceText: String? = null,
        /** 评分预测与分歧归因；画像样本不足以建模时为 null（不展示，绝不伪造）。 */
        val prediction: RatingPredictionUi? = null,
        /** 预计评分（已评分为你的评分）高于你看过的百分之多少；没有评分模型时为 null。 */
        val rankPercent: Int? = null,
    ) : TasteMatchUiModel

    /** 尚未生成口味画像，或样本/标签不足以计算匹配度（[message] 说明具体原因，不伪造）。 */
    data class Unavailable(val message: String) : TasteMatchUiModel
}

/**
 * 口味匹配度的单条「可解释」贡献理由。
 *
 * @property text 展示文案（如「题材·奇幻」「题材组合·奇幻+异世界」）。
 * @property positive 是否为正向贡献（true=拉高匹配，false=拉低）。
 */
data class TasteMatchReasonUi(
    val text: String,
    val positive: Boolean,
)

/**
 * 详情 Tab 的单页：简介 / 评论摘要 / 角色·Staff / 关联作品 / 观看路线 / 平台数据 / 我的记录。
 *
 * 尚未接入数据源的 Tab 以 [body] = [NO_DATA] 占位（绝不伪造）。
 *
 * @property title Tab 标题。
 * @property body Tab 正文（缺失时为「暂无数据」）。
 * @property available 该 Tab 是否有可展示内容。
 */
data class DetailTabUiModel(
    val title: String,
    val body: String,
    val status: TabStatus = TabStatus.READY,
) {
    /** 是否有有效内容（非占位文字）。 */
    val available: Boolean get() = status == TabStatus.READY && body != NO_DATA
}

/** 详情 Tab 的加载状态：失败时界面给出「重新加载」。 */
enum class TabStatus { READY, LOADING, FAILED }

/** Bangumi 扩展资料（角色、Staff、关联、短评）的加载进度。 */
enum class ExtrasLoad { LOADING, LOADED, NO_SOURCE }

/**
 * 补完成本分类：按集数 × 单集时长 / 卷数 / 估计游玩时长归类为「今晚 / 周末 / 长期坑」。
 *
 * 无足够单位信息计算时 [available] 为 `false`，[bucketLabel] / [detailText] 为「[NO_DATA]」。
 *
 * @property available 是否成功计算出分类。
 * @property bucketLabel 分类标签（今晚可看完 / 周末补完 / 长期坑）。
 * @property detailText 估算明细（如「预计约 11 小时」）。
 */
data class CompletionCostUiModel(
    val available: Boolean,
    val bucketLabel: String,
    val detailText: String,
)

/** 字段 / 平台缺失时统一展示的文案。 */
const val NO_DATA: String = "暂无数据"

/**
 * 详情页「角色·Staff / 关联作品 / 观看路线」的预渲染正文（由 ViewModel 从各源端点拉取后折叠为
 * 可读文本，保持本契约纯函数、不依赖数据层 DTO）。缺失时为 [NO_DATA]，绝不伪造。
 */
data class DetailExtras(
    val charactersStaffBody: String = NO_DATA,
    val relationsBody: String = NO_DATA,
    val watchRouteBody: String = NO_DATA,
    /** 结构化观看路线，按主线、可选和可跳过分区；空表示无关联或路线待确认。 */
    val routeEntries: List<RouteEntryUi> = emptyList(),
    /** 真实他人短评展示行（「昵称 · N分：短评」），空表示暂无。 */
    val comments: List<String> = emptyList(),
    /** Bangumi 社区标签（按热度序，最多 15 个），用于详情页「标签」区展示。空表示暂无。 */
    val tags: List<String> = emptyList(),
    val vndbDetail: VisualNovelDetail? = null,
    val vndbLoading: Boolean = false,
    val vndbError: String? = null,
    val bangumiLoad: ExtrasLoad = ExtrasLoad.LOADING,
    val creditsFailed: Boolean = false,
    val commentsFailed: Boolean = false,
    /** 没拉到的原因；null 表示没失败或原因不明。 */
    val failure: com.acgcompass.domain.detail.ExtrasFailure? = null,
)

/** 观看路线分区；MAIN/OPTIONAL/SKIPPABLE 仅限动画，DERIVED 表示非动画衍生作品或原作。 */
enum class RouteSection { MAIN, OPTIONAL, SKIPPABLE, DERIVED }

/** 可导航到作品详情的观看路线条目。 */
data class RouteEntryUi(
    val workId: String,
    val title: String,
    val relationLabel: String,
    val section: RouteSection,
)

/**
 * AI 匹配度分析按钮的 UI 状态。
 *
 * 点击后经 [AiEngine] 运行 [com.acgcompass.domain.ai.AiTask.TasteMatch]，结果结构化为
 * matchScore / likedReasons / riskReasons / confidence；失败或未配置时回退本地模型并说明原因，
 * 页面不得无响应。绝不伪造。
 */
sealed interface AiMatchUi {

    /** 初始 / 未触发：仅展示「AI 分析匹配度」按钮。 */
    data object Idle : AiMatchUi

    /** 分析中：展示 loading / 思考中。 */
    data object Loading : AiMatchUi

    /** 未配置 AI：提示可用本地模型估计（[message]）。 */
    data class NotConfigured(val message: String) : AiMatchUi

    /**
     * 分析成功：结构化匹配度结果。
     *
     * @property matchPercentText 匹配度百分比文案（如「78%」）。
     * @property fraction 归一化 `[0,1]`（供进度条）。
     * @property likedReasons 可能喜欢的理由。
     * @property riskReasons 可能不喜欢 / 需注意的理由。
     * @property confidenceText 置信度文案。
     * @property dataSources 贡献数据来源标签。
     */
    data class Result(
        val matchPercentText: String,
        val fraction: Float,
        val likedReasons: List<String>,
        val riskReasons: List<String>,
        val confidenceText: String,
        val dataSources: List<String>,
        /** AI 预计评分（如「7.5」）；模型未返回时为 null。 */
        val predictedRatingText: String? = null,
        /** 与大众相比会更高 / 更低的一句话原因。 */
        val divergenceReason: String? = null,
        /** AI 归纳的「你打分最看重的角度」。 */
        val ratingAngles: List<String> = emptyList(),
    ) : AiMatchUi

    /** 失败回退：展示失败原因 + 已回退本地模型说明（[message]）。 */
    data class Error(val message: String) : AiMatchUi
}

/** 观看路线无法从关联资料可靠推导时的提示文案（绝不编造顺序）。 */
const val ROUTE_UNCONFIRMED: String = "路线待确认：关联资料不足，暂不推荐确定观看顺序（不编造）。"

/**
 * 角色署名：角色名、关系和声优。
 * 缺失字段为 `null`，渲染时省略而非伪造。
 */
data class CharacterCredit(
    val name: String,
    val role: String?,
    val cv: String?,
)

/** Staff 署名：人物名和职位。 */
data class StaffCredit(
    val name: String,
    val job: String?,
)

/** 关联作品条目：关系类型和标题。 */
data class RelatedWork(
    val relation: String?,
    val title: String,
)

/**
 * 详情页关联内容容器，包含从 Bangumi 拉取的角色、Staff 和关联作品。
 * 拉取失败或缺失时为空集合 → 对应 Tab 显示「暂无数据」，绝不伪造。
 */
data class DetailRelatedContent(
    val characters: List<CharacterCredit> = emptyList(),
    val staff: List<StaffCredit> = emptyList(),
    val relations: List<RelatedWork> = emptyList(),
)

// endregion

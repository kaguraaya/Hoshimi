package com.acgcompass.domain.model

import com.acgcompass.domain.ai.RouteRecommendation

/**
 * 系列关联类型（补番路线图）：续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇 等。
 *
 * 这是**领域层的权威定义**，用于 [RouteNode.relationType]。枚举名作为持久化字符串
 * （[RouteNodeEntity-like] relationType 列），**不得**随意重命名以保证升级兼容。
 * 解析未知 / 缺失值时回退为 [OTHER]，避免损坏 / 升级数据导致崩溃。
 */
enum class RouteRelationType {
    /** 续作 / 续篇（第二季、后续故事）。 */
    SEQUEL,

    /** 前传（时间线更早的故事）。 */
    PREQUEL,

    /** 外传 / 衍生（番外、衍生作品）。 */
    SIDE_STORY,

    /** OVA / OAD。 */
    OVA,

    /** 剧场版 / 电影。 */
    MOVIE,

    /** 总集篇（回顾向，通常可跳过）。 */
    RECAP,

    /** 其它 / 未分类关联。 */
    OTHER,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 一律回退为 [OTHER]，保证非空且不崩溃。 */
        fun fromStorage(raw: String?): RouteRelationType = entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

/**
 * 补番路线图中的单个节点。该模型位于领域层，不依赖 Android。
 *
 * 一个节点把某个系列（[seriesId]）下的关联作品（[workId]）与其关联类型 [relationType]、
 * 观看建议 [recommendation] 关联起来。
 *
 * **顺序预留**：[orderIndex] 与 [confirmed] 仅作为顺序逻辑的存储钩子；当前展示与加入待补池流程
 * 不据此计算或编造推荐观看顺序。资料不足时，上层应将 [confirmed] 置为 `false`
 * （「路线待确认」），且不依赖 [orderIndex] 作为权威顺序。
 *
 * @property id             节点稳定主键（同一系列内唯一）。
 * @property seriesId       所属系列标识。
 * @property workId         节点对应的规范化作品 id（关联 [Work.id]）。
 * @property relationType   关联类型（续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇 等）。
 * @property recommendation 观看建议：必看 / 可选 / 可跳过 / 总集篇回顾。
 * @property orderIndex     建议观看顺序序号（存储钩子）；[confirmed] 为 `false` 时不具约束力。
 * @property confirmed      路线是否已确认（存储钩子）；`false` 表示「路线待确认」，不应据 [orderIndex] 误导用户。
 */
data class RouteNode(
    val id: String,
    val seriesId: String,
    val workId: String,
    val relationType: RouteRelationType,
    val recommendation: RouteRecommendation = RouteRecommendation.OPTIONAL,
    val orderIndex: Int = 0,
    val confirmed: Boolean = false,
)

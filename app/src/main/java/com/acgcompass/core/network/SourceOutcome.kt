package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.domain.model.SourceId

/**
 * 单源成功取数的载荷。
 *
 * 把「业务数据」与「来源元信息」打包，供 [com.acgcompass.core.network.DataSourceOrchestrator]
 * 在命中首个可用源时直接转换为 [SourceOutcome.Success]。
 *
 * - [value]：实际业务数据。
 * - [confidence]：与本地规范化作品的匹配置信度 ∈ [0,1]；缺省 `1f` 表示精确命中。
 * - [missingFields]：本次取数中**字段级缺失**的字段名集合；UI 在这些字段位置显示「暂无数据」，
 *   而不影响其余字段。缺省为空集合表示字段齐全。
 *
 * > 不伪造内容：缺失字段只标记、不回填，由上层渲染为「暂无数据」。
 */
data class SourceData<out T>(
    val value: T,
    val confidence: Float = 1f,
    val missingFields: Set<String> = emptySet(),
)

/**
 * 降级编排的取数结果。
 *
 * 三态封闭类型，使「成功 / 整条缺失 / 失败」在类型层面互斥且可被 `when` 穷尽处理：
 *
 * - [Success]：命中某个可用源，携带数据、来源标签 [sourceTag]、匹配置信度 [confidence]，
 *   以及字段级缺失集合 [missingFields]（「暂无数据」由 UI 据此渲染）。
 * - [Missing]：所有源均「无该条目」（如统一返回空 / 404）而**非错误**——整条暂无数据，
 *   不构成可重试的失败。
 * - [Failure]：全部源不可用（禁用 / 未配置 / 调用失败）时返回的**可重试错误**结果，
 *   携带统一领域错误 [error]，由上层映射为错误卡片（简短原因 + 下一步 + 重试 + 文档）。
 *
 * 编排器**绝不**抓取网页或臆造数据。
 */
sealed interface SourceOutcome<out T> {

    /** 命中首个可用源。[sourceTag] 标注当前来源，[confidence] 为匹配置信度。 */
    data class Success<out T>(
        val data: T,
        val sourceTag: SourceId,
        val confidence: Float = 1f,
        val missingFields: Set<String> = emptySet(),
    ) : SourceOutcome<T>

    /** 所有源均无该条目（非错误的整条缺失）；UI 显示「暂无数据」。 */
    data object Missing : SourceOutcome<Nothing>

    /** 全部源不可用时的可重试错误结果（设计错误卡片四要素）。 */
    data class Failure(val error: AppError) : SourceOutcome<Nothing>
}

/** 成功时返回数据，否则返回 `null`。 */
fun <T> SourceOutcome<T>.dataOrNull(): T? = when (this) {
    is SourceOutcome.Success -> data
    SourceOutcome.Missing -> null
    is SourceOutcome.Failure -> null
}

/** 失败时返回 [AppError]，否则返回 `null`。 */
fun <T> SourceOutcome<T>.errorOrNull(): AppError? = when (this) {
    is SourceOutcome.Failure -> error
    else -> null
}

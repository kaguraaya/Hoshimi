package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.SourceId

/**
 * 集中式数据源降级编排器。
 *
 * 给定一次 [SourceRequest]，按**固定回退顺序** [FALLBACK_ORDER]
 * （`Bangumi → AniList → Jikan → MAL → VNDB`）依次尝试各源：
 *
 * 1. 跳过未参与 / 未配置（取数器缺省、为 `null`，或运行时返回 `null`）的源。
 * 2. 命中首个**取数成功**的源即返回 [SourceOutcome.Success]，并附该源 `sourceTag` 与
 *    匹配置信度；字段级缺失通过 [SourceOutcome.Success.missingFields] 标记，
 *    由 UI 渲染为「暂无数据」。
 * 3. 某源调用失败则记录其错误并降级到下一个源。
 * 4. 全部源不可用时返回**可重试的错误结果** [SourceOutcome.Failure]——
 *    永不抓取网页、永不臆造数据。
 *
 * 编排结果**仅取决于**各源的「可用 / 不可用」以及固定回退顺序，因此对给定的可用性组合
 * 顺序保持确定，便于测试和故障分析。
 */
interface DataSourceOrchestrator {

    /**
     * 按 [FALLBACK_ORDER] 降级取数，返回首个可用源的结果或全部不可用时的可重试错误。
     * 不抛出未捕获异常。
     */
    suspend fun <T> fetch(request: SourceRequest<T>): SourceOutcome<T>

    companion object {
        /**
         * **固定降级回退顺序**：`Bangumi → AniList → Jikan → MAL → VNDB`。
         *
         * 显式声明为常量列表，使「降级顺序确定性」可被属性测试断言。
         * 固定回退顺序：Bangumi → AniList → Jikan → MAL_Official → VNDB。
         */
        val FALLBACK_ORDER: List<SourceId> = listOf(
            SourceId.BANGUMI,
            SourceId.ANILIST,
            SourceId.JIKAN,
            SourceId.MAL,
            SourceId.VNDB,
        )
    }
}

/**
 * [DataSourceOrchestrator] 的纯粹、可测试默认实现。
 *
 * 无任何 Android / IO 依赖：是否「可用」完全由 [SourceRequest] 中各源取数器的返回值决定，
 * 因此对给定的可用性组合其行为是确定的（设计  可据此做属性测试）。
 */
class DefaultDataSourceOrchestrator : DataSourceOrchestrator {

    override suspend fun <T> fetch(request: SourceRequest<T>): SourceOutcome<T> {
        // 记录最近一次「源已尝试但失败」的领域错误，用于全部不可用时回传更贴切的原因。
        var lastFailure: AppError? = null

        for (sourceId in DataSourceOrchestrator.FALLBACK_ORDER) {
            // 该源未在本次请求登记取数器 -> 不参与，跳过。
            val fetcher = request.fetcherFor(sourceId) ?: continue

            // 取数器返回 null -> 运行时不可用（禁用 / 未配置凭据），跳过。
            val result = fetcher.fetch() ?: continue

            when (result) {
                is AppResult.Success -> {
                    val payload = result.data
                    return SourceOutcome.Success(
                        data = payload.value,
                        sourceTag = sourceId,
                        confidence = payload.confidence,
                        missingFields = payload.missingFields,
                    )
                }

                is AppResult.Failure -> {
                    // 本源本次不可用：记录原因并降级到下一个源。
                    lastFailure = result.error
                }
            }
        }

        // 全部源不可用：返回可重试的「暂无数据」错误结果（设计错误卡片四要素）。
        // 有源尝试失败则回传其错误（保留具体原因）；无任何源参与则回退到可重试的 FieldMissing。
        return SourceOutcome.Failure(lastFailure ?: AppError.FieldMissing())
    }
}

package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType

/**
 * AI / 规则结果缓存仓库契约（领域层，纯 Kotlin）。负责按「作品 + 任务类型」读写缓存结果。
 *
 * 缓存的 [AiResult] 携带 `generator`（AI / RULE）、`confidence` 与 `dataSources` 标注，
 * 供 AI 卡片展示来源与置信度，低置信不编造。
 *
 */
interface AiRepository {

    /**
     * 读取某作品某任务类型的缓存结果。未命中时为 [AppResult.Success] 携带 `null`。
     *
     * @param workId 规范化作品 id。
     * @param taskType AI / 规则任务类型。
     * @return 成功时为缓存的 [AiResult]（可为 `null`）；失败时为 [AppResult.Failure]。
     */
    suspend fun getCached(workId: String, taskType: AiTaskType): AppResult<AiResult?>

    /**
     * 写入 / 更新缓存结果。同一作品 + 任务类型应覆盖旧值。
     *
     * @param result 待缓存的 AI / 规则结果。
     */
    suspend fun put(result: AiResult)
}

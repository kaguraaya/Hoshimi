package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import kotlinx.coroutines.flow.Flow

/**
 * 评分仓库契约（领域层，纯 Kotlin）。负责单源评分缓存的读写与多源聚合。
 *
 * 聚合遵循「不伪造、缺失即标记」：缺失源在 [RatingAggregate.perSource] 中为 `null`，
 * **绝不**用其它源数据回填；有效样本不足时社区共识为 `null` 或低置信。
 *
 */
interface RatingRepository {

    /**
     * 观察某作品的多源评分聚合。从本地单一可信源读取，远程刷新后自动发射。
     *
     * @param workId 规范化作品 id。
     * @return 七态 [UiState]；部分源缺失时发射 [UiState.PartialMissing]。
     */
    fun observeRatings(workId: String): Flow<UiState<RatingAggregate>>

    /**
     * 刷新并聚合某作品的多源评分（触发远程拉取后写入本地缓存）。
     *
     * @param workId 规范化作品 id。
     * @return 成功时为 [RatingAggregate]；失败时为 [AppResult.Failure]。
     */
    suspend fun refreshRatings(workId: String): AppResult<RatingAggregate>

    /**
     * 写入 / 更新某作品来自指定源的单源评分。
     *
     * @param workId 规范化作品 id。
     * @param source 评分来源。
     * @param entry 单源评分条目。
     */
    suspend fun upsertRating(workId: String, source: SourceId, entry: RatingEntry)
}

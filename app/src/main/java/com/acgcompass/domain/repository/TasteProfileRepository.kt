package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.usecase.TasteInputRecord
import kotlinx.coroutines.flow.Flow

/**
 * 口味画像持久化仓库契约（领域层，纯 Kotlin）。
 *
 * 负责把导入的用户评分/状态/短评/标签经纯统计（`TasteStatsCalculator`）计算后落库，并对外暴露
 * 可观察的口味画像。遵循「样本不足即低置信」：样本数越低，
 * 生成画像的 `confidence` 越低，供表现层采用「可能 / 倾向于」措辞。
 *
 * 评分习惯（严格度/平均分/高分稀有度/分数段）、口味称号、口味黑洞与详情页匹配度属 ，
 * 不在本契约职责内。
 *
 */
interface TasteProfileRepository {

    /**
     * 观察当前（最近一次生成的）口味画像。尚未生成时发射 `null`。
     *
     * @return 口味画像流（可为 `null`），随底层数据变化重新发射。
     */
    fun observeTasteProfile(): Flow<TasteProfile?>

    /**
     * 导入用户记录并计算 + 持久化口味画像。统计高分/低分标签、常用短评词与常见
     * 搁置类型；样本不足时降低置信度。
     *
     * @param records 来自数据源、已归一的用户个人记录。
     * @return 成功时为新生成的 [TasteProfile]；失败时为 [AppResult.Failure]。
     */
    suspend fun importAndCompute(records: List<TasteInputRecord>): AppResult<TasteProfile>

    /**
     * 用当前本地 `user_collections`（评分 / 状态 / 短评 / 标签）重新计算并持久化口味画像。
     *
     * 用户在详情页保存或清空记录后调用，使变化及时反映到口味画像，
     * 无需手动重新导入。无任何本地记录时不生成画像（不伪造），返回 `Success(null)`。
     *
     * @return 成功时为新画像（无样本时为 `null`）；失败时为 [AppResult.Failure]。
     */
    suspend fun recomputeFromLocal(): AppResult<TasteProfile?>
}

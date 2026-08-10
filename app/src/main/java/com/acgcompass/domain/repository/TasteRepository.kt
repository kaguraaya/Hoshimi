package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.TasteProfile
import kotlinx.coroutines.flow.Flow

/**
 * 口味画像仓库契约（领域层，纯 Kotlin）。负责画像的观察与（重新）生成。
 *
 * 生成遵循「样本不足即低置信」：样本数低于阈值时画像 `confidence` 为低，
 * 措辞采用「可能 / 倾向于」而非绝对判断。未配置 AI key 时由本地规则统计回退。
 *
 */
interface TasteRepository {

    /**
     * 观察当前口味画像。尚未生成时发射 `null`。
     *
     * @return 口味画像流（可为 `null`）。
     */
    fun observeProfile(): Flow<TasteProfile?>

    /**
     * （重新）生成口味画像。基于用户评分历史与标签统计；样本不足时降低置信度。
     *
     * @return 成功时为新生成的 [TasteProfile]；失败时为 [AppResult.Failure]。
     */
    suspend fun generate(): AppResult<TasteProfile>
}

package com.acgcompass.feature.detail

import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import kotlin.math.roundToInt

/**
 * 补完成本分类 UI：优先用 [Work.completionCost]，缺失时由 [computeCompletionCost] 计算。
 */
internal fun Work.toCompletionCostUiModel(): CompletionCostUiModel {
    val cost = completionCost ?: computeCompletionCost(units, mediaType)
        ?: return CompletionCostUiModel(available = false, bucketLabel = NO_DATA, detailText = NO_DATA)
    val minutes = estimateTotalMinutes(units, mediaType)
    val detail = minutes
        ?.let { "预计约 ${(it / 60f).roundToInt().coerceAtLeast(1)} 小时" }
        ?: units.describe(mediaType)
        ?: NO_DATA
    return CompletionCostUiModel(
        available = true,
        bucketLabel = cost.bucketLabel(),
        detailText = detail,
    )
}

internal fun CompletionCost.bucketLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚可看完"
    CompletionCost.WEEKEND -> "周末补完"
    CompletionCost.LONG_HAUL -> "长期坑"
}

/**
 * **纯函数**：按集数 × 单集时长 / 卷数 / 估计游玩时长计算 [CompletionCost] 分桶。
 *
 * 估算口径见 [estimateTotalMinutes]；无足够单位信息时返回 `null`（成本未知，UI 显示「暂无数据」）。
 * 阈值：≤ [TONIGHT_MAX_MINUTES] 今晚、≤ [WEEKEND_MAX_MINUTES] 周末，其余长期坑。
 *
 * @param units 体量 / 时长单位。
 * @param mediaType 媒介类型（决定使用哪些单位）。
 */
fun computeCompletionCost(units: Units, mediaType: MediaType): CompletionCost? {
    val minutes = estimateTotalMinutes(units, mediaType) ?: return null
    return when {
        minutes <= TONIGHT_MAX_MINUTES -> CompletionCost.TONIGHT
        minutes <= WEEKEND_MAX_MINUTES -> CompletionCost.WEEKEND
        else -> CompletionCost.LONG_HAUL
    }
}

/**
 * 估算补完总时长（分钟）。各媒介各取所需，缺失关键单位返回 `null`：
 * - 动画：集数 × 单集时长（单集时长缺失时按 [DEFAULT_EPISODE_MINUTES] 估）。
 * - 游戏 / VN：估计游玩时长。
 * - 漫画 / 小说：卷数 × [MINUTES_PER_VOLUME]。
 */
internal fun estimateTotalMinutes(units: Units, mediaType: MediaType): Int? = when (mediaType) {
    MediaType.ANIME ->
        units.episodes?.let { eps -> eps * (units.episodeMinutes ?: DEFAULT_EPISODE_MINUTES) }
    MediaType.GAME, MediaType.VN -> units.estPlayMinutes
    MediaType.MANGA, MediaType.NOVEL -> units.volumes?.let { it * MINUTES_PER_VOLUME }
    MediaType.OTHER -> null
}

/** 「今晚可看完」上限（分钟）：约一个晚上。 */
internal const val TONIGHT_MAX_MINUTES = 180

/** 「周末补完」上限（分钟）：约一个周末（~20 小时）。 */
internal const val WEEKEND_MAX_MINUTES = 1_200

/** 单集时长缺省估值（分钟）：常见 TV 动画单集约 24 分钟。 */
internal const val DEFAULT_EPISODE_MINUTES = 24

/** 单卷漫画 / 小说阅读时长估值（分钟）。 */
internal const val MINUTES_PER_VOLUME = 60

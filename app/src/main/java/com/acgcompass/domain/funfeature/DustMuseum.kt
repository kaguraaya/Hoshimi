package com.acgcompass.domain.funfeature

import com.acgcompass.domain.model.BacklogItem

/** 进入「吃灰博物馆」的默认吃灰天数阈值（含），。 */
const val DEFAULT_DUST_MUSEUM_THRESHOLD_DAYS: Int = 30

/**
 * 吃灰博物馆选择器——纯函数。
 *
 * 返回待补池里「躺太久」的作品：吃灰天数 ≥ [thresholdDays]，或已被显式标记为 [BacklogItem.inDustMuseum]。
 * 结果按吃灰天数降序（躺得最久的排前面），天数相同保持稳定。
 *
 * @param backlog 待补池条目。
 * @param thresholdDays 进入吃灰博物馆的吃灰天数阈值（含），默认 [DEFAULT_DUST_MUSEUM_THRESHOLD_DAYS]。
 * @return 命中阈值的条目，按吃灰最久优先排序。
 */
fun selectDustMuseum(
    backlog: List<BacklogItem>,
    thresholdDays: Int = DEFAULT_DUST_MUSEUM_THRESHOLD_DAYS,
): List<BacklogItem> = backlog
    .filter { it.inDustMuseum || it.dustDays >= thresholdDays }
    .sortedByDescending { it.dustDays }

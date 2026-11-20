package com.acgcompass.feature.cache

import com.acgcompass.domain.cache.CacheFillRun
import com.acgcompass.domain.cache.CacheItem
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.cache.CacheUsage
import com.acgcompass.domain.model.SourceId

data class CacheManagementUiState(
    val policy: CachePolicy = CachePolicy(),
    val usage: CacheUsage = CacheUsage(),
    val items: List<CacheItem> = emptyList(),
    val query: String = "",
    val selectedWorkIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val fillInProgress: Boolean = false,
    val message: String? = null,
    val fillRuns: List<CacheFillRun> = emptyList(),
    /** 设置和用量都读到之后才为 true；之前不显示上限等数值，免得先闪一下默认值再跳成用户选的值。 */
    val loaded: Boolean = false,
) {
    val mode: CacheLimitMode get() = policy.mode
    val utilization: Float get() = usage.utilization(policy).coerceIn(0f, 1f)
    val filteredItems: List<CacheItem> get() = items.filter { item ->
        query.isBlank() || item.title.contains(query, ignoreCase = true) ||
            item.workId.contains(query, ignoreCase = true)
    }
    val sourceCoverage: Map<SourceId, Int> get() = usage.sourceCoverage
}

sealed interface CachePolicyEvent {
    data class ModeChanged(val mode: CacheLimitMode) : CachePolicyEvent
    data class CountLimitChanged(val value: Int) : CachePolicyEvent
    data class SpaceLimitChanged(val value: Int) : CachePolicyEvent
    data class BackgroundFillChanged(val enabled: Boolean) : CachePolicyEvent
    data object StartFill : CachePolicyEvent
    data object StopFill : CachePolicyEvent
}

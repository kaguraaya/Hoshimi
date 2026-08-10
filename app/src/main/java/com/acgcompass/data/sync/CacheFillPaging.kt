package com.acgcompass.data.sync

internal sealed interface CacheCandidatePageResult<out T> {
    data class Page<T>(
        val items: List<T>,
        val hasMore: Boolean,
    ) : CacheCandidatePageResult<T>

    data class Failure(val reason: String?) : CacheCandidatePageResult<Nothing>
}

data class CacheFillBatch<T>(
    val items: List<T>,
    val nextPage: Int,
    val exhausted: Boolean,
    val result: String,
    val failureReason: String? = null,
)

internal data class CacheFillPagingConfig(
    val targetCount: Int,
    val existingCount: Int,
    val startPage: Int,
)

/** Collects unique candidates without imposing an arbitrary page ceiling. */
internal suspend fun <T, K> collectCacheFillPages(
    config: CacheFillPagingConfig,
    keyOf: (T) -> K,
    fetchPage: suspend (Int) -> CacheCandidatePageResult<T>,
    afterPage: suspend (projectedCount: Int, targetCount: Int) -> Unit = { _, _ -> },
): CacheFillBatch<T> {
    val normalizedTarget = config.targetCount.coerceAtLeast(0)
    val existingCount = config.existingCount.coerceAtLeast(0)
    var page = config.startPage.coerceAtLeast(1)
    if (existingCount >= normalizedTarget) {
        return CacheFillBatch(
            items = emptyList(),
            nextPage = page,
            exhausted = false,
            result = RESULT_TARGET_REACHED,
        )
    }

    val required = normalizedTarget - existingCount
    val unique = LinkedHashMap<K, T>()
    var outcome: CacheFillBatch<T>? = null
    while (unique.size < required) {
        when (val response = fetchPage(page)) {
            is CacheCandidatePageResult.Failure -> {
                outcome = unique.toBatch(page, exhausted = false, RESULT_FAILED, response.reason)
            }

            is CacheCandidatePageResult.Page -> {
                unique.addCandidates(response.items, required, keyOf)
                page++
                outcome = when {
                    unique.size >= required -> unique.toBatch(page, exhausted = false, RESULT_TARGET_REACHED)
                    !response.hasMore -> unique.toBatch(page, exhausted = true, RESULT_EXHAUSTED)
                    else -> null
                }
                if (outcome == null) afterPage(existingCount + unique.size, normalizedTarget)
            }
        }
        if (outcome != null) break
    }

    return outcome ?: unique.toBatch(page, exhausted = false, RESULT_TARGET_REACHED)
}

private fun <T, K> MutableMap<K, T>.addCandidates(candidates: List<T>, maximumSize: Int, keyOf: (T) -> K) {
    candidates.forEach { candidate ->
        if (size < maximumSize) putIfAbsent(keyOf(candidate), candidate)
    }
}

private fun <T, K> Map<K, T>.toBatch(
    nextPage: Int,
    exhausted: Boolean,
    result: String,
    failureReason: String? = null,
) = CacheFillBatch(
    items = values.toList(),
    nextPage = nextPage,
    exhausted = exhausted,
    result = result,
    failureReason = failureReason,
)

internal const val RESULT_TARGET_REACHED = "TARGET_REACHED"
internal const val RESULT_EXHAUSTED = "EXHAUSTED"
internal const val RESULT_FAILED = "FAILED"

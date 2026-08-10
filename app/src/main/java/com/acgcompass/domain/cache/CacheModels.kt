package com.acgcompass.domain.cache

import com.acgcompass.domain.model.SourceId

enum class CacheLimitMode {
    COUNT,
    SPACE,
}

enum class CacheAssetKind(val evictionPriority: Int) {
    SCREENSHOT_ORIGINAL(0),
    SCREENSHOT_THUMBNAIL(1),
    COVER(2),
    DETAIL(3),
    WORK(4),
}

data class CachePolicy(
    val mode: CacheLimitMode = CacheLimitMode.COUNT,
    val countLimit: Int = DEFAULT_COUNT_LIMIT,
    val spaceLimitBytes: Long = DEFAULT_SPACE_LIMIT_BYTES,
    val backgroundFillEnabled: Boolean = false,
) {
    val normalizedCountLimit: Int = countLimit.coerceIn(MIN_COUNT_LIMIT, MAX_COUNT_LIMIT)
    val normalizedSpaceLimitBytes: Long = spaceLimitBytes.coerceIn(MIN_SPACE_LIMIT_BYTES, MAX_SPACE_LIMIT_BYTES)

    companion object {
        const val DEFAULT_COUNT_LIMIT = 1_000
        const val MIN_COUNT_LIMIT = 100
        const val MAX_COUNT_LIMIT = 10_000
        const val DEFAULT_SPACE_LIMIT_BYTES = 250L * 1024L * 1024L
        const val MIN_SPACE_LIMIT_BYTES = 50L * 1024L * 1024L
        const val MAX_SPACE_LIMIT_BYTES = 5_120L * 1024L * 1024L
    }
}

data class CacheUsage(
    val publicWorkCount: Int = 0,
    val metadataBytes: Long = 0,
    val mediaBytes: Long = 0,
    val protectedWorkCount: Int = 0,
    val sourceCoverage: Map<SourceId, Int> = emptyMap(),
    /** Best-effort size of files visible to the app; it is not the system's billing size. */
    val appStorageBytes: Long = 0,
) {
    val totalBytes: Long get() = metadataBytes + mediaBytes

    fun utilization(policy: CachePolicy): Float = when (policy.mode) {
        CacheLimitMode.COUNT -> publicWorkCount.toFloat() / policy.normalizedCountLimit
        CacheLimitMode.SPACE -> totalBytes.toFloat() / policy.normalizedSpaceLimitBytes
    }.coerceAtLeast(0f)
}

data class CacheItem(
    val workId: String,
    val title: String,
    val source: SourceId,
    val estimatedBytes: Long,
    val mediaBytes: Long,
    val lastAccessedAt: Long,
    val protected: Boolean,
    val assetKinds: Set<CacheAssetKind> = setOf(CacheAssetKind.WORK),
)

data class ManagedMediaRecord(
    val cacheKey: String,
    val url: String,
    val workId: String? = null,
    val source: SourceId? = null,
    val kind: CacheAssetKind = CacheAssetKind.COVER,
    val adult: Boolean = false,
) {
    companion object {
        const val PARAM_WORK_ID = "acg.managed.work_id"
        const val PARAM_SOURCE = "acg.managed.source"
        const val PARAM_KIND = "acg.managed.kind"
        const val PARAM_ADULT = "acg.managed.adult"
    }
}

data class CacheClearRequest(
    val workIds: Set<String> = emptySet(),
    val sources: Set<SourceId> = emptySet(),
    val assetKinds: Set<CacheAssetKind> = emptySet(),
    val expiredDetailsOnly: Boolean = false,
)

data class CacheTrimResult(
    val removedWorks: Int = 0,
    val removedAssets: Int = 0,
    val releasedBytes: Long = 0,
    val protectedSkipped: Int = 0,
)

data class CacheFillRun(
    val source: String,
    val nextPage: Int,
    val exhausted: Boolean,
    val lastRunAt: Long?,
    val result: String?,
    val failureReason: String?,
    val consecutiveFailures: Int = 0,
    val cooldownUntil: Long? = null,
    val lastSuccessAt: Long? = null,
)

object CacheSourceAllocation {
    val weights: Map<SourceId, Int> = linkedMapOf(
        SourceId.BANGUMI to 80,
        SourceId.TENRAI to 5,
        SourceId.ANILIST to 5,
        SourceId.VNDB to 10,
    )

    fun countTargets(total: Int): Map<SourceId, Int> {
        val normalized = total.coerceAtLeast(0)
        val targets = weights.mapValues { (_, weight) -> normalized * weight / 100 }.toMutableMap()
        val remainder = normalized - targets.values.sum()
        targets[SourceId.BANGUMI] = targets.getValue(SourceId.BANGUMI) + remainder
        return targets
    }

    /** Additional delay applied after each source page; source-specific rate limiters still take precedence. */
    fun delayMillis(utilization: Float): Long = when {
        utilization < 0.25f -> 0L
        utilization < 0.60f -> 800L
        utilization < 0.85f -> 1_200L
        else -> 1_800L
    }
}

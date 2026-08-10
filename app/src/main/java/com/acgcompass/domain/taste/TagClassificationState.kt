package com.acgcompass.domain.taste

enum class TagClassificationPhase {
    REQUESTING,
    VALIDATING,
    WRITING,
}

enum class TagClassificationResult {
    COMPLETED,
    PARTIAL,
    NOTHING_TO_DO,
    NOT_CONFIGURED,
    FAILED,
    STOPPED,
}

data class TagClassificationState(
    val active: Boolean = false,
    val phase: TagClassificationPhase? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val batchIndex: Int = 0,
    val batchCount: Int = 0,
    val classified: Int = 0,
    val manualReview: Int = 0,
    val rejected: Int = 0,
    val failed: Int = 0,
    val remaining: Int = 0,
    val lastError: String? = null,
    val retryAt: Long? = null,
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
    val lastResult: TagClassificationResult? = null,
)

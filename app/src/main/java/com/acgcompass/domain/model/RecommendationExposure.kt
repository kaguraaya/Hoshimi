package com.acgcompass.domain.model

/** 一次推荐曝光（今晚看什么 / 探索队列），用于重复推荐冷却和滑动反馈。 */
data class RecommendationExposure(
    val id: String,
    val subjectId: String,
    val context: String,
    val exposedAt: Long,
    val clickedAt: Long?,
    val dismissedAt: Long?,
)

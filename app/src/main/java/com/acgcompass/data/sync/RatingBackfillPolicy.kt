package com.acgcompass.data.sync

import com.acgcompass.data.local.entity.RatingBackfillStateEntity
import com.acgcompass.domain.model.RatingBackfillOutcome

/**
 * 后台补评分的重试规则：
 * - 查过了（找到评分或确认没有）：[CHECKED_RETRY_MS] 后再看一次；
 * - 网络、限流、服务端错误：按 [TRANSIENT_RETRY_MS] 逐次拉长，几小时后就再试，最长一天；
 * - 其它错误：一天后再试。
 */
internal object RatingBackfillPolicy {
    const val CHECKED_RETRY_MS: Long = 7L * 24 * 60 * 60 * 1000
    private const val HOUR_MS: Long = 60L * 60 * 1000
    private const val DAY_MS: Long = 24 * HOUR_MS

    /** 暂时失败后的等待时间：第 1、2、3、4 次及以后。 */
    @Suppress("MagicNumber") // 退避阶梯本身就是一组数字
    val TRANSIENT_RETRY_MS: List<Long> = listOf(1, 3, 6, 12, 24).map { it * HOUR_MS }
    const val FAILED_RETRY_MS: Long = DAY_MS

    fun next(
        workId: String,
        previous: RatingBackfillStateEntity?,
        outcome: RatingBackfillOutcome,
        now: Long,
    ): RatingBackfillStateEntity = when (outcome) {
        RatingBackfillOutcome.CHECKED -> RatingBackfillStateEntity(
            workId = workId,
            lastAttemptAt = now,
            lastCheckedAt = now,
            failureCount = 0,
            nextAttemptAt = now + CHECKED_RETRY_MS,
            lastOutcome = outcome.name,
        )
        RatingBackfillOutcome.TRANSIENT_FAILURE, RatingBackfillOutcome.FAILED -> {
            val failures = (previous?.failureCount ?: 0) + 1
            val wait = if (outcome == RatingBackfillOutcome.FAILED) {
                FAILED_RETRY_MS
            } else {
                TRANSIENT_RETRY_MS[(failures - 1).coerceAtMost(TRANSIENT_RETRY_MS.lastIndex)]
            }
            RatingBackfillStateEntity(
                workId = workId,
                lastAttemptAt = now,
                lastCheckedAt = previous?.lastCheckedAt,
                failureCount = failures,
                nextAttemptAt = now + wait,
                lastOutcome = outcome.name,
            )
        }
    }

    /** 开始查一部作品前的占位时长，见 [lease]。 */
    const val LEASE_MS: Long = 2 * HOUR_MS

    /** 占位行的结果名：这部作品已经开始查、还没有结果。 */
    const val LEASED: String = "LEASED"

    /**
     * 开始查一部作品之前先占位：把下次可以再试的时间推到 [LEASE_MS] 之后，失败次数和上次结果不变。
     * 运行中途被系统停掉时，这部作品不会在下一次运行里又排第一、再次卡住整批；查完后由 [next] 覆盖。
     */
    fun lease(workId: String, previous: RatingBackfillStateEntity?, now: Long): RatingBackfillStateEntity =
        (previous ?: RatingBackfillStateEntity(workId, now, null, 0, now, LEASED))
            .copy(lastAttemptAt = now, nextAttemptAt = now + LEASE_MS)

    /** 旧版本存在设置里的「最近查过」记录换算成表里的行：当作查过了，按原来的时间往后排。 */
    fun fromLegacy(attempts: Map<String, Long>): List<RatingBackfillStateEntity> = attempts.map { (id, at) ->
        RatingBackfillStateEntity(
            workId = id,
            lastAttemptAt = at,
            lastCheckedAt = at,
            failureCount = 0,
            nextAttemptAt = at + CHECKED_RETRY_MS,
            lastOutcome = RatingBackfillOutcome.CHECKED.name,
        )
    }
}

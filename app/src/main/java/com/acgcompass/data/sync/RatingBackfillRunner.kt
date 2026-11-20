package com.acgcompass.data.sync

import com.acgcompass.data.local.dao.RatingBackfillDao
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.RatingBackfillReport
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一批补评分的执行过程（从 Worker 里拆出来，便于测试）：
 * - 每部作品开始前先占位（[RatingBackfillPolicy.lease]），被系统停掉时下次不会又从这一部开始；
 * - 每部最多等 [itemTimeoutMs]：某个平台在限速冷却时请求会一直排队，到点就算这部暂时没查成，接着查下一部；
 * - 整批最多跑 [budgetMs]，到点停下，剩下的下次继续；
 * - 记下每部没拿到结果的平台，汇总进这批的说明。
 */
internal class RatingBackfillRunner(
    private val dao: RatingBackfillDao,
    private val backfill: suspend (String) -> RatingBackfillReport,
    private val itemTimeoutMs: Long,
    private val budgetMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** 运行中的进度：被系统停掉时用来说明停在哪里。 */
    class Progress {
        @Volatile var done: Int = 0

        @Volatile var currentId: String? = null

        @Volatile var currentStartedAtMs: Long = 0L
    }

    @Suppress("TooGenericExceptionCaught") // 单部作品的任何异常都只算这一部没查成
    suspend fun run(targets: List<String>, progress: Progress): RatingBackfillBatch {
        val deadline = elapsedMs() + budgetMs
        var checked = 0
        var transient = 0
        var timedOut = 0
        val failedBySource = LinkedHashMap<String, Int>()
        for (id in targets) {
            if (elapsedMs() > deadline) break
            dao.upsert(RatingBackfillPolicy.lease(id, dao.get(id), nowMs()))
            progress.currentId = id
            progress.currentStartedAtMs = elapsedMs()
            val report = try {
                withTimeoutOrNull(itemTimeoutMs) { backfill(id) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RatingBackfillReport(RatingBackfillOutcome.FAILED)
            }
            val outcome = report?.outcome ?: RatingBackfillOutcome.TRANSIENT_FAILURE
            if (report == null) timedOut++
            report?.failedSources?.forEach { failedBySource[it] = (failedBySource[it] ?: 0) + 1 }
            if (outcome == RatingBackfillOutcome.CHECKED) checked++ else transient++
            dao.upsert(RatingBackfillPolicy.next(id, dao.get(id), outcome, nowMs()))
            progress.done++
            progress.currentId = null
        }
        return RatingBackfillBatch(
            total = targets.size,
            checked = checked,
            failed = transient,
            deferred = targets.size - checked - transient,
            timedOut = timedOut,
            failedSources = failedBySource,
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** WorkManager 给出的停止原因（`ListenableWorker.getStopReason()`）→ 给用户看的说明。 */
@Suppress("MagicNumber", "CyclomaticComplexMethod") // 取值来自 WorkInfo.STOP_REASON_* 常量，逐个对应
internal fun describeStopReason(code: Int): String = when (code) {
    1 -> "被应用取消"
    2 -> "被系统让位给其它任务"
    3 -> "超过了系统给后台任务的运行时限"
    4 -> "设备状态变化"
    5 -> "电量低"
    6 -> "不在充电"
    7 -> "网络断开或切换"
    8 -> "设备不再空闲"
    9 -> "存储空间不足"
    10 -> "后台运行配额用完"
    11 -> "系统限制了这个应用的后台运行"
    12 -> "应用进入了待机分组"
    13 -> "被用户停止"
    14 -> "系统正在处理其它事务"
    15 -> "系统预计你很快会打开应用"
    else -> "系统没有给出原因"
}

package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.sync.isTransient
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkMatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 放进协程上下文，统计一次操作里有多少个请求因为网络、限流或服务端错误没拿到结果。
 * 后台补评分据此区分「确实查过了」和「这次没查成、过几小时再试」。
 */
internal class TransientFailureCounter : AbstractCoroutineContextElement(Key) {
    private val failures = AtomicInteger(0)
    private val bySource = java.util.concurrent.ConcurrentHashMap<com.acgcompass.domain.model.SourceId, AppError>()

    val count: Int get() = failures.get()

    /** 各来源最近一次暂时失败的原因（详情页用来在「后台任务」页说明是哪个平台没拿到）。 */
    val failedSources: Map<com.acgcompass.domain.model.SourceId, AppError> get() = bySource.toMap()

    fun record(source: com.acgcompass.domain.model.SourceId? = null, error: AppError? = null) {
        failures.incrementAndGet()
        if (source != null) bySource[source] = error ?: AppError.Network()
    }

    companion object Key : CoroutineContext.Key<TransientFailureCounter>
}

/** 当前协程里有计数器时记一笔暂时失败（网络、限流、服务端错误；查不到、缺字段属于确定的结果）。 */
internal suspend fun recordTransientFailure(error: AppError?, source: com.acgcompass.domain.model.SourceId? = null) {
    if (error == null || error.isTransient()) coroutineContext[TransientFailureCounter]?.record(source, error)
}

/** 跑一次搜索：失败（含异常）记到当前的 [TransientFailureCounter] 并返回空列表；协程取消照常抛出。 */
internal suspend fun safeSearch(
    source: SourceId? = null,
    block: suspend () -> AppResult<List<WorkMatch>>,
): List<WorkMatch> = try {
    when (val result = block()) {
        is AppResult.Success -> result.data
        is AppResult.Failure -> {
            recordTransientFailure(result.error, source)
            emptyList()
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    recordTransientFailure(null, source)
    emptyList()
}

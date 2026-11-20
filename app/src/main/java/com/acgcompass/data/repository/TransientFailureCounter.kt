package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import com.acgcompass.data.sync.isTransient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 放进协程上下文，统计一次操作里有多少个请求因为网络、限流或服务端错误没拿到结果。
 * 后台补评分据此区分「确实查过了」和「这次没查成、过几小时再试」。
 */
internal class TransientFailureCounter : AbstractCoroutineContextElement(Key) {
    private val failures = AtomicInteger(0)

    val count: Int get() = failures.get()

    fun record() {
        failures.incrementAndGet()
    }

    companion object Key : CoroutineContext.Key<TransientFailureCounter>
}

/** 当前协程里有计数器时记一笔暂时失败（网络、限流、服务端错误；查不到、缺字段属于确定的结果）。 */
internal suspend fun recordTransientFailure(error: AppError?) {
    if (error == null || error.isTransient()) coroutineContext[TransientFailureCounter]?.record()
}

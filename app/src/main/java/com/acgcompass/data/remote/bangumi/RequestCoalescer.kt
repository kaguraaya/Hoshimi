package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RequestPriority
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 合并同一时间对同一资源的重复请求，并在 [ttlMillis] 内复用成功结果。
 *
 * 详情页打开时，口味特征和「角色 · Staff」会同时请求同一部作品的人物、角色列表（大番一份就有两百多 KB）；
 * 合并后只下载一次。后台请求还在排队时来了前台请求，前台不等它，自己按前台优先级发出。
 */
internal class RequestCoalescer<T>(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class InFlight<T>(val deferred: Deferred<AppResult<T>>, val priority: RequestPriority)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, InFlight<T>>()
    private val recent = ConcurrentHashMap<String, Pair<Long, T>>()

    suspend fun get(key: String, priority: RequestPriority, fetch: suspend () -> AppResult<T>): AppResult<T> {
        recent[key]?.takeIf { clock() - it.first <= ttlMillis }?.let { return AppResult.Success(it.second) }
        val running = inFlight[key]
        val reuse = running != null &&
            (running.priority == RequestPriority.FOREGROUND || priority == RequestPriority.BACKGROUND)
        val deferred = if (reuse) {
            running!!.deferred
        } else {
            scope.async { fetch() }.also { started ->
                inFlight[key] = InFlight(started, priority)
                started.invokeOnCompletion { inFlight.remove(key, inFlight[key]?.takeIf { it.deferred === started }) }
            }
        }
        val result = deferred.await()
        if (result is AppResult.Success) recent[key] = clock() to result.data
        return result
    }
}

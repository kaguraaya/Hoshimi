package com.acgcompass.data.tasks

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.TaskRunDao
import com.acgcompass.data.local.entity.TaskRunEntity
import com.acgcompass.domain.model.BackgroundTaskKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 记录后台任务的开始、成功和失败，给设置里的「后台任务」页看。
 * 记录本身出错只会被忽略，绝不影响任务。失败原因只写一句概括，不写请求内容、令牌或个人数据。
 */
@Singleton
class TaskRunRecorder @Inject constructor(
    private val dao: TaskRunDao,
) {
    suspend fun started(kind: BackgroundTaskKind, now: Long = System.currentTimeMillis()) = write(kind) {
        it.copy(running = true, lastStartedAt = now, updatedAt = now)
    }

    suspend fun succeeded(kind: BackgroundTaskKind, summary: String? = null, now: Long = System.currentTimeMillis()) =
        write(kind) {
            it.copy(running = false, lastSuccessAt = now, lastSummary = summary ?: it.lastSummary, updatedAt = now)
        }

    /** 被系统暂停、这次还没做完任何一项：只收起「运行中」，不算成功也不算失败（上一次的结果照常显示）。 */
    suspend fun paused(kind: BackgroundTaskKind, now: Long = System.currentTimeMillis()) = write(kind) {
        it.copy(running = false, updatedAt = now)
    }

    suspend fun failed(
        kind: BackgroundTaskKind,
        reason: String,
        summary: String? = null,
        now: Long = System.currentTimeMillis(),
    ) = write(kind) {
        it.copy(
            running = false,
            lastFailureAt = now,
            lastError = reason,
            lastSummary = summary ?: it.lastSummary,
            updatedAt = now,
        )
    }

    /** 记一次结果：成功写说明，失败写原因。 */
    suspend fun <T> finish(kind: BackgroundTaskKind, result: AppResult<T>, summary: (T) -> String? = { null }) {
        when (result) {
            is AppResult.Success -> succeeded(kind, summary(result.data))
            is AppResult.Failure -> failed(kind, describe(result.error))
        }
    }

    /** 包住一次运行：开始、成功、异常都记下；异常照常抛给调用方。 */
    @Suppress("TooGenericExceptionCaught") // 只记录，异常原样抛出
    suspend fun <T> track(kind: BackgroundTaskKind, summary: (T) -> String? = { null }, block: suspend () -> T): T {
        started(kind)
        return try {
            block().also { succeeded(kind, summary(it)) }
        } catch (e: CancellationException) {
            write(kind) { it.copy(running = false, updatedAt = System.currentTimeMillis()) }
            throw e
        } catch (e: Exception) {
            failed(kind, describe(e))
            throw e
        }
    }

    /** 进程被杀后「运行中」不会自己清掉：启动时调用。 */
    suspend fun clearStaleRunning() {
        runCatchingCancellable { dao.clearRunning() }
    }

    private suspend fun write(kind: BackgroundTaskKind, change: (TaskRunEntity) -> TaskRunEntity) {
        try {
            val current = dao.get(kind.key) ?: TaskRunEntity(
                taskKey = kind.key,
                running = false,
                lastStartedAt = null,
                lastSuccessAt = null,
                lastFailureAt = null,
                lastError = null,
                lastSummary = null,
                updatedAt = 0L,
            )
            dao.upsert(change(current))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 记录失败不影响任务本身。
        }
    }

    companion object {
        /** 失败原因的一句话说明（不含请求地址、响应内容）。 */
        fun describe(error: AppError): String = when (error) {
            is AppError.Network -> "网络连不上或超时"
            is AppError.RateLimited -> "请求太频繁被限速，稍后会自动再试"
            is AppError.Server -> "对方服务器出错"
            is AppError.Unauthorized -> "登录已失效，需要重新登录"
            is AppError.NotFound -> "没有找到对应的数据"
            is AppError.FieldMissing -> "返回的数据缺少字段"
            else -> "出错了"
        }

        fun describe(error: Throwable): String = when (error) {
            is java.io.IOException -> "网络连不上或超时"
            else -> "出错了（${error.javaClass.simpleName}）"
        }
    }
}

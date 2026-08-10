package com.acgcompass.core.common.logging

import android.util.Log

/**
 * 日志输出后端抽象。默认实现委托给 [android.util.Log]，便于在测试中替换为内存 sink。
 */
fun interface LogSink {
    /**
     * @param priority 对应 [android.util.Log] 的优先级常量（DEBUG/INFO/WARN/ERROR）。
     */
    fun log(priority: Int, tag: String, message: String, throwable: Throwable?)
}

/** 默认 sink：委托给 Android 平台日志。 */
object AndroidLogSink : LogSink {
    override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.println(priority, tag, message + '\n' + Log.getStackTraceString(throwable))
        } else {
            Log.println(priority, tag, message)
        }
    }
}

/**
 * 脱敏日志器。
 *
 * 在把任何内容交给底层 [LogSink] 之前，都会用 [LogRedactor.redact] 对 **message** 与
 * **throwable 的 message** 做脱敏，确保完整 key / token / secret 绝不进入日志。
 *
 * throwable 本身的堆栈仍由 sink 输出，但其 `message` 已被脱敏包装（见 [redactThrowable]）。
 *
 * @param sink 输出后端，默认 [AndroidLogSink]；测试可注入自定义实现。
 */
class RedactingLogger(private val sink: LogSink = AndroidLogSink) {

    /** DEBUG 级别日志。 */
    fun d(tag: String, message: String, throwable: Throwable? = null) = write(Log.DEBUG, tag, message, throwable)

    /** INFO 级别日志。 */
    fun i(tag: String, message: String, throwable: Throwable? = null) = write(Log.INFO, tag, message, throwable)

    /** WARN 级别日志。 */
    fun w(tag: String, message: String, throwable: Throwable? = null) = write(Log.WARN, tag, message, throwable)

    /** ERROR 级别日志。 */
    fun e(tag: String, message: String, throwable: Throwable? = null) = write(Log.ERROR, tag, message, throwable)

    private fun write(priority: Int, tag: String, message: String, throwable: Throwable?) {
        sink.log(priority, tag, LogRedactor.redact(message), throwable?.let(::redactThrowable))
    }

    /**
     * 递归地把 [throwable] 及其整条 cause 链重建为 message 已脱敏的包装异常。
     *
     * 重建（而非把原异常作为 cause）是必要的：底层 sink 会打印 "Caused by: <原 message>"，
     * 若保留原异常，其未脱敏的 message 仍会泄露。这里复制堆栈以保留排查能力，但每一层 message 都经过脱敏。
     */
    private fun redactThrowable(throwable: Throwable): Throwable {
        val redactedMessage = throwable.message?.let(LogRedactor::redact)
        val redactedCause = throwable.cause?.takeIf { it !== throwable }?.let(::redactThrowable)
        return RedactedThrowable(redactedMessage, redactedCause).apply {
            stackTrace = throwable.stackTrace
        }
    }

    /** message 已脱敏的异常包装；整条 cause 链同样为脱敏后的包装，绝不引用原始异常对象。 */
    private class RedactedThrowable(
        message: String?,
        cause: Throwable?,
    ) : Throwable(message, cause)
}

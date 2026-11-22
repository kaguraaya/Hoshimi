package com.acgcompass.core.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地异常记录：闪退时的堆栈、后台任务里没接住的异常、系统记下的进程退出原因。
 * 只写在应用私有目录里，不上传；后台任务页可以查看、复制，用来排查偶发、难复现的闪退。
 *
 * 写入是同步的普通文件读写（闪退时进程马上要结束，不能交给协程）；任何读写失败都静默忽略，记录本身绝不能再引发异常。
 * 没有调用 [init] 时（例如单元测试）所有操作都不做事。
 */
object CrashLog {
    @Volatile private var file: File? = null

    /** 记录放在 [dir] 下；应用启动时最先调用。 */
    fun init(dir: File) {
        file = File(dir, FILE_NAME)
    }

    /** 记一条：[kind] 如「闪退」，[summary] 是一行说明，[detail] 是堆栈或系统给的详情（只保留前 [MAX_DETAIL_LINES] 行）。 */
    @Synchronized
    fun record(kind: String, summary: String, detail: String = "", at: Long = System.currentTimeMillis()) {
        val target = file ?: return
        runCatchingCancellable {
            val entry =
                Entry(header(at, kind), summary, detail.lines().filter { it.isNotBlank() }.take(MAX_DETAIL_LINES))
            val kept = (parse(target.takeIf { it.exists() }?.readText().orEmpty()) + entry).takeLast(MAX_ENTRIES)
            target.parentFile?.mkdirs()
            target.writeText(kept.joinToString("") { it.render() })
        }
    }

    /** 记一个异常：摘要是「类型：消息」，详情是堆栈（含 cause）。 */
    fun record(kind: String, error: Throwable, thread: String? = null) {
        val where = thread?.let { "（线程 $it）" }.orEmpty()
        record(
            kind,
            "${error.javaClass.name}: ${error.message.orEmpty().take(MAX_MESSAGE)}$where",
            error.stackTraceToString(),
        )
    }

    /** 全部记录，旧的在前。 */
    fun entries(): List<Entry> = runCatchingCancellable {
        file?.takeIf { it.exists() }?.readText()?.let(::parse).orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear() {
        runCatchingCancellable { file?.delete() }
    }

    /** 一条记录：[header] 是「时间 · 类型」，[summary] 一行说明，[detail] 若干行详情。 */
    data class Entry(val header: String, val summary: String, val detail: List<String>) {
        internal fun render(): String = buildString {
            append(SEPARATOR).append(header).append('\n')
            append(summary.replace('\n', ' ')).append('\n')
            detail.forEach { append(it).append('\n') }
        }

        /** 复制出去时的完整文本。 */
        val text: String get() = render()
    }

    internal fun parse(text: String): List<Entry> = text.split(SEPARATOR).filter { it.isNotBlank() }.map { block ->
        val lines = block.lines().filter { it.isNotEmpty() }
        Entry(lines.first(), lines.getOrElse(1) { "" }, lines.drop(2))
    }

    private fun header(at: Long, kind: String): String =
        "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))} · $kind"

    private const val FILE_NAME = "crash-log.txt"
    private const val SEPARATOR = "=== "
    private const val MAX_ENTRIES = 10
    private const val MAX_DETAIL_LINES = 40
    private const val MAX_MESSAGE = 300
}

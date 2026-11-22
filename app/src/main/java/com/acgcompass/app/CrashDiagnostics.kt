package com.acgcompass.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.acgcompass.core.common.CrashLog
import com.acgcompass.core.common.runCatchingCancellable
import java.io.File

/**
 * 闪退诊断，结果都写进 [CrashLog]（只在本机）：
 * - 未捕获的异常：记下堆栈后照常交给系统处理（该闪退还是闪退，只是留了记录）；
 * - 启动时读系统记下的上几次进程退出原因（Android 11 起）：闪退、无响应、内存不足被结束等，
 *   包括应用自己来不及记录的情况（原生代码崩溃、无响应、被系统结束）。
 */
internal object CrashDiagnostics {
    fun install(context: Context) {
        CrashLog.init(File(context.filesDir, DIR))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record("闪退", error, thread.name)
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读系统记下的进程退出原因；只记上次读过之后的、值得排查的那几种。 */
    fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatchingCancellable { collectSinceLastRead(context) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectSinceLastRead(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(context.filesDir, "$DIR/$SEEN_FILE")
        val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }.forEach { info ->
            val label = exitReasonLabel(info.reason) ?: return@forEach
            val foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val where = if (foreground) "前台" else "后台"
            val detail = buildString {
                info.description?.let { append(it).append('\n') }
                if (info.reason == ApplicationExitInfo.REASON_ANR) append(traceHead(info))
            }
            CrashLog.record("系统记录：$label", "退出时应用在$where，占用内存约 ${info.pss / KB} MB", detail, at = info.timestamp)
        }
        exits.maxOfOrNull { it.timestamp }?.let {
            seenFile.parentFile?.mkdirs()
            seenFile.writeText(it.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun traceHead(info: ApplicationExitInfo): String = runCatchingCancellable {
        info.traceInputStream?.bufferedReader()?.useLines { lines -> lines.take(TRACE_LINES).joinToString("\n") }
    }.getOrNull().orEmpty()

    private const val DIR = "diagnostics"
    private const val SEEN_FILE = "exit-seen.txt"
    private const val MAX_EXITS = 10
    private const val TRACE_LINES = 40
    private const val KB = 1024
}

/**
 * 值得记下的系统退出原因（[ApplicationExitInfo] 的 reason）；自己退出、用户手动结束、权限变化、升级等正常情况返回 null。
 */
internal fun exitReasonLabel(reason: Int): String? = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "闪退"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "闪退（原生代码）"
    ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "占用资源过多被系统结束"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
    ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程结束"
    ApplicationExitInfo.REASON_OTHER -> "被系统结束（其它原因）"
    else -> null
}

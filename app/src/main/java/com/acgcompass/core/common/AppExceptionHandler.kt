package com.acgcompass.core.common

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 应用级协程作用域（同步、画像重建、回测、标签分类、启动初始化等）里没被接住的异常：记下来，不让整个应用闪退。
 * 这些任务都在后台跑，失败了下次还会再试；为了它们让用户正在看的页面直接退出，代价太大。
 * 取消（CancellationException）不会到这里。
 */
val AppExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    runCatchingCancellable { Log.e("Hoshimi", "后台任务出错", error) }
    CrashLog.record("后台任务出错（已拦下，没有闪退）", error, Thread.currentThread().name)
}

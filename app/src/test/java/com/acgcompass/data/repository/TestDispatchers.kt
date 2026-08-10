package com.acgcompass.data.repository

import com.acgcompass.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers

/**
 * 测试用调度器：全部走 [Dispatchers.Unconfined]，立即在调用线程执行。共享于本包下的仓库单元测试。
 *
 * 说明：刻意不使用 `UnconfinedTestDispatcher()`——后者会创建自己的 `TestCoroutineScheduler`，与
 * `runTest {}` 自带的调度器不同，导致仓库内 `flowOn` / `combine` 收集时抛出
 * 「Detected use of different schedulers」。使用真实的 Unconfined 调度器即可避免该冲突，
 * 且对这些「立即执行」的纯内存 Fake 测试行为等价。
 */
internal class TestDispatchers : DispatcherProvider {
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val main = Dispatchers.Unconfined
}

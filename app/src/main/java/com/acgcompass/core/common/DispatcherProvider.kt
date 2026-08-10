package com.acgcompass.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器抽象，便于在测试中替换为 `StandardTestDispatcher` / `UnconfinedTestDispatcher`，
 * 避免在生产代码中直接硬编码 [Dispatchers]（提升可测性，支撑 测试矩阵）。
 */
interface DispatcherProvider {
    /** I/O 密集型工作（网络、磁盘、Room）。 */
    val io: CoroutineDispatcher

    /** CPU 密集型工作（解析、归一化、聚合计算）。 */
    val default: CoroutineDispatcher

    /** 主线程 / UI 更新。 */
    val main: CoroutineDispatcher
}

/** 生产环境默认实现，委托给标准 [Dispatchers]。 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}

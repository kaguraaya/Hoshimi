package com.acgcompass.domain.undo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一次可以撤销的移除操作：[message] 显示在撤销提示条上，[batchId] 是回收站里这次操作的批次。
 */
data class UndoRequest(val message: String, val batchId: String)

/**
 * 撤销提示的事件通道：移除操作完成后 [offer]，应用根部的提示条收集并显示，用户点「撤销」时按批次恢复。
 * 新请求会顶替还没消失的旧提示（旧操作仍可在回收站里恢复）。
 */
@Singleton
class UndoController @Inject constructor() {
    private val _requests = MutableSharedFlow<UndoRequest>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests: SharedFlow<UndoRequest> = _requests.asSharedFlow()

    fun offer(message: String, batchId: String) {
        _requests.tryEmit(UndoRequest(message, batchId))
    }
}

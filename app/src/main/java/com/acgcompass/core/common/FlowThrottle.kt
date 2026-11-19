package com.acgcompass.core.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * 第一个值立即发出；之后 [periodMillis] 内连续到来的值只发最后一个。
 * 用在观察整张表的流上：后台批量写入时一秒内触发几十次变更，页面只需要按最新数据重算一两次。
 */
fun <T> Flow<T>.throttleLatest(periodMillis: Long, clock: () -> Long = System::currentTimeMillis): Flow<T> =
    channelFlow {
        var lastSent = Long.MIN_VALUE
        collectLatest { value ->
            if (lastSent != Long.MIN_VALUE) {
                val wait = lastSent + periodMillis - clock()
                if (wait > 0) delay(wait)
            }
            send(value)
            lastSent = clock()
        }
    }

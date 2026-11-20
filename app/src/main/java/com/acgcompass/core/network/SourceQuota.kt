package com.acgcompass.core.network

import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** 查询某个数据源的本地限速额度是否紧张（剩余不到 [TIGHT_HEADROOM] 或处于暂停）。 */
@Singleton
class SourceQuota @Inject constructor(
    private val registry: RateLimiterRegistry,
) {
    fun isTight(source: NetworkSource): Boolean = (registry.limiterFor(source)?.headroom() ?: 1.0) < TIGHT_HEADROOM

    private companion object {
        /** 剩余额度低于这个比例就算紧张，留给用户正在等的其它请求。 */
        const val TIGHT_HEADROOM = 0.25
    }
}

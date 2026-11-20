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

    /** 这个数据源的后台请求还要冷却多久（毫秒）：撞上限流后的退避期内，后台请求发不出去。 */
    fun backgroundCooldownMillis(source: NetworkSource): Long =
        registry.limiterFor(source)?.backgroundCooldownMillis() ?: 0L

    private companion object {
        /** 剩余额度低于这个比例就算紧张，留给用户正在等的其它请求。 */
        const val TIGHT_HEADROOM = 0.25
    }
}

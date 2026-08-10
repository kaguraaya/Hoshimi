package com.acgcompass.data.remote.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI provider 工厂 / 注册表（design「AiProvider 抽象」）。
 *
 * 封装 Dagger 多绑定得到的 `Map<ProviderId, AiProvider>`，供上层 `AiEngine`
 * 按用户当前选择的 [ProviderId] 解析出对应实现，避免直接依赖具体 provider 类。
 *
 * @property providers 由 `AiModule` 以 `@IntoMap @ProviderIdKey(...)` 聚合的全部实现。
 *                     `@JvmSuppressWildcards` 确保注入点的泛型不被擦除为通配符。
 */
@Singleton
class AiProviderRegistry @Inject constructor(
    private val providers: Map<ProviderId, @JvmSuppressWildcards AiProvider>,
) {

    /** 返回指定 [id] 的 provider；未注册时返回 `null`（调用方决定如何兜底，不崩溃）。 */
    fun get(id: ProviderId): AiProvider? = providers[id]

    /**
     * 返回指定 [id] 的 provider；未注册时抛出 [IllegalStateException]。
     *
     * 仅在调用方已确认该 provider 必然存在时使用（所有 [ProviderId] 在 `AiModule` 中均已绑定）。
     */
    fun require(id: ProviderId): AiProvider = providers[id] ?: error("No AiProvider registered for $id")

    /** 全部已注册的 [ProviderId]（用于设置页枚举可选项等）。 */
    fun availableProviders(): Set<ProviderId> = providers.keys
}

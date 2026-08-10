package com.acgcompass.data.remote.ai

import dagger.MapKey

/**
 * Dagger 多绑定（multibinding）map 的键注解：以 [ProviderId] 为键聚合所有 [AiProvider]
 * 实现（design「AiProvider 抽象」）。
 *
 * 配合 `@IntoMap` 使用，使 `Map<ProviderId, AiProvider>` 可被 [AiProviderRegistry] 注入，
 * 在运行时按用户选择的 provider 解析出对应实现。
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class ProviderIdKey(val value: ProviderId)

package com.acgcompass.core.network

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.SourceId

/**
 * 单源取数器。
 *
 * 描述「如何从某一个数据源取得本次请求的数据」。这是一个挂起函数：
 *
 * - 返回 `null` 表示该源**当前不可用**（禁用 / 未配置凭据 / 不支持该请求），
 *   编排器将**跳过**该源继续尝试下一个。
 * - 返回 [AppResult.Success]（携带 [SourceData]）表示取数成功——即便其中含字段级缺失，
 *   也视为命中（字段缺失只在 [SourceData.missingFields] 中标记，UI 显示「暂无数据」）。
 * - 返回 [AppResult.Failure] 表示该源本次调用失败（鉴权 / 限流 / 超时 / 服务错误等），
 *   编排器记录其错误并降级到下一个源。
 *
 * 取数器内部不应抛出未捕获异常；若可能抛出，应使用
 * [com.acgcompass.core.common.runCatchingApp] 包装为 [AppResult]。
 */
fun interface SourceFetcher<out T> {
    suspend fun fetch(): AppResult<SourceData<@UnsafeVariance T>>?
}

/**
 * 一次降级取数请求。
 *
 * 把「要取什么」抽象为一组**按源注册**的取数器：[fetchers] 以 [SourceId] 为键。
 *
 * - 值为 `null` 或键缺省：该源在本次请求中**不参与**（如未实现 / 显式禁用）。
 * - 值为 [SourceFetcher]：编排器在轮到该源时调用之；取数器自身亦可返回 `null`
 *   表示运行时不可用（未配置凭据等）。
 *
 * 请求本身**不决定**尝试顺序——顺序由编排器的固定回退顺序
 * [DataSourceOrchestrator.FALLBACK_ORDER] 决定，从而保证降级顺序确定。
 *
 * @param fetchers 各源取数器注册表（缺省 / `null` 表示该源不参与）。
 * @param description 可选的请求描述，仅用于日志 / 调试（不得含凭据明文）。
 */
class SourceRequest<out T>(
    val fetchers: Map<SourceId, SourceFetcher<T>?>,
    val description: String? = null,
) {
    /** 返回在 [sourceId] 上注册的取数器；未注册返回 `null`（该源不参与本次请求）。 */
    fun fetcherFor(sourceId: SourceId): SourceFetcher<T>? = fetchers[sourceId]

    companion object {
        /**
         * 便捷构造：以可变参数登记各源取数器，自动忽略 `null` 取数器对应的源。
         *
         * 例：`SourceRequest.of(SourceId.BANGUMI to bgmFetcher, SourceId.ANILIST to aniFetcher)`
         */
        fun <T> of(vararg fetchers: Pair<SourceId, SourceFetcher<T>?>, description: String? = null): SourceRequest<T> =
            SourceRequest(fetchers.toMap(), description)
    }
}

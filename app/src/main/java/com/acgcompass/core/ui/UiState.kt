package com.acgcompass.core.ui

import com.acgcompass.core.common.AppError

/**
 * 统一页面状态（Page_State）。每个 ViewModel 暴露 `StateFlow<UiState<T>>`，
 * 由 `StateScaffold` 组件统一渲染七态，保证全局状态一致。
 *
 * 状态语义：
 * - [Loading]：加载中。
 * - [Empty]：内容为空，附带「下一步」按钮（Cta），引导用户操作。
 * - [Error]：发生错误，渲染错误卡片（含原因/下一步/重试/文档）。
 * - [Unauthorized]：未授权（如 401/403），引导用户配置或重新授权。
 * - [RateLimited]：被数据源限流（如 429），提示稍后重试。
 * - [NoNetwork]：无网络或请求超时。
 * - [PartialMissing]：数据成功但部分字段缺失，字段级显示「暂无数据」，不隐藏整块。
 * - [Success]：数据加载成功。
 *
 * 使用 `out T` 协变，使 [Loading]/[Empty]/[Error]/[Unauthorized]/[RateLimited]/[NoNetwork]
 * 这些不携带数据的状态可安全复用 `UiState<Nothing>`。
 *
 */
sealed interface UiState<out T> {

    /** 加载中。 */
    data object Loading : UiState<Nothing>

    /** 空状态：附带引导用户进行下一步操作的 [Cta]。 */
    data class Empty(val cta: Cta) : UiState<Nothing>

    /** 错误状态：携带结构化 [AppError]，由错误卡片渲染。 */
    data class Error(val err: AppError) : UiState<Nothing>

    /** 未授权（401/403 等）。 */
    data object Unauthorized : UiState<Nothing>

    /** 被限流（429 等）。 */
    data object RateLimited : UiState<Nothing>

    /** 无网络 / 请求超时。 */
    data object NoNetwork : UiState<Nothing>

    /** 数据成功但部分字段缺失：字段级显示「暂无数据」。 */
    data class PartialMissing<out T>(val data: T) : UiState<T>

    /** 数据加载成功。 */
    data class Success<out T>(val data: T) : UiState<T>
}

/**
 * 空状态下的「下一步」操作提示。
 *
 * @param label 按钮显示文案（用户可读）。
 * @param action 操作标识或路由提示（如导航 route 或动作 key），供 UI 决定点击后的行为。
 */
data class Cta(
    val label: String,
    val action: String,
)

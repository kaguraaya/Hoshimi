package com.acgcompass.core.common

/**
 * 统一错误类型体系。
 *
 * 所有远程 / AI / 解析调用最终都映射为一个 [AppError]，再由表现层映射到 `UiState` 与
 * `ErrorCard`（简短原因 + 下一步 + 重试 + 查看文档）。
 *
 * 每个子类型 **必须** 携带四要素，因此它们都是 [AppError] 接口的抽象成员，由编译器强制
 * 覆写以保证错误信息映射完整。
 *
 * （错误卡片四要素）。
 */
sealed interface AppError {
    /** 简短、用户可读的原因（不含技术堆栈、不含凭据明文）。 */
    val cause: String

    /** 给用户的下一步行动建议。 */
    val nextStep: String

    /** 是否向用户提供「重试」动作。 */
    val retryable: Boolean

    /** 「查看文档」入口；无对应文档时为 null。 */
    val docUrl: String?

    /** 超时 / 无网络 / 连接失败 → `UiState.NoNetwork`。 */
    data class Network(
        override val cause: String = "网络连接失败或请求超时",
        override val nextStep: String = "请检查网络后重试",
        override val retryable: Boolean = true,
        override val docUrl: String? = null,
    ) : AppError

    /** 401 / 403 → `UiState.Unauthorized`。 */
    data class Unauthorized(
        override val cause: String = "授权失效或凭据无效",
        override val nextStep: String = "请到设置页检查并重新填写凭据",
        override val retryable: Boolean = false,
        override val docUrl: String? = null,
    ) : AppError

    /** HTTP 429 maps to `UiState.RateLimited`. */
    data class RateLimited(
        override val cause: String = "请求过于频繁，已被数据源限流",
        override val nextStep: String = "请稍后再试",
        override val retryable: Boolean = true,
        override val docUrl: String? = null,
    ) : AppError

    /** 404。 */
    data class NotFound(
        override val cause: String = "未找到对应数据",
        override val nextStep: String = "请确认输入或尝试其他数据源",
        override val retryable: Boolean = false,
        override val docUrl: String? = null,
    ) : AppError

    /** 500 / 未捕获异常兜底。 */
    data class Server(
        override val cause: String = "数据源服务暂时不可用",
        override val nextStep: String = "请稍后重试",
        override val retryable: Boolean = true,
        override val docUrl: String? = null,
    ) : AppError

    /** 字段缺失 / 空结果 → `UiState.PartialMissing`，UI 显示「暂无数据」。 */
    data class FieldMissing(
        override val cause: String = "部分数据暂无",
        override val nextStep: String = "可切换数据源或稍后重试",
        override val retryable: Boolean = true,
        override val docUrl: String? = null,
    ) : AppError

    /** AI 输出 JSON / 结构损坏。 */
    data class AiMalformed(
        override val cause: String = "AI 返回结果结构异常",
        override val nextStep: String = "可重新生成或更换模型",
        override val retryable: Boolean = true,
        override val docUrl: String? = null,
    ) : AppError

    /** 检出剧透 → 抽象化处理。 */
    data class Spoiler(
        override val cause: String = "内容可能包含剧透，已做保护处理",
        override val nextStep: String = "已抽象化展示，无需操作",
        override val retryable: Boolean = false,
        override val docUrl: String? = null,
    ) : AppError
}

/**
 * 返回一个保留原类型与语义、但替换 [AppError.cause]（简短原因）的副本，用于注入更具体的诊断信息
 * （如 HTTP 状态码和服务端错误体），供 UI 展示可诊断的失败详情。
 */
fun AppError.withCause(cause: String): AppError = when (this) {
    is AppError.Network -> copy(cause = cause)
    is AppError.Unauthorized -> copy(cause = cause)
    is AppError.RateLimited -> copy(cause = cause)
    is AppError.NotFound -> copy(cause = cause)
    is AppError.Server -> copy(cause = cause)
    is AppError.FieldMissing -> copy(cause = cause)
    is AppError.AiMalformed -> copy(cause = cause)
    is AppError.Spoiler -> copy(cause = cause)
}

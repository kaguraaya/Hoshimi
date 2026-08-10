package com.acgcompass.data.remote.ai

/**
 * AI 服务 provider 抽象（design「AiProvider 抽象」）。
 *
 * 一个 [AiProvider] 封装「如何把 provider 无关的 [AiRequest] 发送给某个具体 AI 服务并取回
 * [AiRawResponse]」。Base URL / 模型名 / API Key 等 **均来自用户配置**（运行时经
 * [AiCredentialSource] 从 `CredentialStore` 读取），代码与资源中**绝不**包含任何 key
 * 。
 *
 * 错误约定：调用失败时抛出携带 [com.acgcompass.core.common.AppError] 的
 * [com.acgcompass.core.common.AppErrorException]（如未配置凭据 → `Unauthorized`、限流 →
 * `RateLimited`、网络 → `Network`），由上层 `AiEngine` 在 `runCatchingApp` 中统一兜底，
 * 保证不崩溃。本接口**不**负责 schema 校验、修复二次请求或剧透过滤。
 */
interface AiProvider {

    /** 该 provider 的稳定标识（用于 DI 多绑定 map 的键）。 */
    val id: ProviderId

    /**
     * 执行一次补全。
     *
     * @param req provider 无关的请求描述。
     * @return 原始补全结果（文本/JSON 内容 + 结束原因 + 用量）。
     * @throws com.acgcompass.core.common.AppErrorException 配置缺失 / HTTP 错误 / 网络异常时抛出对应 [com.acgcompass.core.common.AppError]。
     */
    suspend fun complete(req: AiRequest): AiRawResponse

    /**
     * 是否支持服务端结构化输出（`response_format` / JSON Schema / `responseSchema`）。
     *
     * 返回 `false` 时，上层应把 schema 约束写入提示词并在收到结果后自行校验。
     */
    fun supportsStructuredOutput(): Boolean
}

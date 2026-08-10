package com.acgcompass.domain.ai

import com.acgcompass.core.common.AppError
import com.acgcompass.domain.model.AiResult

/**
 * AI 调用管线契约。
 *
 * 职责（由 `data/remote/ai/AiEngineImpl` 实现）：
 * 1. 组装系统提示词（**强制剧透保护**）+ 目标 JSON schema，调用用户所选 provider 对应的
 *    `AiProvider`（凭据来自 `CredentialStore`，代码零密钥）。
 * 2. 校验输出 JSON：缺字段 / JSON 损坏时发起一次「修复成指定格式」二次请求。
 * 3. 仍失败 → 低置信兜底，**不编造**。
 * 4. 剧透过滤 post-pass：净化被禁用的剧透 token，抽象化处理（支撑 ）。
 * 5. 产出携带 `generator=AI`、`confidence`、`dataSources`、`generatedAt` 的领域 [AiResult]。
 *
 * 异常安全：所有实现都不得向调用方抛出裸异常，一律以 [AiRunResult] 的 `Failure` / `NotConfigured`
 * / `LowConfidence` 表达。
 */
interface AiEngine {

    /**
     * 执行一类 AI 任务。
     *
     * @param task 任务描述（类型 + 输入 + 目标输出类型）。
     * @param options 运行选项（成本确认 / 仅分析摘要 / provider 与模型覆盖等）。
     * @return [AiRunResult]：成功（强类型输出 + 可缓存的领域结果）、低置信兜底、未配置、或失败。
     */
    suspend fun <T> run(task: AiTask<T>, options: AiRunOptions = AiRunOptions()): AiRunResult<T>

    /**
     * 估算一次任务的成本区间。
     *
     * 仅基于输入文本与任务的输出上限给出 **token 区间**（不臆造任何货币定价 —— 定价随 provider /
     * 模型而异，由用户在所选服务侧自行知晓）。UI 可据此在触发前展示「估计成本」并请求确认，
     * 同时提示可「仅分析摘要」以降低成本。
     */
    fun estimateCost(task: AiTask<*>, options: AiRunOptions = AiRunOptions()): CostRange
}

/**
 * 一次 AI 调用的运行选项。
 *
 * @property confirmed            用户是否已确认成本（false 时引擎返回 [AiRunResult.NeedsConfirmation]，不发请求）。
 * @property analyzeSummariesOnly 是否仅分析摘要（截断超长输入以降低成本）。
 * @property model                覆盖模型名；为 `null` 时回落到凭据中的默认模型。
 * @property temperature          采样温度（结构化任务建议偏低）。
 * @property maxOutputTokens      输出 token 上限；为 `null` 时使用各任务默认上限。
 */
data class AiRunOptions(
    val confirmed: Boolean = true,
    val analyzeSummariesOnly: Boolean = false,
    val model: String? = null,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxOutputTokens: Int? = null,
) {
    companion object {
        /** 结构化任务默认温度（偏低以提升可解析性）。 */
        const val DEFAULT_TEMPERATURE: Double = 0.2
    }
}

/**
 * 估算的成本区间。以 token 表达，不含货币金额。
 *
 * @property minTokens             乐观估计的总 token 数（输入 + 输出下限）。
 * @property maxTokens             保守估计的总 token 数（输入 + 输出上限）。
 * @property summaryOnlyAvailable  该任务是否支持「仅分析摘要」以进一步降本。
 */
data class CostRange(
    val minTokens: Int,
    val maxTokens: Int,
    val summaryOnlyAvailable: Boolean,
)

/**
 * AI 调用结果。
 *
 * 除 [Failure] 与 [NeedsConfirmation] 外均携带可写入 `AI_RESULT` 缓存的领域 [AiResult]。
 *
 * @param T 任务的固定输出类型。
 */
sealed interface AiRunResult<out T> {

    /** 成功：携带强类型输出 [payload] 与可缓存的领域 [result]（`generator=AI`）。 */
    data class Success<T>(val payload: T, val result: AiResult) : AiRunResult<T>

    /**
     * 低置信兜底：修复二次请求后仍无法得到合规结构，**不编造**。
     *
     * [result] 的 `confidence` 为 `0f`，`payloadJson` 保留最后一次原始内容（便于诊断 / 展示「资料不足」）。
     */
    data class LowConfidence(val result: AiResult, val error: AppError) : AiRunResult<Nothing>

    /**
     * 未配置 AI 凭据。调用方应据此回退到本地规则引擎或显示「未配置」。
     */
    data object NotConfigured : AiRunResult<Nothing>

    /**
     * 成本未确认：[options.confirmed][AiRunOptions.confirmed] 为 false 时返回，
     * 携带 [estimate] 供 UI 展示并请求确认；引擎**不**发起任何网络请求。
     */
    data class NeedsConfirmation(val estimate: CostRange) : AiRunResult<Nothing>

    /** 其它失败（网络 / 限流 / 服务错误等），携带领域 [error]。 */
    data class Failure(val error: AppError) : AiRunResult<Nothing>
}

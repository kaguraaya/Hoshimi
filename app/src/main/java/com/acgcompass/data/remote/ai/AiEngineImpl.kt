package com.acgcompass.data.remote.ai

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.remote.ai.prompt.AiTaskSpec
import com.acgcompass.data.remote.ai.prompt.AiTaskSpecs
import com.acgcompass.data.remote.ai.prompt.SpoilerGuard
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.CostRange
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.AiResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [AiEngine] 的生产实现：组装提示词 + schema → 调用 provider → 校验 / 修复 / 低置信兜底 → 剧透过滤
 * （design「调用管线」）。
 *
 * 全程不抛裸异常：所有 provider 调用经 [runCatchingApp] 兜底为领域错误，并映射为 [AiRunResult]
 * 的相应分支。未配置凭据（provider 抛 `Unauthorized`）映射为
 * [AiRunResult.NotConfigured]，供调用方回退本地规则引擎。
 */
@Singleton
class AiEngineImpl @Inject constructor(
    private val registry: AiProviderRegistry,
    private val providerSelector: AiProviderSelector,
    private val json: Json,
) : AiEngine {

    override suspend fun <T> run(task: AiTask<T>, options: AiRunOptions): AiRunResult<T> {
        val spec = AiTaskSpecs.specFor(task)
        // 放宽 AI 结构化校验：用任务 schema 对模型输出做「类型就近矫正」，再交强类型解析（见 AiJsonCoercion）。
        val schema = (spec.responseFormat as? AiResponseFormat.JsonSchema)?.schema

        // 成本确认：未确认则只返回估算，不发起任何请求。
        if (!options.confirmed) {
            return AiRunResult.NeedsConfirmation(estimateCost(task, options))
        }

        val provider = registry.get(providerSelector.selected())
            ?: return AiRunResult.Failure(AppError.Server(cause = "未注册可用的 AI 服务"))

        val content = prepareContent(task, options)

        // 首次请求按「结构化 → JSON 对象 → 纯文本」顺序协商输出格式。
        // 部分 OpenAI 兼容端点不支持 json_schema 或 json_object，需逐级降级。
        val firstRaw = when (val r = requestWithFormatFallback(provider, spec, content, options)) {
            is ProviderCall.Ok -> r.raw.content
            is ProviderCall.NotConfigured -> return AiRunResult.NotConfigured
            is ProviderCall.Error -> return AiRunResult.Failure(r.error)
        }

        parseValidatedScrubbed(task, firstRaw, schema)?.let { return it }

        // ---- 缺字段 / JSON 损坏 → 「修复成指定格式」二次请求 ----
        val repairRaw = when (
            val r = callProvider(provider, repairRequest(spec, firstRaw, options))
        ) {
            is ProviderCall.Ok -> r.raw.content
            is ProviderCall.NotConfigured -> return AiRunResult.NotConfigured
            // 修复请求失败：保留首次内容作为低置信兜底（不编造）。
            is ProviderCall.Error -> return lowConfidence(task, firstRaw, r.error)
        }

        parseValidatedScrubbed(task, repairRaw, schema)?.let { return it }

        // ---- 仍失败 → 低置信兜底，不编造 ----
        return lowConfidence(task, repairRaw, AppError.AiMalformed())
    }

    override fun estimateCost(task: AiTask<*>, options: AiRunOptions): CostRange {
        val spec = AiTaskSpecs.specFor(task)
        val content = prepareContent(task, options)
        val inputChars = spec.systemPrompt.length + content.length
        // CJK 偏多：保守按 ~2 字符/token（高估）与 ~4 字符/token（低估）给出区间，不臆造货币定价。
        val minInputTokens = inputChars / HIGH_CHARS_PER_TOKEN
        val maxInputTokens = ceil(inputChars.toDouble() / LOW_CHARS_PER_TOKEN).toInt()
        val outTokens = options.maxOutputTokens ?: spec.maxOutputTokens
        return CostRange(
            minTokens = minInputTokens + outTokens / 4,
            maxTokens = maxInputTokens + outTokens,
            summaryOnlyAvailable = task is AiTask.SpoilerRadar && task.summarizable,
        )
    }

    // ---- 内部：输入准备 ----

    /** 「仅分析摘要」时截断超长输入以降本。 */
    private fun prepareContent(task: AiTask<*>, options: AiRunOptions): String {
        val raw = task.userContent
        if (!options.analyzeSummariesOnly || raw.length <= SUMMARY_CHAR_LIMIT) return raw
        return raw.take(SUMMARY_CHAR_LIMIT) + "\n…（已截断，仅分析摘要）"
    }

    private fun firstRequest(
        spec: AiTaskSpec,
        content: String,
        options: AiRunOptions,
        format: AiResponseFormat = spec.responseFormat,
    ) = AiRequest(
        systemPrompt = spec.systemPrompt,
        userContent = content,
        model = options.model.orEmpty(),
        temperature = options.temperature,
        responseFormat = format,
        maxTokens = options.maxOutputTokens ?: spec.maxOutputTokens,
    )

    /**
     * 按「spec 指定格式 → json_object → 纯文本」降级序列发起首个请求。
     *
     * 仅在错误「可能由输出格式不被支持」（Server/AiMalformed/FieldMissing，多为 400）时才降级重试；
     * 鉴权 / 限流 / 网络类错误立即返回，不浪费额度。
     */
    private suspend fun requestWithFormatFallback(
        provider: AiProvider,
        spec: AiTaskSpec,
        content: String,
        options: AiRunOptions,
    ): ProviderCall {
        val formats = listOf(spec.responseFormat, AiResponseFormat.JsonObject, AiResponseFormat.Text).distinct()
        var lastError: ProviderCall.Error? = null
        for (format in formats) {
            when (val r = callProvider(provider, firstRequest(spec, content, options, format))) {
                is ProviderCall.Ok -> return r
                is ProviderCall.NotConfigured -> return r
                is ProviderCall.Error -> {
                    if (!isFormatNegotiable(r.error)) return r
                    lastError = r
                }
            }
        }
        return lastError ?: ProviderCall.Error(AppError.Server())
    }

    /** 该错误是否可能由不支持输出格式引起，从而值得降级重试。 */
    private fun isFormatNegotiable(error: AppError): Boolean = when (error) {
        is AppError.Server, is AppError.AiMalformed, is AppError.FieldMissing -> true
        else -> false
    }

    private fun repairRequest(spec: AiTaskSpec, malformed: String, options: AiRunOptions) = AiRequest(
        systemPrompt = AiTaskSpecs.repairPrompt(spec.systemPrompt),
        userContent = buildString {
            appendLine("请把下面的内容修复为符合要求的合法 JSON（仅输出 JSON 本身）：")
            append(malformed)
        },
        model = options.model.orEmpty(),
        temperature = options.temperature,
        // 修复时强制 JSON 对象输出（兼容不支持 json_schema 的端点）。
        responseFormat = AiResponseFormat.JsonObject,
        maxTokens = options.maxOutputTokens ?: spec.maxOutputTokens,
    )

    // ---- 内部：provider 调用与错误归一 ----

    private sealed interface ProviderCall {
        data class Ok(val raw: AiRawResponse) : ProviderCall
        data object NotConfigured : ProviderCall
        data class Error(val error: AppError) : ProviderCall
    }

    private suspend fun callProvider(provider: AiProvider, req: AiRequest): ProviderCall =
        when (val result = runCatchingApp { provider.complete(req) }) {
            is AppResult.Success -> ProviderCall.Ok(result.data)
            is AppResult.Failure ->
                // 未配置 / 凭据无效 → 让调用方回退本地规则引擎。
                if (result.error is AppError.Unauthorized) {
                    ProviderCall.NotConfigured
                } else {
                    ProviderCall.Error(result.error)
                }
        }

    // ---- 内部：校验 + 剧透过滤 + 解析 ----

    /**
     * 对 [rawContent] 做：JSON 抽取 → 合法性 / 必需字段校验 → 剧透过滤 post-pass → 强类型解析。
     *
     * @return 成功时返回 [AiRunResult.Success]；任一步失败返回 `null`（由调用方触发修复 / 兜底）。
     */
    private fun <T> parseValidatedScrubbed(
        task: AiTask<T>,
        rawContent: String,
        schema: JsonObject?,
    ): AiRunResult.Success<T>? {
        val jsonText = extractJsonText(rawContent)
        val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return null
        val obj = coerceTopLevelToObject(element, schema, task.requiredFields) ?: return null
        if (!hasAllRequired(obj, task.requiredFields)) return null

        // 剧透过滤：净化所有字符串叶子（支撑 ）。
        val scrubbed = SpoilerGuard.scrubJson(obj)
        // 放宽 AI 结构化校验：依 schema 把数值/数组/字符串等类型偏差就近矫正，避免可用响应因小偏差被判失败。
        val normalized = if (schema != null) AiJsonCoercion.coerce(scrubbed, schema) else scrubbed
        val normalizedText = json.encodeToString(JsonElement.serializer(), normalized)
        val payload = runCatching { json.decodeFromString(task.serializer, normalizedText) }
            .getOrNull() ?: return null

        return AiRunResult.Success(
            payload = payload,
            result = aiResult(
                task = task,
                payloadJson = normalizedText,
                confidence = extractConfidence(normalized),
            ),
        )
    }

    private fun <T> lowConfidence(task: AiTask<T>, lastRaw: String, error: AppError): AiRunResult.LowConfidence {
        // 不编造：保留最后一次原始内容（已做剧透过滤），置信度 0。
        val safeText = SpoilerGuard.scrubText(lastRaw)
        return AiRunResult.LowConfidence(
            result = aiResult(task = task, payloadJson = safeText, confidence = 0f),
            error = error,
        )
    }

    private fun <T> aiResult(task: AiTask<T>, payloadJson: String, confidence: Float): AiResult = AiResult(
        id = UUID.randomUUID().toString(),
        workId = task.workId,
        taskType = task.type,
        generator = AiGenerator.AI,
        payloadJson = payloadJson,
        confidence = confidence,
        dataSources = task.dataSources,
        generatedAt = System.currentTimeMillis(),
    )

    private fun hasAllRequired(obj: JsonObject, required: List<String>): Boolean = required.all { key ->
        val value = obj[key]
        value != null && value != JsonNull
    }

    /**
     * 顶层结构归一：把模型输出的顶层元素统一为 [JsonObject]。
     *
     * 部分 provider 在 `response_format=json_object` 下仍会返回顶层 JSON 数组
     * `[{...},{...}]`，而本项目所有结构化任务的目标类型都是「含单个数组字段的对象」（如 TagClassify 的
     * `{"items":[...]}`）。此处按任务 schema 的数组属性名把裸数组包裹成对象；对象输入保持不变。
     */
    private fun coerceTopLevelToObject(
        element: JsonElement,
        schema: JsonObject?,
        requiredFields: List<String>,
    ): JsonObject? {
        (element as? JsonObject)?.let { return it }
        val array = element as? JsonArray ?: return null
        val field = arrayFieldName(schema, requiredFields) ?: return null
        return JsonObject(mapOf(field to array))
    }

    /**
     * 推断「裸数组应包裹进哪个字段」：优先取 schema 中唯一的 array 类型属性；多个时取其中的必需字段；
     * 无 schema 时回退到唯一的必需字段。无法确定则返回 null（不猜测，交由上层判失败）。
     */
    private fun arrayFieldName(schema: JsonObject?, requiredFields: List<String>): String? {
        val props = schema?.get("properties") as? JsonObject
        if (props != null) {
            val arrayProps = props.filter { (_, v) ->
                ((v as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "array"
            }.keys
            when {
                arrayProps.size == 1 -> return arrayProps.first()
                arrayProps.size > 1 -> arrayProps.firstOrNull { it in requiredFields }?.let { return it }
            }
        }
        return requiredFields.singleOrNull()
    }

    private fun extractConfidence(element: JsonElement): Float {
        val obj = element as? JsonObject ?: return 0f
        val raw = (obj["confidence"] as? JsonPrimitive)?.content
        return raw?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
    }

    /**
     * 从模型输出中抽取最外层 JSON 文本：剥离 Markdown 代码围栏与前后说明。
     *
     * 同时支持对象 `{...}` 与数组 `[...]`：以「最先出现的开括号」判定外层容器类型，再截到对应的末个闭括号。
     * 这样顶层数组 `[{...},{...}]` 也能被完整截取。
     * 抽取失败时原样返回（后续解析会判定为非法 → 触发修复）。
     */
    private fun extractJsonText(raw: String): String {
        val firstObj = raw.indexOf('{')
        val firstArr = raw.indexOf('[')
        val useArray = firstArr >= 0 && (firstObj < 0 || firstArr < firstObj)
        return if (useArray) {
            val end = raw.lastIndexOf(']')
            if (firstArr in 0 until end) raw.substring(firstArr, end + 1) else raw
        } else {
            val end = raw.lastIndexOf('}')
            if (firstObj in 0 until end) raw.substring(firstObj, end + 1) else raw
        }
    }

    private companion object {
        /** 「仅分析摘要」时的输入字符上限。 */
        const val SUMMARY_CHAR_LIMIT = 1200

        /** token 估算：低估档（字符/ token）。 */
        const val LOW_CHARS_PER_TOKEN = 2

        /** token 估算：高估档（字符/ token）。 */
        const val HIGH_CHARS_PER_TOKEN = 4
    }
}

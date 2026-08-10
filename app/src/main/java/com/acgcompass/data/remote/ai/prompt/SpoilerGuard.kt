package com.acgcompass.data.remote.ai.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 剧透保护：系统提示词约束（事前）+ 输出剧透 token 过滤（事后 post-pass）。
 *
 * 双重防线：
 * - **事前**：[SYSTEM_PROMPT_RULES] 注入到每个任务的系统提示词中，强制模型不得泄露关键剧情 /
 *   死亡 / 身份 / 结局 / 反转 / CP 结果，并要求资料不足时输出低置信而非编造。
 * - **事后**：[scrubJson] / [scrubText] 对模型输出再做一次本地净化，命中 [BANNED_TOKENS] 即抽象化为
 *   [ABSTRACTION]，确保最终展示文本**不含**任何被禁用的具体剧透 token（支撑 ）。
 */
object SpoilerGuard {

    /**
     * 抽象化替换文案：命中剧透 token 时以此非具体表述替换原词。
     */
    const val ABSTRACTION: String = "（涉及关键情节，已隐去）"

    /**
     * 被禁用的剧透 token（具体表述）。命中即在输出中抽象化。
     *
     * 公开以便属性测试引用同一份清单生成对抗样本并断言其被净化。
     * 注意：清单聚焦「明确指向剧情结果」的词，避免误伤风格 / 节奏类正常描述。
     */
    val BANNED_TOKENS: List<String> = listOf(
        // 结局 / 结尾
        "结局", "大结局", "结尾", "最终结局", "ending",
        // 死亡 / 牺牲 / 复活
        "死亡", "死了", "身亡", "丧命", "牺牲", "复活", "领便当", "下线",
        // 身份 / 真相
        "真实身份", "真正身份", "身份是", "真相", "真凶", "凶手", "幕后黑手", "幕后真凶",
        // 反转 / 黑化 / 背叛
        "反转", "大反转", "黑化", "背叛", "叛变", "卧底",
        // CP / 感情结果
        "在一起", "分手", "表白成功", "告白成功", "CP 结果", "cp结果", "be结局", "he结局",
    )

    /**
     * 系统提示词中的剧透保护规则块。注入到每个任务的系统提示词。
     */
    val SYSTEM_PROMPT_RULES: String = """
        剧透保护规则（必须严格遵守）：
        1. 禁止泄露任何关键剧情、角色死亡、角色真实身份、故事结局、剧情反转与 CP（情侣）最终结果。
        2. 只描述风格、节奏、题材氛围、争议点与适合/不适合的人群，不复述具体情节走向。
        3. 如确需提及转折，只能抽象表述（例如「中后期有重要转折」「结尾存在争议」），不得指明是谁、发生了什么。
        4. 当资料不足或无法确定时，输出低置信度（confidence 取较低值）并如实说明「资料不足」，绝不编造情节或结论。
        5. 不得输出任何上述被保护信息的同义改写或暗示。
    """.trimIndent()

    /** 预编译的「整词忽略大小写」匹配正则（按 token 长度降序，优先替换更长的具体表述）。 */
    private val bannedRegex: Regex = run {
        val alternation = BANNED_TOKENS
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex(alternation, RegexOption.IGNORE_CASE)
    }

    /**
     * 净化单段文本：把所有命中的剧透 token 替换为 [ABSTRACTION]。
     *
     * 幂等：替换文案本身不含任何被禁 token，故重复调用结果稳定。
     */
    fun scrubText(text: String): String = if (text.isEmpty()) text else bannedRegex.replace(text, ABSTRACTION)

    /**
     * 递归净化 JSON 树中所有字符串叶子值（对象键名不改动，仅净化值），返回净化后的新树。
     *
     * 用于 `AiEngine` 在解析强类型输出**之前**对原始 JSON 做剧透过滤 post-pass，
     * 使任何文本字段（含嵌套数组 / 对象）都不残留被禁 token（支撑 ）。
     */
    fun scrubJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, scrubJson(value)) }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(scrubJson(it)) }
        }
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(scrubText(element.content)) else element
        JsonNull -> JsonNull
    }

    /**
     * 解析 [rawJson] 为 JSON 树、净化后重新编码为紧凑 JSON 文本。
     *
     * @return 净化后的 JSON 文本；当 [rawJson] 非合法 JSON 时返回 `null`（由调用方触发修复流程）。
     */
    fun scrubJsonString(json: Json, rawJson: String): String? {
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return null
        return json.encodeToString(JsonElement.serializer(), scrubJson(element))
    }
}

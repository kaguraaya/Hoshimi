package com.acgcompass.data.remote.ai

import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 按任务的 JSON Schema 把模型输出「就近矫正」为目标类型（放宽 AI 结构化校验）。
 *
 * 背景：`AiEngineImpl` 用强类型 [kotlinx.serialization] 解析模型输出。即使用了宽松的 `Json`
 * （`ignoreUnknownKeys` / `coerceInputValues` / `isLenient`），**结构 / 数值类型不匹配**仍会让
 * `decodeFromString` 抛异常 → 整次解析失败 → 低置信兜底「AI 未能给出可靠结构化结果」。
 * 这在「不支持 json_schema、被降级为纯文本」的兼容端点上尤其常见，导致用户频繁看到 AI 分析失败。
 *
 * 常见且会直接打断解析的偏差：
 * - 整型字段给了小数 / 带单位字符串：`matchScore: 85.0` / `"85分"` / `"约 85"`。
 * - 数组字段给了字符串：`likedReasons: "理由A；理由B"`。
 * - 数组字段给了单个对象：`nodes: { ... }`。
 * - 字符串字段给了数字 / 布尔，布尔字段给了 `"true"`。
 *
 * 本矫正器依据应用构造的 schema（[com.acgcompass.data.remote.ai.AiResponseFormat.JsonSchema.schema]）
 * 递归地把上述偏差就近规整为目标类型；对已合规的输出是幂等的 no-op，故不影响支持结构化输出的 provider。
 * 真正损坏、无法规整的输出仍会让后续解析失败并走低置信兜底——**绝不编造**。
 */
object AiJsonCoercion {

    /** 依 [schema]（JSON Schema 片段）把 [value] 就近矫正为目标类型；无法识别时原样返回。 */
    fun coerce(value: JsonElement, schema: JsonElement): JsonElement {
        val schemaObj = schema as? JsonObject ?: return value
        return when ((schemaObj["type"] as? JsonPrimitive)?.content) {
            "object" -> coerceObject(value, schemaObj)
            "array" -> coerceArray(value, schemaObj)
            "integer" -> coerceInteger(value)
            "number" -> coerceNumber(value)
            "string" -> coerceString(value)
            "boolean" -> coerceBoolean(value)
            else -> value
        }
    }

    private fun coerceObject(value: JsonElement, schema: JsonObject): JsonElement {
        val obj = value as? JsonObject ?: return value
        val props = schema["properties"] as? JsonObject ?: return obj
        return buildJsonObject {
            for ((key, child) in obj) {
                val propSchema = props[key]
                put(key, if (propSchema != null) coerce(child, propSchema) else child)
            }
        }
    }

    private fun coerceArray(value: JsonElement, schema: JsonObject): JsonElement {
        val itemSchema = schema["items"]
        val arr: JsonArray = when {
            value is JsonArray -> value
            value is JsonNull -> JsonArray(emptyList())
            value is JsonObject -> JsonArray(listOf(value)) // 单对象 → 包成单元素数组。
            value is JsonPrimitive && value.isString -> splitStringToArray(value.content)
            value is JsonPrimitive -> JsonArray(listOf(value)) // 单标量 → 单元素数组。
            else -> return value
        }
        return if (itemSchema != null) JsonArray(arr.map { coerce(it, itemSchema) }) else arr
    }

    /** 字符串当数组：按常见分隔符（换行 / 中英文分号顿号 / 竖线）拆分，剥离项目符号，丢弃空项。 */
    private fun splitStringToArray(raw: String): JsonArray {
        val s = raw.trim()
        if (s.isEmpty()) return JsonArray(emptyList())
        val parts = s.split('\n', '；', ';', '、', '|')
            .map { it.trim().trimStart('-', '*', '•', '·', ' ', '\t') }
            .filter { it.isNotEmpty() }
        return JsonArray(parts.map { JsonPrimitive(it) })
    }

    private fun coerceInteger(value: JsonElement): JsonElement {
        if (value is JsonNull) return value
        val prim = value as? JsonPrimitive ?: return value
        val number = parseLeadingNumber(prim.content) ?: return value
        return JsonPrimitive(number.roundToLong())
    }

    private fun coerceNumber(value: JsonElement): JsonElement {
        if (value is JsonNull) return value
        val prim = value as? JsonPrimitive ?: return value
        val number = parseLeadingNumber(prim.content) ?: return value
        return JsonPrimitive(number)
    }

    private fun coerceString(value: JsonElement): JsonElement = when {
        value is JsonNull -> value
        value is JsonPrimitive -> if (value.isString) value else JsonPrimitive(value.content)
        // 对象 / 数组喂给字符串字段：退化为紧凑 JSON 文本，避免整次解析失败（极少见的兜底）。
        else -> JsonPrimitive(value.toString())
    }

    private fun coerceBoolean(value: JsonElement): JsonElement {
        if (value is JsonNull) return value
        val prim = value as? JsonPrimitive ?: return value
        if (!prim.isString) return value // 已是 true/false 字面量。
        return when (prim.content.trim().lowercase()) {
            "true", "1", "yes", "y", "是" -> JsonPrimitive(true)
            "false", "0", "no", "n", "否" -> JsonPrimitive(false)
            else -> value
        }
    }

    /** 从内容中提取首个数字（支持小数与负号），容忍单位 / 前后缀，如 "85%"、"约85.0分"。无数字返回 null。 */
    private fun parseLeadingNumber(content: String): Double? {
        val raw = content.trim()
        raw.toDoubleOrNull()?.let { return it }
        return LEADING_NUMBER.find(raw)?.value?.toDoubleOrNull()
    }

    private val LEADING_NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
}

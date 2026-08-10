package com.acgcompass.data.remote.ai

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * AI 结构化输出「类型就近矫正」回归（放宽 AI 结构化校验）。
 *
 * 验证 [AiJsonCoercion] 依 schema 把模型常见的类型偏差规整为目标类型，使原本会让
 * `decodeFromString` 抛异常 → 低置信兜底「AI 未能给出可靠结构化结果」的可用响应得以正常解析；
 * 同时对已合规输出幂等、对无法矫正的内容原样保留（交由后续兜底，不编造）。纯函数，无 Android/IO 依赖。
 */
class AiJsonCoercionTest : StringSpec({

    // taste_match schema 片段：matchScore(integer) + likedReasons/riskReasons(string[]) + confidence(number)。
    val tasteMatchSchema: JsonObject = buildSchema()

    fun coerce(raw: String): JsonObject =
        AiJsonCoercion.coerce(Json.parseToJsonElement(raw), tasteMatchSchema).jsonObject

    "整型字段给小数应四舍五入为整数" {
        coerce("""{"matchScore": 85.0}""")["matchScore"]!!.jsonPrimitive.int shouldBe 85
    }

    "整型字段给带单位字符串应提取首个数字" {
        coerce("""{"matchScore": "85分"}""")["matchScore"]!!.jsonPrimitive.int shouldBe 85
        coerce("""{"matchScore": "约 92%"}""")["matchScore"]!!.jsonPrimitive.int shouldBe 92
    }

    "数组字段给字符串应按分隔符拆分并剥离项目符号" {
        val arr = coerce("""{"likedReasons": "节奏好；作画强\n- 音乐赞"}""")["likedReasons"]!!.jsonArray
        arr.map { it.jsonPrimitive.content } shouldBe listOf("节奏好", "作画强", "音乐赞")
    }

    "数组字段给单个对象应包成单元素数组（不丢弃整次响应）" {
        coerce("""{"likedReasons": {"reason":"好"}}""")["likedReasons"]!!.jsonArray.size shouldBe 1
    }

    "number 字段给字符串应转为数值" {
        coerce("""{"confidence": "0.8"}""")["confidence"]!!.jsonPrimitive.float shouldBe 0.8f
    }

    "已合规输出应保持不变（幂等）" {
        val out = coerce("""{"matchScore": 73, "likedReasons": ["a","b"], "confidence": 0.6}""")
        out["matchScore"]!!.jsonPrimitive.int shouldBe 73
        out["likedReasons"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("a", "b")
        out["confidence"]!!.jsonPrimitive.float shouldBe 0.6f
    }

    "无法矫正的数字应原样保留（交由后续低置信兜底，不编造)" {
        coerce("""{"matchScore": "未知"}""")["matchScore"]!!.jsonPrimitive.content shouldBe "未知"
    }
})

private fun buildSchema(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("matchScore") { put("type", "integer") }
        putJsonObject("likedReasons") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("riskReasons") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("confidence") { put("type", "number") }
    }
}

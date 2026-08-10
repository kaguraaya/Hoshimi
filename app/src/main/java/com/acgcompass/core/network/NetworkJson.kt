package com.acgcompass.core.network

import kotlinx.serialization.json.Json

/**
 * 全局共享的 kotlinx.serialization [Json] 配置（REST + GraphQL 通用）。
 *
 * 各数据源返回结构演进频繁，配置以「容错优先、不崩溃」为目标：
 * - `ignoreUnknownKeys = true`：源新增字段不影响解析。
 * - `coerceInputValues = true`：null / 非法值回落到默认值，避免反序列化异常。
 * - `isLenient = true`：宽松解析（无引号 / 非标准）。
 * - `explicitNulls = false`：序列化时省略 null 字段，输出更紧凑。
 *
 * 注意：本实例用于网络层 DTO；它与备份导出（.x）可使用各自配置，互不影响。
 */
object NetworkJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }
}

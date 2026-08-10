package com.acgcompass.data.remote.vndb

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * VNDB `filters` 谓词构造器。
 *
 * VNDB 过滤器是三元 JSON 数组 `[name, op, value]`（如 `["id","=","v17"]`、`["search","=","关键字"]`），
 * 可用 `["and", p1, p2, ...]` / `["or", ...]` 组合。这里仅构造本项目需要的几种基本谓词，
 * 不臆造未核验的过滤维度。
 */
object VndbFilters {

    private const val OP_EQUALS = "="

    /** 按 vndbid 精确匹配：`["id","=","v17"]`。 */
    fun byId(id: String): JsonElement = buildJsonArray {
        add("id")
        add(OP_EQUALS)
        add(id)
    }

    /** 按关键字搜索（匹配标题 / 别名 / 发行标题）：`["search","=","关键字"]`。 */
    fun bySearch(keyword: String): JsonElement = buildJsonArray {
        add("search")
        add(OP_EQUALS)
        add(keyword)
    }

    /** Public automatic-pool quality floor; avoids low-signal entries before local ranking. */
    fun minimumVotes(votes: Int): JsonElement = buildJsonArray {
        add("votecount")
        add(">=")
        add(JsonPrimitive(votes.coerceAtLeast(1)))
    }

    /** VNDB nested filter: visual novels developed by the specified producer. */
    fun byDeveloper(producerId: String): JsonElement = buildJsonArray {
        add("developer")
        add(OP_EQUALS)
        add(
            buildJsonArray {
                add("id")
                add(OP_EQUALS)
                add(producerId)
            },
        )
    }
}

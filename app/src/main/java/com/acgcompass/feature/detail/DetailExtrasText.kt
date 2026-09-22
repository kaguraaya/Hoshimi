package com.acgcompass.feature.detail

import com.acgcompass.domain.detail.DetailExtrasData

/** 把 Bangumi 扩展资料折叠成详情 Tab 里的可读正文；没有内容时返回 [NO_DATA]。 */
internal fun charactersStaffText(data: DetailExtrasData): String {
    val charLines = data.characters.take(LIST_LIMIT).mapNotNull { character ->
        character.name.takeIf { it.isNotBlank() }?.let { name ->
            buildString {
                append("· ").append(name)
                character.role?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
                character.actor?.takeIf { it.isNotBlank() }?.let { append(" · CV：$it") }
            }
        }
    }
    val staffLines = data.staff.take(LIST_LIMIT).mapNotNull { person ->
        person.name.takeIf { it.isNotBlank() }?.let { name ->
            "· $name" + (person.role?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
        }
    }
    return buildString {
        if (charLines.isNotEmpty()) append("角色\n").append(charLines.joinToString("\n"))
        if (staffLines.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Staff\n").append(staffLines.joinToString("\n"))
        }
    }.ifBlank { NO_DATA }
}

internal fun relationsText(data: DetailExtrasData): String = data.relations.take(
    RELATION_LIMIT,
).mapNotNull { relation ->
    val title = relation.nameCn.takeIf { it.isNotBlank() } ?: relation.name.takeIf { it.isNotBlank() }
    title?.let { "· " + (relation.relation?.takeIf { r -> r.isNotBlank() }?.let { r -> "[$r] " } ?: "") + it }
}.joinToString("\n").ifBlank { NO_DATA }

internal fun watchRouteText(data: DetailExtrasData): String {
    val prequels = data.relations.filter { it.relation?.contains("前") == true }
    val sequels = data.relations.filter { it.relation?.contains("续") == true }
    return when {
        data.relations.isEmpty() -> NO_DATA
        prequels.isEmpty() && sequels.isEmpty() ->
            "路线待确认（已有关联作品，但前传/续作关系不明确，建议参考「关联作品」自行判断）"
        else -> buildString {
            append("根据关联作品推断的大致顺序（仅供参考）：\n")
            prequels.forEach { append("· 前作：").append(it.nameCn.ifBlank { it.name }).append("\n") }
            append("· 本作\n")
            sequels.forEach { append("· 续作：").append(it.nameCn.ifBlank { it.name }).append("\n") }
        }.trim()
    }
}

internal fun commentLines(data: DetailExtrasData): List<String> = data.comments.take(COMMENT_LIMIT).map { comment ->
    val rate = if (comment.rate > 0) " · ${comment.rate}分" else ""
    "${comment.nickname}$rate：${comment.text.replace("\n", " ").take(COMMENT_CHARS)}"
}

private const val LIST_LIMIT = 12
private const val RELATION_LIMIT = 20
private const val COMMENT_LIMIT = 8
private const val COMMENT_CHARS = 80

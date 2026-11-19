package com.acgcompass.domain.model

/**
 * 本地作品 id 的规则。
 *
 * 作品表以作品 id 为主键。Bangumi 条目 id 直接用作作品 id；VNDB 的 id 自带 `v` 前缀，不会撞号。
 * AniList、MAL、Tenrai、Jikan 用的是各自的数字编号，和 Bangumi 共用一套数字会撞号（Bangumi 的 21 和 AniList 的 21 不是同一部），
 * 所以这些平台的作品 id 写成「来源:编号」（如 `ANILIST:21`）。调各平台接口、记源链接时用 [sourceItemId] 取回原始编号。
 */
object WorkIds {
    private val NAMESPACED = setOf(SourceId.ANILIST, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL)

    /** 某平台的原始编号对应的本地作品 id。 */
    fun local(source: SourceId, rawId: String): String =
        if (source in NAMESPACED && !rawId.startsWith(prefix(source))) {
            prefix(source) + rawId
        } else {
            rawId
        }

    /** 本地作品 id 在 [source] 上的原始编号（去掉来源前缀）。 */
    fun sourceItemId(workId: String, source: SourceId): String = workId.removePrefix(prefix(source))

    /** 作品对应的原始编号。 */
    fun sourceItemId(work: Work): String = sourceItemId(work.id, work.primarySource)

    /** 这个平台的作品 id 是否带来源前缀。 */
    fun isNamespaced(source: SourceId): Boolean = source in NAMESPACED

    private fun prefix(source: SourceId): String = "${source.name}:"
}

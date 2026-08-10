package com.acgcompass.domain.model

/**
 * 规范化的内容数据源标识。
 *
 * 这是 **领域层的权威定义**，用于 [Work.primarySource]、[SourceRef]、`RatingAggregate.perSource`
 * 以及降级编排顺序等业务语义。取值与设计文档「各源客户端」一致：
 *
 * - [BANGUMI]：Bangumi 主来源。
 * - [ANILIST]：AniList 补充来源。
 * - [JIKAN]：Jikan 补充来源。
 * - [MAL]：MyAnimeList 官方 API。
 * - [VNDB]：VNDB HTTP API。
 *
 * 设计决策（与  的 `com.acgcompass.data.credential.SourceId` 的关系）：
 * 凭据层的 `SourceId` 额外含 `AI_PROVIDER`（AI 服务并非内容评分源），且属于 data 层。
 * 为遵守分层方向（domain 不依赖 data / Android），此处**单独**定义只含 5 个内容源的领域枚举，
 * 作为作品/评分/来源链接的权威类型；data 层 mapper 引用本枚举进行 enum↔String 转换。
 * 两个枚举的取值前缀保持一致（BANGUMI/ANILIST/JIKAN/MAL/VNDB），便于跨层字符串互认。
 *
 * 枚举名作为持久化字符串，**不得**随意重命名以保证升级兼容。
 */
enum class SourceId {
    BANGUMI,
    ANILIST,
    TENRAI,
    JIKAN,
    MAL,
    VNDB,
    ;

    companion object {
        /** 从持久化字符串解析；未知值返回 `null`，避免升级 / 损坏数据导致崩溃。 */
        fun fromStorage(raw: String?): SourceId? = entries.firstOrNull { it.name == raw }
    }
}

package com.acgcompass.domain.ai

import com.acgcompass.domain.model.AiGenerator

/**
 * 防剧透雷达的剧透等级。
 *
 * 设计约束（强制剧透保护）：
 * - **无剧透必做且为默认**：[NO_SPOILER] 是唯一已实现且强制默认的等级，任何请求都会被收敛到它，
 *   确保「无剧透」不可被绕过。
 * - **轻微 / 完整为预留**：[MILD] / [FULL] 仅占位预留（[implemented] = false），尚未实现；
 *   当前阶段请求这两个等级会被安全降级回 [NO_SPOILER]，绝不输出更高剧透度内容。
 */
enum class SpoilerLevel(val label: String, val implemented: Boolean) {
    /** 无剧透：仅总结风格 / 节奏 / 争议 / 雷点，强制默认。 */
    NO_SPOILER("无剧透", implemented = true),

    /** 轻微剧透：预留等级，尚未实现。 */
    MILD("轻微剧透", implemented = false),

    /** 完整分析：预留等级，尚未实现。 */
    FULL("完整分析", implemented = false),
    ;

    companion object {
        /** 强制默认等级：无剧透。 */
        val DEFAULT: SpoilerLevel = NO_SPOILER

        /** 从持久化 / 输入字符串解析；未知 / 未实现等回退由调用方再做 [DEFAULT] 收敛。 */
        fun fromStorage(raw: String?): SpoilerLevel = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 雷达摘要来源标注：摘要分别来自用户短评 / 公共 Reviews / 标签统计 / AI 总结。
 *
 * 用于在 AI / 规则卡片上明确「这份无剧透摘要由哪些数据来源支撑」，避免用户误以为是客观定论。
 */
enum class RadarSummarySource(val label: String) {
    /** 用户个人短评。 */
    USER_COMMENTS("用户短评"),

    /** 公共评论（如 AniList Reviews）。 */
    PUBLIC_REVIEWS("公共 Reviews"),

    /** 标签频次统计。 */
    TAG_STATS("标签统计"),

    /** AI 总结。 */
    AI("AI 总结"),
}

/**
 * 一次防剧透雷达生成的领域结果。
 *
 * 在 [SpoilerRadarOutput] 之上补充展示所需的标注信息：
 * - [generator]：本结果由 AI 增强（[AiGenerator.AI]）还是本地规则回退（[AiGenerator.RULE]）生成。
 * - [requestedLevel] / [effectiveLevel]：用户请求的剧透等级与实际生效等级；当前实际生效恒为
 *   [SpoilerLevel.NO_SPOILER]（强制无剧透）。
 * - [summarySources]：摘要来源标注（短评 / Reviews / 标签 / AI）。
 * - [generatedAt]：生成时间戳，供卡片展示。
 *
 * 置信度与底层维度 / 数据来源沿用 [output]（[SpoilerRadarOutput.confidence] / [SpoilerRadarOutput.sources]）。
 */
data class SpoilerRadarResult(
    val workId: String,
    val output: SpoilerRadarOutput,
    val generator: AiGenerator,
    val requestedLevel: SpoilerLevel,
    val effectiveLevel: SpoilerLevel,
    val summarySources: List<RadarSummarySource>,
    val generatedAt: Long,
) {
    /** 置信度便捷访问（取自底层结构化输出）。 */
    val confidence: Float get() = output.confidence
}

/**
 * 剧透文本净化器（领域抽象）。
 *
 * 由数据层绑定到 `data/remote/ai/prompt/SpoilerGuard::scrubText`（命中被禁剧透 token 即抽象化），
 * 使领域用例无需反向依赖 data 层即可对本地规则版输出再做一次剧透过滤。默认 [NONE] 为恒等函数。
 */
fun interface SpoilerScrubber {
    fun scrub(text: String): String

    companion object {
        /** 恒等净化器（不过滤）；仅用于测试或不需要过滤的场景。 */
        val NONE: SpoilerScrubber = SpoilerScrubber { it }
    }
}

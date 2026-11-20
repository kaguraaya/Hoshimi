package com.acgcompass.feature.detail

import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.Work

/** 详情 Tab：可填充的从已有数据生成，未接入数据源的 Tab 以「暂无数据」占位。 */
internal fun Work.toTabs(
    ratings: RatingAggregate?,
    personal: PersonalUiModel,
    extras: DetailExtras = DetailExtras(),
    radar: SpoilerRadarResult? = null,
): List<DetailTabUiModel> = listOf(
    summaryTab(summary, extras.bangumiLoad),
    // 评论摘要 = 真实他人短评（next.bgm.tv/p1）+ 无剧透评价雷达折叠的口碑概览。
    extrasTab("评论摘要", reviewSummaryWithComments(extras.comments, radar), extras, extras.commentsFailed),
    extrasTab("角色 · Staff", extras.charactersStaffBody, extras, extras.creditsFailed),
    extrasTab("关联作品", extras.relationsBody, extras, extras.creditsFailed),
    // 移除重复的「观看路线」文字 Tab——结构化「观看路线（智能选择主线）」区块已单独展示可点击作品行。
    DetailTabUiModel("平台数据", platformDataSummary(ratings)),
    DetailTabUiModel("我的记录", personalRecordSummary(personal)),
)

internal fun summaryTab(summary: String?, load: ExtrasLoad): DetailTabUiModel {
    val text = summary?.takeIf { it.isNotBlank() }
    return when {
        text != null -> DetailTabUiModel("简介", text)
        load == ExtrasLoad.LOADING -> DetailTabUiModel("简介", "正在加载简介…", TabStatus.LOADING)
        else -> DetailTabUiModel("简介", NO_DATA)
    }
}

internal fun failureText(failure: com.acgcompass.domain.detail.ExtrasFailure?): String = when (failure) {
    com.acgcompass.domain.detail.ExtrasFailure.RATE_LIMITED ->
        "Bangumi 说请求太频繁了（后台可能正在补资料），等一会儿再点重新加载。"
    com.acgcompass.domain.detail.ExtrasFailure.NETWORK -> "没连上 Bangumi，检查网络后点重新加载。"
    else -> "Bangumi 暂时没有响应，没能加载这部分资料。"
}

/** 有内容就显示；没有内容时区分「加载中」「加载失败」「没有 Bangumi 条目」和真的没有。 */
internal fun extrasTab(title: String, body: String, extras: DetailExtras, failed: Boolean): DetailTabUiModel = when {
    body != NO_DATA -> DetailTabUiModel(title, body)
    extras.bangumiLoad == ExtrasLoad.LOADING -> DetailTabUiModel(title, "正在从 Bangumi 加载…", TabStatus.LOADING)
    failed -> DetailTabUiModel(title, failureText(extras.failure), TabStatus.FAILED)
    extras.bangumiLoad == ExtrasLoad.NO_SOURCE ->
        DetailTabUiModel(title, "这部作品没有对应的 Bangumi 条目，暂无这部分资料。")
    else -> DetailTabUiModel(title, NO_DATA)
}

/**
 * 评论摘要 = 对大众口碑/评论的**内容性**摘要（好评点、争议点、需留意、适合人群），
 * 来自无剧透评价雷达（AI 或本地规则，基于评论/标签分析），**不展示评分数值**充当评论。
 * 无任何可用的内容性摘要时返回 [NO_DATA]（绝不用评分替代，绝不伪造）。
 */
/**
 * 评论摘要 = 真实他人短评（最多展示数条）+ 雷达内容性口碑摘要。两者都空才「暂无数据」。
 */
internal fun reviewSummaryWithComments(comments: List<String>, radar: SpoilerRadarResult?): String {
    val parts = mutableListOf<String>()
    val realComments = comments.filter { it.isNotBlank() }.take(6)
    if (realComments.isNotEmpty()) {
        parts += "近期短评：\n" + realComments.joinToString("\n") { "· $it" }
    }
    val summary = reviewSummary(radar)
    if (summary != NO_DATA) {
        parts += summary
    }
    return if (parts.isEmpty()) NO_DATA else parts.joinToString("\n\n")
}

internal fun reviewSummary(radar: SpoilerRadarResult?): String {
    val sections = mutableListOf<String>()
    val out = radar?.output
    if (out != null) {
        // 总体印象和观看时机在「无剧透评价雷达」里显示，这里只列要点。
        out.pros.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "好评点：\n" + it.joinToString("\n") { p -> "· $p" } }
        out.controversies.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "争议 / 吐槽点：\n" + it.joinToString("\n") { c -> "· $c" } }
        out.pitfalls.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "需留意：\n" + it.joinToString("\n") { c -> "· $c" } }
        out.suitableFor.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "适合：" + it.joinToString("、") }
        out.notSuitableFor.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "可能不适合：" + it.joinToString("、") }
    }
    if (sections.isEmpty()) return NO_DATA
    // 标注来源，避免被误读为逐条原始评论。
    val source = when (radar?.generator) {
        AiGenerator.AI -> "（来源：AI 对评论 / 标签的无剧透分析）"
        else -> "（来源：本地规则汇总，基于社区标签与口碑信号）"
    }
    return sections.joinToString("\n\n") + "\n\n" + source
}

/** 「平台数据」Tab：汇总各平台是否有评分；全部缺失则「暂无数据」。 */
internal fun platformDataSummary(ratings: RatingAggregate?): String {
    val rows = ratings.toPlatformRatings()
    val available = rows.filter { it.available }
    if (available.isEmpty()) return NO_DATA
    return available.joinToString("\n") { row ->
        val vote = row.voteText?.let { "（$it）" }.orEmpty()
        "${row.platform}：${row.scoreText}$vote"
    }
}

/** 「我的记录」Tab：汇总个人状态 / 评分 / 进度 / 短评；全部缺失则「暂无数据」。 */
internal fun personalRecordSummary(personal: PersonalUiModel): String {
    val parts = buildList {
        personal.statusText?.let { add("状态：$it") }
        personal.ratingText?.let { add("我的评分：$it") }
        personal.progressText?.let { add("进度：$it") }
        personal.reviewText?.let { add("短评：$it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: NO_DATA
}

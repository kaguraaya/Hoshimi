package com.acgcompass.domain.funfeature

import com.acgcompass.domain.model.BacklogItem

/**
 * 情绪风险类别。每个类别携带其匹配别名与对外展示的提示文案。
 */
enum class MoodRisk(val warning: String) {
    /** 致郁：观后情绪低落。 */
    DEPRESSING("致郁警告：情绪低落时建议改日再看"),

    /** 压抑：剧情沉重、令人喘不过气。 */
    OPPRESSIVE("压抑提示：剧情沉重，注意心理负担"),

    /** 党争：粉丝阵营 / CP 容易开战。 */
    FACTION_WAR("党争预警：阵营 / CP 容易上头，理性吃番"),

    /** 高上头：极易停不下来、熬夜补番。 */
    HIGHLY_ADDICTIVE("高上头预警：可能一口气停不下来，注意作息"),
    ;

    companion object {
        /** 各风险类别的匹配别名（规范化为去空格小写后比较）。 */
        internal val aliases: Map<MoodRisk, Set<String>> = mapOf(
            DEPRESSING to setOf("致郁", "抑郁", "depressing"),
            OPPRESSIVE to setOf("压抑", "沉重", "致郁系", "oppressive"),
            FACTION_WAR to setOf("党争", "撕逼", "cp大战", "阵营", "factionwar"),
            HIGHLY_ADDICTIVE to setOf("上头", "高上头", "高能", "停不下来", "熬夜", "addictive"),
        )
    }
}

/**
 * 单条情绪风险提示。
 *
 * - [risk]：命中的风险类别。
 * - [warning]：对外展示的提示文案（取自 [MoodRisk.warning]）。
 */
data class MoodRiskWarning(
    val risk: MoodRisk,
    val warning: String,
)

/**
 * 情绪风险提示——纯函数。
 *
 * 扫描一组标签，命中致郁 / 压抑 / 党争 / 高上头等风险别名时给出对应提示。结果按 [MoodRisk] 声明
 * 顺序去重排列，便于稳定展示与断言。无命中时返回空列表（不伪造提示）。
 *
 * @param tags 心情 / 风险标签（如 [BacklogItem.moodTags] + [BacklogItem.riskTags] 或作品标签名）。
 * @return 命中的风险提示列表，去重且有序。
 */
fun assessMoodRisks(tags: List<String>): List<MoodRiskWarning> {
    val normalized = tags
        .map { it.trim().lowercase().replace(" ", "") }
        .filter { it.isNotEmpty() }
        .toSet()
    if (normalized.isEmpty()) return emptyList()

    return MoodRisk.entries.mapNotNull { risk ->
        val hit = MoodRisk.aliases[risk].orEmpty().any { alias ->
            val a = alias.lowercase().replace(" ", "")
            a in normalized
        }
        if (hit) MoodRiskWarning(risk = risk, warning = risk.warning) else null
    }
}

/**
 * 针对单条待补条目评估情绪风险——便捷重载。
 * 合并条目的心情标签与风险标签后调用 [assessMoodRisks]。
 */
fun assessMoodRisks(item: BacklogItem): List<MoodRiskWarning> = assessMoodRisks(item.moodTags + item.riskTags)

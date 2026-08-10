package com.acgcompass.domain.fallback

import com.acgcompass.domain.ai.SpoilerRadarOutput

/**
 * 防剧透雷达的输入（[LocalFallbackRadar.generate] 的入参）。
 *
 * 仅承载本地可得的轻量信号：作品标签与短评片段。资料越稀疏，生成结果的置信度越低。
 *
 * @property tags          作品标签 / 题材关键词（来自数据源），可空。
 * @property reviewSnippets 短评 / 吐槽片段（仅取其关键词做统计，不复述具体情节），可空。
 * @property title         作品标题，仅用于点缀整体印象文案，可空。
 * @property sourceLabels  数据来源标签（如「Bangumi 标签」「本地短评」），并入输出 sources。
 */
data class RadarInput(
    val tags: List<String> = emptyList(),
    val reviewSnippets: List<String> = emptyList(),
    val title: String = "",
    val sourceLabels: List<String> = emptyList(),
)

/**
 * 本地降级防剧透雷达（Local_Fallback）。
 *
 * 当用户未配置 AI key 时，仅凭标签 + 短评关键词统计，用**规则启发式**生成完整维度的
 * [SpoilerRadarOutput]：总体印象、优点、争议、雷点、适合 / 不适合人群、观看时机、置信度与数据来源
 * 。
 *
 * 设计约束：
 * - **纯函数**：[generate] 无副作用、不触网、不访问磁盘，输出仅由入参决定，便于属性测试。
 * - **不泄露剧情**：本地只做关键词归类，不复述情节；同时把所有产出文本经 [generate] 的 `scrub`
 *   参数（调用方传入 `SpoilerGuard::scrubText`）再过滤一次，命中剧透 token 即抽象化。
 * - **领域纯净**：剧透过滤器以 `(String) -> String` 函数依赖注入，默认恒等函数，使本类不依赖 data 层。
 * - **资料不足给低置信而非编造**：信号越稀疏 [SpoilerRadarOutput.confidence] 越低；维度无命中时给出
 *   诚实占位说明而非伪造情节。
 */
object LocalFallbackRadar {

    /** 本地启发式生成的置信度上限：本地规则版永远不声称高置信。 */
    private const val MAX_CONFIDENCE: Float = 0.6f

    private data class Rule(val keywords: List<String>, val text: String)

    /** 优点启发式：题材 / 制作维度的正向信号。 */
    private val PROS_RULES: List<Rule> = listOf(
        Rule(listOf("作画", "画面", "画风", "美术", "作画精良"), "作画与画面表现获得好评"),
        Rule(listOf("配乐", "音乐", "bgm", "ost", "声优", "配音"), "音乐 / 声优表现突出"),
        Rule(listOf("剧情", "故事", "文戏", "演出", "分镜", "脚本"), "剧情编排与演出获得肯定"),
        Rule(listOf("治愈", "温暖", "暖", "温馨"), "氛围治愈温暖"),
        Rule(listOf("搞笑", "欢乐", "沙雕", "喜剧", "轻松"), "轻松搞笑，娱乐性强"),
        Rule(listOf("热血", "燃", "战斗", "高能"), "热血燃向，节奏带感"),
        Rule(listOf("感人", "催泪", "泪点", "细腻"), "情感细腻，容易共鸣"),
        Rule(listOf("神作", "经典", "良心", "精良", "用心", "高质量"), "整体完成度受到称赞"),
    )

    /** 争议启发式：评价分化 / 风评波动信号（仅抽象描述，不指明情节）。 */
    private val CONTROVERSY_RULES: List<Rule> = listOf(
        Rule(listOf("烂尾", "高开低走", "后段", "崩"), "部分观众认为后段表现下滑"),
        Rule(listOf("注水", "拖沓", "节奏慢", "冗长", "拖"), "节奏存在拖沓争议"),
        Rule(listOf("作画崩", "崩坏", "作画不稳"), "部分集数作画稳定性受质疑"),
        Rule(listOf("魔改", "改编", "原创剧情"), "改编处理存在分歧"),
        Rule(listOf("两极", "见仁见智", "争议", "口碑分化"), "口碑两极，评价分化"),
        Rule(listOf("粉丝向", "门槛", "情怀"), "偏粉丝向，对新观众门槛较高"),
    )

    /** 雷点启发式：观看前应被提示的体验风险（不剧透，仅给提示）。 */
    private val PITFALL_RULES: List<Rule> = listOf(
        Rule(listOf("血腥", "暴力", "猎奇", "重口"), "含暴力 / 猎奇向描写，介意者慎入"),
        Rule(listOf("致郁", "压抑", "黑深残", "虐", "沉重"), "情绪偏沉重压抑，心情不佳时慎看"),
        Rule(listOf("卖肉", "福利", "杀必死", "后宫", "擦边"), "含较多福利向元素，介意者注意"),
        Rule(listOf("恐怖", "惊悚", "惊吓"), "含恐怖惊悚元素"),
        Rule(listOf("说教", "三观", "价值观"), "价值观表达可能引发部分观众不适"),
    )

    /** 适合人群启发式：题材偏好映射。 */
    private val SUITABLE_RULES: List<Rule> = listOf(
        Rule(listOf("热血", "战斗", "燃", "运动"), "喜欢热血 / 战斗题材的观众"),
        Rule(listOf("恋爱", "恋爱喜剧", "校园", "青春"), "偏好恋爱 / 校园题材的观众"),
        Rule(listOf("日常", "治愈", "轻松", "温馨"), "想放松解压的观众"),
        Rule(listOf("悬疑", "推理", "烧脑", "科幻", "悬念"), "喜欢烧脑 / 思考向作品的观众"),
        Rule(listOf("催泪", "感人", "细腻"), "愿意投入情感的观众"),
        Rule(listOf("硬核", "设定", "世界观"), "喜欢复杂设定的核心爱好者"),
    )

    /** 不适合人群启发式：与雷点 / 节奏相对应的回避建议。 */
    private val NOT_SUITABLE_RULES: List<Rule> = listOf(
        Rule(listOf("致郁", "压抑", "虐", "沉重"), "想找轻松解压内容的观众"),
        Rule(listOf("血腥", "暴力", "猎奇", "重口", "恐怖"), "无法接受暴力 / 猎奇描写的观众"),
        Rule(listOf("节奏慢", "拖沓", "注水", "冗长"), "缺乏耐心、偏好快节奏的观众"),
        Rule(listOf("卖肉", "福利", "后宫", "擦边"), "介意福利向内容的观众"),
        Rule(listOf("粉丝向", "门槛", "硬核"), "想轻度入门的新观众"),
    )

    /**
     * 由标签 + 短评关键词统计生成完整维度的防剧透雷达（纯函数）。
     *
     * @param input 本地可得的标签与短评片段。
     * @param scrub 剧透过滤器；默认恒等函数（保持领域纯净），调用方应传入 `SpoilerGuard::scrubText`
     *              以确保任何被禁剧透 token 在最终展示前被抽象化。
     */
    fun generate(input: RadarInput, scrub: (String) -> String = { it }): SpoilerRadarOutput {
        val corpus = buildCorpus(input)

        val pros = matchRules(PROS_RULES, corpus)
        val controversies = matchRules(CONTROVERSY_RULES, corpus)
        val pitfalls = matchRules(PITFALL_RULES, corpus)
        val suitableFor = matchRules(SUITABLE_RULES, corpus)
        val notSuitableFor = matchRules(NOT_SUITABLE_RULES, corpus)

        val output = SpoilerRadarOutput(
            overallImpression = buildOverallImpression(input, pros, controversies),
            pros = pros.ifEmpty { listOf("（资料有限，暂未提取到明确优点）") },
            controversies = controversies.ifEmpty { listOf("（暂未发现明显争议点）") },
            pitfalls = pitfalls.ifEmpty { listOf("（暂未发现需特别提示的雷点）") },
            suitableFor = suitableFor.ifEmpty { listOf("（适合人群信息不足，建议结合标签自行判断）") },
            notSuitableFor = notSuitableFor.ifEmpty { listOf("（暂无明确不适合人群提示）") },
            watchTiming = buildWatchTiming(corpus),
            confidence = computeConfidence(input, pros, controversies, pitfalls, suitableFor, notSuitableFor),
            sources = buildSources(input),
        )

        return output.scrubbed(scrub)
    }

    /** 把所有文本字段经 [scrub] 过滤一次，确保展示文本不残留被禁剧透 token。 */
    private fun SpoilerRadarOutput.scrubbed(scrub: (String) -> String): SpoilerRadarOutput = copy(
        overallImpression = scrub(overallImpression),
        pros = pros.map(scrub),
        controversies = controversies.map(scrub),
        pitfalls = pitfalls.map(scrub),
        suitableFor = suitableFor.map(scrub),
        notSuitableFor = notSuitableFor.map(scrub),
        watchTiming = scrub(watchTiming),
        sources = sources.map(scrub),
    )

    /** 归一化语料：标签 + 短评片段合并、去空白、转小写，供 [matchRules] 做 contains 命中。 */
    private fun buildCorpus(input: RadarInput): String = (input.tags + input.reviewSnippets)
        .joinToString(separator = " ") { it.trim() }
        .lowercase()

    /** 命中即收录规则文本；按规则顺序去重，保证维度内不重复。 */
    private fun matchRules(rules: List<Rule>, corpus: String): List<String> {
        if (corpus.isBlank()) return emptyList()
        val hits = LinkedHashSet<String>()
        for (rule in rules) {
            if (rule.keywords.any { corpus.contains(it.lowercase()) }) hits += rule.text
        }
        return hits.toList()
    }

    private fun buildOverallImpression(input: RadarInput, pros: List<String>, controversies: List<String>): String {
        val topTags = input.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (topTags.isEmpty() && input.reviewSnippets.isEmpty()) {
            return "资料有限，暂无法给出可靠的整体印象（建议配置 AI 获取更完整的无剧透分析）。"
        }
        val titlePrefix = input.title.trim().takeIf { it.isNotEmpty() }?.let { "《$it》" } ?: "本作"
        val tagPart = if (topTags.isNotEmpty()) "整体偏向「${topTags.joinToString("、")}」" else "暂无明确题材标签"
        val tonePart = when {
            pros.isNotEmpty() && controversies.isNotEmpty() -> "，有亮点也存在评价分歧"
            pros.isNotEmpty() -> "，整体口碑偏正向"
            controversies.isNotEmpty() -> "，评价存在一定分化"
            else -> ""
        }
        return "$titlePrefix$tagPart$tonePart；以下为基于本地标签与短评关键词统计的无剧透概览。"
    }

    private fun buildWatchTiming(corpus: String): String = when {
        listOf("剧场版", "ova", "短篇", "单元", "一集").any { corpus.contains(it) } ->
            "篇幅较短，适合今晚一口气看完。"
        listOf("长篇", "连载", "百集", "长篇巨制").any { corpus.contains(it) } ->
            "体量较大，适合安排整段时间慢慢追。"
        else -> "可按个人节奏观看，无强制观看时机要求。"
    }

    /** 信号越稀疏置信度越低；本地版上限 [MAX_CONFIDENCE]，永不声称高置信。 */
    private fun computeConfidence(input: RadarInput, vararg matchedDimensions: List<String>): Float {
        val tagCount = input.tags.count { it.isNotBlank() }
        val snippetCount = input.reviewSnippets.count { it.isNotBlank() }
        val matchedSignals = matchedDimensions.sumOf { it.size }
        val raw = tagCount * 0.06f + snippetCount * 0.08f + matchedSignals * 0.05f
        return raw.coerceIn(0f, MAX_CONFIDENCE)
    }

    /** sources 始终非空：至少标注本地统计方法，并并入调用方提供的来源标签。 */
    private fun buildSources(input: RadarInput): List<String> {
        val sources = LinkedHashSet<String>()
        input.sourceLabels.map { it.trim() }.filter { it.isNotEmpty() }.forEach { sources += it }
        if (input.tags.any { it.isNotBlank() }) sources += "本地标签统计"
        if (input.reviewSnippets.any { it.isNotBlank() }) sources += "本地短评关键词统计"
        if (sources.isEmpty()) sources += "本地规则引擎（资料不足）"
        return sources.toList()
    }
}

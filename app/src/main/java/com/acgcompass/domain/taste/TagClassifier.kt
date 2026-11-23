package com.acgcompass.domain.taste

import com.acgcompass.domain.usecase.TasteTagTaxonomy

/**
 * 基于字典、确定性规则和交叉验证的单标签 [TasteCategory] 分类器。
 *
 * 三层规则（顺序很重要，越具体越靠前）：
 * 1. **规则层（正则）**：`^\d{4}年(\d{1,2}月)?$` 等 → [TasteCategory.TIME]；
 *    `(小说改|轻小说改|游戏改|GAL改|漫画改|漫改)` 与制作公司 → [TasteCategory.SOURCE]。
 * 2. **交叉验证层**：标签命中作品**真实** staff/角色/CV 名 → 对应 [STAFF]/[CHARACTER]/[CV]；
 *    self-title（与作品标题变体相同）→ [NOISE]（泄漏标签）；社区联想厂牌（如「京阿尼」）但真实 staff
 *    不含该厂牌 → [NOISE]（最多保留极低权重，默认不参与主分）。
 * 3. **字典层**：维护四张表——题材单标签 / 情节装置 / 角色原型 / 梗社区语气，分别 → [TOPIC]/[DEVICE]/[XP]/[MEME]。
 *
 * 兜底：以上都未命中且长度 ≥ 2 的标签按文档「其余视为题材」归入 [TOPIC]（保留社区标签丰富性）。
 * 纯 Kotlin、无 IO，便于单测。清洗口径与 [TasteTagTaxonomy] 一致（下划线/连字符转空格、折叠、小写）。
 */
object TagClassifier {

    /**
     * 分类上下文：提供作品**结构化**的 staff/角色/CV 名与标题变体，用于交叉验证（缺省为空集合，
     * 此时退化为「正则 + 字典」分类，不做交叉验证）。名称均会被 [clean] 归一后比较。
     */
    data class Context(
        val staffNames: Set<String> = emptySet(),
        val characterNames: Set<String> = emptySet(),
        val cvNames: Set<String> = emptySet(),
        val sourceNames: Set<String> = emptySet(),
        val titleVariants: Set<String> = emptySet(),
    ) {
        /** 标题、人名切好的词：一部作品的几十个标签共用，不用每个标签都把名字重新切一遍。 */
        private val titleFragments by lazy { NameFragments(titleVariants) }
        private val personFragments by lazy { NameFragments(staffNames + characterNames + cvNames + sourceNames) }
        internal val contextualNames: Set<String> by lazy { characterNames + cvNames + staffNames + sourceNames }

        internal fun isTitleFragment(tag: String): Boolean = titleFragments.contains(tag)

        internal fun isPersonFragment(tag: String): Boolean = personFragments.contains(tag)

        companion object {
            val EMPTY = Context()

            /** 便捷构造：传入原始名列表，内部统一清洗归一。 */
            fun of(
                staff: Collection<String> = emptyList(),
                characters: Collection<String> = emptyList(),
                cv: Collection<String> = emptyList(),
                sources: Collection<String> = emptyList(),
                titles: Collection<String> = emptyList(),
            ): Context = Context(
                staffNames = staff.map { clean(it) }.filter { it.isNotEmpty() }.toSet(),
                characterNames = characters.map { clean(it) }.filter { it.isNotEmpty() }.toSet(),
                cvNames = cv.map { clean(it) }.filter { it.isNotEmpty() }.toSet(),
                sourceNames = sources.map { clean(it) }.filter { it.isNotEmpty() }.toSet(),
                titleVariants = titles.map { clean(it) }.filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    /**
     * 对单个标签分类（结合上下文交叉验证）。空白标签 → [TasteCategory.NOISE]。
     *
     * [overrides]（清洗后标签 → 维度）为 AI 升维分类缓存，**仅**作用于本地规则「其余视为题材」兜底的
     * 未知标签——已被词典 / 正则 / 交叉验证命中的标签不受影响。缓存为空（AI 未配置 / 未分类）时行为与之前完全一致。
     */
    fun classify(
        rawTag: String,
        context: Context = Context.EMPTY,
        overrides: Map<String, TasteCategory> = emptyMap(),
    ): TasteCategory {
        val f = factsOf(rawTag)
        val t = f.cleaned
        if (t.length < MIN_LEN) return TasteCategory.NOISE

        // 2-a. self-title 泄漏：标签与作品标题变体相同 → 噪声（如《未来日记》条目里的「未来日记」标签）。
        if (t in context.titleVariants) return TasteCategory.NOISE
        if (context.isTitleFragment(t)) return TasteCategory.NOISE

        // 1. 规则层：时间标签。
        if (f.time) return TasteCategory.TIME

        // 1. 规则层：改编来源（正则）。
        if (f.source) return TasteCategory.SOURCE

        // 2-b. 交叉验证：命中真实角色 / CV / staff 名。
        if (t in context.characterNames) return TasteCategory.CHARACTER
        if (t in context.cvNames) return TasteCategory.CV
        if (t in context.staffNames) return TasteCategory.STAFF
        if (t in context.sourceNames || t in KNOWN_SOURCE_NAMES) return TasteCategory.SOURCE
        // 标题、人名的罗马音碎片不是语义题材；例如 Steins / Gate 不能进入 TOPIC。
        if (context.isPersonFragment(t)) return TasteCategory.NOISE

        // 1. 规则层 + 2-c. 交叉验证：制作公司 / 厂牌。
        if (f.studio) {
            // 社区联想厂牌但真实 staff 不含该厂牌 → 噪声（如「京阿尼」联想标签，实际并非京阿尼制作）。
            return if (context.staffNames.isEmpty() || studioMatchesStaff(t, context.staffNames)) {
                TasteCategory.SOURCE
            } else {
                TasteCategory.NOISE
            }
        }

        // 3. 字典层（越具体越靠前：梗 > XP > 情节装置 > 题材）。
        f.dictionary?.let { return it }

        // 其余非内容元数据（媒介格式/放送状态/地区等）→ 噪声。
        if (f.meta) return TasteCategory.NOISE
        // 对「其余视为题材」兜底的未知标签，若 AI 升维缓存命中则用更精确维度；未命中仍归题材（与原行为一致）。
        overrides[t]?.let { return it }
        return TasteCategory.TOPIC
    }

    /**
     * 一个标签不看作品上下文就能定的那些判断（清洗、时间 / 来源正则、厂牌、字典、元数据）。标签反复出现在成千上万部作品里，
     * 这些结果按原始标签缓存起来，[classify] 只剩查表和上下文比对，结果和逐次计算完全一样。
     */
    private class TagFacts(
        val cleaned: String,
        val time: Boolean,
        val source: Boolean,
        val studio: Boolean,
        val dictionary: TasteCategory?,
        val meta: Boolean,
    )

    private val factsCache = java.util.concurrent.ConcurrentHashMap<String, TagFacts>()

    private fun factsOf(rawTag: String): TagFacts {
        factsCache[rawTag]?.let { return it }
        val t = clean(rawTag)
        val facts = TagFacts(
            cleaned = t,
            time = TIME_REGEX.matches(t),
            source = SOURCE_REGEX.containsMatchIn(t),
            studio = isStudioTag(t),
            dictionary = when {
                t in MEME_TAGS -> TasteCategory.MEME
                t in XP_TAGS -> TasteCategory.XP
                t in DEVICE_TAGS -> TasteCategory.DEVICE
                t in TasteTagTaxonomy.GENRE_TAGS -> TasteCategory.TOPIC
                else -> null
            },
            meta = TasteTagTaxonomy.isMeta(rawTag),
        )
        if (factsCache.size >= CACHE_LIMIT) factsCache.clear()
        factsCache[rawTag] = facts
        return facts
    }

    /**
     * 判断标签在**本地规则**下是否落入「其余视为题材」兜底——即未被时间 / 来源 / 厂牌 / 交叉验证 /
     * 各词典（MEME/XP/DEVICE/GENRE）命中、也非 [TasteTagTaxonomy.isMeta] 噪声的未知标签。这些正是需要 AI
     * 补充升维的候选；其余标签本地已能精确分类，无需（也不应）交给 AI。判定顺序与 [classify] 保持一致。
     */
    fun isUnknownTopicFallback(rawTag: String, context: Context = Context.EMPTY): Boolean {
        val f = factsOf(rawTag)
        val t = f.cleaned
        if (t.length < MIN_LEN) return false
        if (t in context.titleVariants) return false
        if (context.isTitleFragment(t)) return false
        if (f.time || f.source) return false
        if (t in context.contextualNames) return false
        if (t in KNOWN_SOURCE_NAMES) return false
        if (context.isPersonFragment(t)) return false
        if (f.studio) return false
        if (f.dictionary != null) return false
        if (f.meta) return false
        return true
    }

    /** 标签清洗：下划线/连字符转空格、折叠空白、trim、小写（与 [TasteTagTaxonomy] 口径一致）。结果按原文缓存。 */
    fun clean(raw: String): String {
        cleanCache[raw]?.let { return it }
        val cleaned = raw.replace('_', ' ').replace('-', ' ').trim().replace(WHITESPACE, " ").lowercase()
        if (cleanCache.size >= CACHE_LIMIT) cleanCache.clear()
        cleanCache[raw] = cleaned
        return cleaned
    }

    private val cleanCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val WHITESPACE = Regex("\\s+")

    /** 缓存条目上限：超过就整张清空（标签和人名的种类有限，正常用不到这么多）。 */
    private const val CACHE_LIMIT = 50_000

    /**
     * 一组名字（标题变体或人名）切好的词，判断「标签是不是其中某个名字的一个词」：和 [isNameFragment] 结果相同，
     * 只是名字只切一次。
     */
    private class NameFragments(names: Collection<String>) {
        private val nameSet: Set<String> = names.mapTo(HashSet()) { clean(it) }

        /** 词 → 含这个词的不同名字有几个。 */
        private val tokenCounts: Map<String, Int> = HashMap<String, Int>().also { counts ->
            for (name in nameSet) {
                tokensOf(name).forEach { token -> counts[token] = (counts[token] ?: 0) + 1 }
            }
        }

        fun contains(rawTag: String): Boolean {
            val tag = clean(rawTag)
            if (tag.length < MIN_NAME_FRAGMENT_LEN || !tag.any(Char::isLetter)) return false
            // 和标签完全相同的那个名字不算（[isNameFragment] 里 `filter { it != tag }`）。
            val self = if (tag in nameSet && tag in tokensOf(tag)) 1 else 0
            return (tokenCounts[tag] ?: 0) - self > 0
        }

        private fun tokensOf(name: String): Set<String> =
            name.split(NAME_TOKEN_SEPARATOR).filterTo(HashSet()) { it.length >= MIN_NAME_FRAGMENT_LEN }
    }

    /** True when a Latin-like tag is only one token taken from a title/person name. */
    fun isNameFragment(cleanedTag: String, names: Collection<String>): Boolean {
        val tag = clean(cleanedTag)
        if (tag.length < MIN_NAME_FRAGMENT_LEN || !tag.any(Char::isLetter)) return false
        return names.asSequence()
            .map(::clean)
            .filter { it != tag }
            .flatMap { it.split(NAME_TOKEN_SEPARATOR).asSequence() }
            .any { token -> token.length >= MIN_NAME_FRAGMENT_LEN && token == tag }
    }

    /** 从时间标签中提取 4 位年份（1900–2099）；无有效年份返回 `null`。 */
    private val YEAR_IN_TAG = Regex("(19|20)\\d{2}")

    /** 年代分档桶宽（年）。5 年一档：2010-2014 / 2015-2019……使同代不同季度聚合。 */
    private const val TIME_BUCKET_SPAN: Int = 5

    /**
     * 将时间标签归一化为 5 年区间键，例如 2014 年与 2013 年映射到 `2010-2014`。
     *
     * 根因：`TIME_REGEX` 精确匹配到「年+季度/月」，`2014年4月` 与 `2015年10月` 成为两个不同的 TIME 键，
     * 即便属同一时代也永不对齐，年代偏好被打散成一堆单例、无法聚合。按 [TIME_BUCKET_SPAN] 年分档后，
     * 同一时代的作品共享同一键，才能形成「你偏爱 2010 年代前半的番」这类可累积的年代画像。
     * 不含可解析年份的时间标签（如 `夏季`、`1月新番`）原样返回（仍属 TIME，但不参与年代分档）。
     */
    fun timeBucket(cleanedTag: String): String {
        val year = YEAR_IN_TAG.find(cleanedTag)?.value?.toIntOrNull()
            ?.takeIf { it in 1900..2099 } ?: return cleanedTag
        val start = year - (year % TIME_BUCKET_SPAN)
        return "$start-${start + TIME_BUCKET_SPAN - 1}"
    }

    /**
     * 年代档在界面上的写法：含今年的那一档写「近几年」（「2025-2029」读起来像在说未来），更早的写「2010–2014 年」；
     * 不是年代档的时间标签原样返回。
     */
    fun eraLabel(bucket: String, currentYear: Int = java.time.Year.now().value): String {
        val match = TIME_BUCKET_KEY.matchEntire(bucket) ?: return bucket
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        return if (end >= currentYear) "近几年" else "$start–$end 年"
    }

    private val TIME_BUCKET_KEY = Regex("(\\d{4})-(\\d{4})")

    private fun isStudioTag(cleaned: String): Boolean = TasteTagTaxonomy.isStudio(cleaned)

    /** 厂牌标签是否与真实 staff 名集合相符（任一 staff 名包含厂牌串或反之）。 */
    private fun studioMatchesStaff(studio: String, staffNames: Set<String>): Boolean =
        staffNames.any { it.contains(studio) || studio.contains(it) }

    private const val MIN_LEN: Int = 2
    private const val MIN_NAME_FRAGMENT_LEN: Int = 3
    private val KNOWN_SOURCE_NAMES: Set<String> = setOf("空想科学")
    private val NAME_TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /**
     * 时间标签正则（与 [TasteTagTaxonomy] 日期正则同口径，覆盖 2014年4月 / 2011年10月 / 2025年7月 等）。
     */
    private val TIME_REGEX = Regex(
        "^(\\d{4}|\\d{4}年|\\d{4}年\\d{1,2}月|\\d{4}[-./]\\d{1,2}|\\d{1,2}月|" +
            "\\d{4}年?[春夏秋冬]季?|\\d{1,2}月新番|\\d{4}[Qq][1-4])$",
    )

    /** 改编来源正则（文档：`(小说改|轻小说改|游戏改|GAL改|漫画改|漫改)` 等）。 */
    private val SOURCE_REGEX = Regex(
        "(轻小说改|小说改|游戏改|gal改|galgame改|eroge改|漫画改|漫改|手游改|18禁游戏改|" +
            "同名漫画改|同名小说改|同名游戏改|改编自|原作)",
    )

    /**
     * 情节装置字典（[TasteCategory.DEVICE]）：与题材并列、略低权重的「叙事/结构」装置。
     * 与 [TasteTagTaxonomy.GENRE_TAGS] 中重叠者优先归 DEVICE（更精确）。
     */
    val DEVICE_TAGS: Set<String> = setOf(
        "轮回", "时间循环", "时间旅行", "穿越", "转生", "重生", "党争", "大逃杀", "智斗", "博弈",
        "反转", "多线叙事", "群像", "群像剧", "下克上", "复仇", "励志", "成长", "逆袭", "龙傲天",
        "无敌", "阴谋", "权谋", "生存", "解谜", "单元剧", "叙述性诡计", "伏笔", "脑洞", "反乌托邦",
        "废土", "末世", "无限流", "经营", "养成", "复仇剧",
    )

    /**
     * 角色原型 / XP 字典（[TasteCategory.XP]）：受评论/高分作品约束的个性化细化层。
     * 常见标签包括妹妹、姐姐、病娇、伪娘、后宫和黑长直。
     */
    val XP_TAGS: Set<String> = setOf(
        "妹妹", "妹控", "兄控", "姐姐", "姐控", "病娇", "病娇女", "傲娇", "三无", "天然呆",
        "伪娘", "男娘", "女装", "性转", "变身", "后宫", "逆后宫", "黑长直", "双马尾", "马尾",
        "萝莉", "正太", "御姐", "女仆", "猫娘", "兽耳", "兽娘", "眼镜娘", "大小姐", "青梅竹马",
        "幼驯染", "巨乳", "贫乳", "妹系", "弟系", "девочка", "白毛", "银发", "黑长直少女",
        "傲娇女", "腹黑", "中二病", "中二", "无口",
    )

    /**
     * 梗 / 社区语气字典（[TasteCategory.MEME]）：只能弱用，避免梗驱动误判。
     * 常见社区表达包括嘟嘟噜、柴刀、史上最强跟踪狂和抽象。
     */
    val MEME_TAGS: Set<String> = setOf(
        "嘟嘟噜", "柴刀", "抽象", "真香", "鬼畜", "空耳", "神作", "粪作", "烂尾", "名场面",
        "名作之壁", "经费燃烧", "作画崩坏", "史上最强跟踪狂", "我妻有奶", "京阿尼脸", "刀", "发刀",
        "致郁系神作", "信仰", "厨力", "下饭", "下酒", "爷青回", "爷青结",
    )
}

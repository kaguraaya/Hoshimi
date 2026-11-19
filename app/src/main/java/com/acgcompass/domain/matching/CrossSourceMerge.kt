package com.acgcompass.domain.matching

import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import kotlin.math.ln

/**
 * 跨源合并通用算法。实现为纯函数，不包含针对具体作品的硬编码特判。
 *
 * 判定两个作品是否「同一作品」综合多字段信号：
 * - **标题归一化**：复用 [normalizeTitle]（NFKC 全半角统一、大小写、去标点/连字符/点号、空白折叠）。
 *   候选标题集合涵盖规范名 / 日文 / 罗马音 / 英文 / 别名——跨语言匹配依赖各源普遍填充的日文原名。
 * - **原文标题一致（强匹配）**：任一归一化标题变体完全相等。
 * - **年份 / 类型一致性**：作为合并的硬约束，冲突则不自动合并（区分系列总称 / 续作 / 外传 / 新章 /
 *   不同媒介），仅原文+年份+类型都不冲突才自动合并；原文一致但年份/类型冲突则不合并（留独立/人工确认）。
 * - **相似度兜底**：无完全相等时按最大跨变体相似度 ≥ [MERGE_THRESHOLD] 且年份/类型不冲突才合并。
 *
 * 热度 / 评分人数仅用于排序，绝不单独决定合并（避免误合并）。
 */

/** 跨源合并阈值（相似度兜底用，略低于自动链接阈值以便聚合展示）。 */
public const val MERGE_THRESHOLD: Double = 0.82

/** 收集作品全部可比较标题变体（规范名 / 日文 / 罗马音 / 英文 / 别名），去空白。 */
public fun Work.allTitleVariants(): List<String> = buildList {
    add(titles.canonical)
    titles.ja?.let(::add)
    titles.romaji?.let(::add)
    titles.en?.let(::add)
    addAll(titles.aliases)
}.filter { it.isNotBlank() }

/**
 * 正式标题（规范名 / 中文 / 日文 / 罗马音 / 英文），不含别名。季度序号和衍生标记只从这里取：别名里常有简称
 * （如「OP」），拿来判断会把正片误认成主题曲。
 */
internal fun Work.formalTitleVariants(): List<String> =
    listOfNotNull(titles.canonical, titles.cn, titles.ja, titles.romaji, titles.en).filter { it.isNotBlank() }

private fun maxVariantSimilarity(a: List<String>, b: List<String>): Double {
    var best = 0.0
    for (x in a) for (y in b) {
        val s = similarity(x, y)
        if (s > best) best = s
        if (best >= 1.0) return 1.0
    }
    return best
}

/**
 * 判断两个来源条目是否疑似同一作品。
 *
 * 规则顺序：
 * 1. 同 id → 是。
 * 2. 原文标题任一变体归一化后完全相等：年份与类型都不冲突 → 是（自动合并）；否则 → 否（冲突，保留独立/人工确认）。
 * 3. 年份冲突或类型冲突 → 否（不靠前缀相似强行合并）。
 * 4. 否则最大跨变体相似度 ≥ [MERGE_THRESHOLD] → 是。
 */
public fun sameWork(a: Work, b: Work): Boolean {
    if (sameSourceItem(a, b)) return true
    // 明确的季度或衍生类型不一致时，不使用标题相似度强行合并。
    if (seasonNumberOf(a.formalTitleVariants()) != seasonNumberOf(b.formalTitleVariants())) return false
    if (editionMarkersOf(a.formalTitleVariants()) != editionMarkersOf(b.formalTitleVariants())) return false
    val yearConflict = a.year != null && b.year != null && a.year != b.year
    val typeConflict = mediaTypesConflict(a.mediaType, b.mediaType)

    val ta = a.allTitleVariants().map { normalizeTitle(it) }.filter { it.isNotEmpty() }
    val tb = b.allTitleVariants().map { normalizeTitle(it) }.filter { it.isNotEmpty() }

    val exactShared = ta.any { it in tb }
    if (exactShared) return !yearConflict && !typeConflict
    // 紧凑（去空格）精确相等——处理 9nine / 9-nine / 9 -Nine- 这类仅空格/连字符差异。
    val ca = a.allTitleVariants().map { normalizeCompact(it) }.filter { it.length >= MIN_COMPACT_LEN }
    val cb = b.allTitleVariants().map { normalizeCompact(it) }.filter { it.length >= MIN_COMPACT_LEN }
    val compactShared = ca.any { it in cb }
    if (compactShared) return !yearConflict && !typeConflict
    if (yearConflict || typeConflict) return false
    // 核心标题包含关系——一个原文标题是另一个的核心标题加 Episode/副标题等补充时也应合并。
    // 仅当被包含的核心长度足够（避免「9 nine」这类过短系列前缀把不同分作误并）。
    if (coreContainmentMatch(ta, tb)) return true
    // 某些来源会省略标题中段的长副标题，但保留系列前缀和篇章后缀。前后锚点均足够长时可视为同作。
    if (orderedAnchorMatch(ta, tb)) return true
    return maxVariantSimilarity(ta, tb) >= MERGE_THRESHOLD
}

/** 各平台的数字 id 互不相关（Bangumi 的 21 和 AniList 的 21 不是同一部），只有同一来源的相同 id 才直接算同一作品。 */
private fun sameSourceItem(a: Work, b: Work): Boolean = a.id == b.id && a.primarySource == b.primarySource

/** 判断标题变体是否存在较长核心被另一标题完整包含的关系，并排除过短前缀。 */
private const val MIN_CORE_LEN: Int = 10

/**
 * 媒介类型是否冲突（family-aware）。同一「媒介家族」内不视为冲突，避免同一作品因各源标注不同
 * 媒介而漏合并：
 * - 游戏家族：GAME ↔ VN（视觉小说常被某些源标为游戏，反之亦然，如 9-nine 系列）。
 * - 文字家族：MANGA ↔ NOVEL（漫画 / 小说改编互标）。
 * 动画（ANIME）自成一类，与上述家族冲突（防止把动画版与游戏 / 小说版误并为同一卡）。
 */
private fun mediaTypesConflict(
    a: com.acgcompass.domain.model.MediaType,
    b: com.acgcompass.domain.model.MediaType,
): Boolean {
    if (a == b) return false
    val gameFamily = setOf(com.acgcompass.domain.model.MediaType.GAME, com.acgcompass.domain.model.MediaType.VN)
    val textFamily = setOf(com.acgcompass.domain.model.MediaType.MANGA, com.acgcompass.domain.model.MediaType.NOVEL)
    if (a in gameFamily && b in gameFamily) return false
    if (a in textFamily && b in textFamily) return false
    return true
}

/** 紧凑精确相等的最小长度，避免过短字符串被错误合并。 */
private const val MIN_COMPACT_LEN: Int = 4
private fun coreContainmentMatch(a: List<String>, b: List<String>): Boolean {
    for (x in a) for (y in b) {
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        if (shorter.length >= MIN_CORE_LEN && longer.contains(shorter)) return true
    }
    return false
}

private const val MIN_ANCHOR_LEN: Int = 4
private const val MIN_TOTAL_ANCHOR_LEN: Int = 10
private const val MIN_SHORTER_ANCHOR_COVERAGE: Double = 0.72

private fun orderedAnchorMatch(a: List<String>, b: List<String>): Boolean {
    for (left in a) for (right in b) {
        val x = left.replace(" ", "")
        val y = right.replace(" ", "")
        val shorterLength = minOf(x.length, y.length)
        if (shorterLength < MIN_TOTAL_ANCHOR_LEN) continue
        val prefix = commonPrefixLength(x, y)
        val suffix = commonSuffixLength(x, y, maxLength = shorterLength - prefix)
        val covered = prefix + suffix
        val anchorsLongEnough = prefix >= MIN_ANCHOR_LEN && suffix >= MIN_ANCHOR_LEN
        val coverageEnough = covered >= MIN_TOTAL_ANCHOR_LEN &&
            covered.toDouble() / shorterLength >= MIN_SHORTER_ANCHOR_COVERAGE
        if (anchorsLongEnough && coverageEnough) {
            return true
        }
    }
    return false
}

private fun commonPrefixLength(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[index] == b[index]) index += 1
    return index
}

private fun commonSuffixLength(a: String, b: String, maxLength: Int): Int {
    val limit = minOf(a.length, b.length, maxLength.coerceAtLeast(0))
    var length = 0
    while (length < limit && a[a.lastIndex - length] == b[b.lastIndex - length]) length += 1
    return length
}

/**
 * 衍生条目标记：两部作品的标记集合不同就不是同一作品。正片不带任何标记，所以主题曲、宣传片、迷你动画、
 * 映像特典这类条目即使标题和正片几乎一样，也不会被合并到正片上。
 */
private val EDITION_MARKERS = linkedMapOf(
    "side_story" to Regex("外[伝传]|番外|side\\s*story|spin[ -]?off", RegexOption.IGNORE_CASE),
    "movie" to Regex("劇場版|剧场版|the\\s*movie|movie", RegexOption.IGNORE_CASE),
    "ova" to Regex("(?:^|[^a-z])(ova|oad)(?:$|[^a-z])", RegexOption.IGNORE_CASE),
    "special" to Regex("特別編|特别篇|special", RegexOption.IGNORE_CASE),
    "recap" to Regex("総集編|总集篇|recap", RegexOption.IGNORE_CASE),
    "final" to Regex("完結編|完结篇|final\\s*season", RegexOption.IGNORE_CASE),
    "music" to Regex(
        "主題歌|主题曲|ノンクレジット|non[- ]?credit|キャラクターソング|character\\s*song|music\\s*video|" +
            "(?:^|[^a-z])(?:nc)?(?:op|ed|mv)(?:$|[^a-z])",
        RegexOption.IGNORE_CASE,
    ),
    "promo" to Regex("(?:^|[^a-z])(?:pv|cm)(?:$|[^a-z])|予告|ティザー|teaser|trailer|プロモーション", RegexOption.IGNORE_CASE),
    "mini" to Regex("ミニアニメ|mini\\s*anime|ぷち|petit|chibi", RegexOption.IGNORE_CASE),
    "bonus" to Regex("映像特典|特典映像|未放送|unaired|ピクチャードラマ|picture\\s*drama", RegexOption.IGNORE_CASE),
)

private fun editionMarkersOf(titles: List<String>): Set<String> = buildSet {
    titles.forEach { title ->
        EDITION_MARKERS.forEach { (marker, pattern) -> if (pattern.containsMatchIn(title)) add(marker) }
    }
}

/**
 * 两部作品是否属于同一季、同一形式：季度序号一致、衍生条目标记一致、年份与媒介类型不冲突。
 * 只看标题相似度挑候选的地方（如反查 Bangumi）先用它过滤，避免把续作、主题曲或衍生作品当成本作。
 */
public fun sameSeasonAndEdition(a: Work, b: Work): Boolean {
    val ta = a.formalTitleVariants()
    val tb = b.formalTitleVariants()
    val yearConflict = a.year != null && b.year != null && a.year != b.year
    return seasonNumberOf(ta) == seasonNumberOf(tb) &&
        editionMarkersOf(ta) == editionMarkersOf(tb) &&
        !yearConflict &&
        !mediaTypesConflict(a.mediaType, b.mediaType)
}

/**
 * 去外站（AniList / Tenrai / MAL）搜索时用的查询词，最多 [limit] 个。外站没有中文名，所以按有用程度排序：
 * 原名（通常是日文）→ 罗马音 → 英文名 → 含拉丁字母的较长别名（Bangumi 信息框里的英文名、罗马音）→
 * 非拉丁字母的别名；不用中文名（与原名相同时除外），也不用拉丁字母简称。
 */
public fun foreignSearchQueries(work: Work, limit: Int = FOREIGN_QUERY_LIMIT): List<String> {
    val t = work.titles
    val chinese = t.cn?.let(::normalizeTitle)
    // 「BTR」这类拉丁字母简称搜出来的结果不可靠，不用；非拉丁字母的别名（日文的其它写法等）保留在后面。
    val (latinAliases, otherAliases) = t.aliases.partition { alias -> alias.any { it in 'A'..'Z' || it in 'a'..'z' } }
    val usableLatin = latinAliases.filter { it.length >= MIN_LATIN_ALIAS_LEN }
    return (listOfNotNull(t.ja, t.romaji, t.en) + usableLatin + otherAliases + listOf(t.canonical))
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .filter { normalizeTitle(it) != chinese || normalizeTitle(it) == t.ja?.let(::normalizeTitle) }
        .distinctBy { normalizeTitle(it) }
        .take(limit)
}

private const val FOREIGN_QUERY_LIMIT = 3
private const val MIN_LATIN_ALIAS_LEN = 5

/**
 * 按置信度降序对疑似同一作品的候选进行贪心聚类。
 *
 * 候选和簇里任意一部是同一作品就可以加入（不同平台的标题写法不同，常要靠中间一部搭桥），
 * 但和簇里任何一部有硬冲突（季数、衍生类型、年份、媒介类型对不上）时不加入，
 * 避免 A≈B、B≈C 而 A 和 C 明显不是同一部时被串进同一簇。
 */
public fun clusterMatches(matches: List<WorkMatch>): List<List<WorkMatch>> {
    if (matches.isEmpty()) return emptyList()
    val ranked = matches.sortedByDescending { it.matchConfidence }
    val clusters = mutableListOf<MutableList<WorkMatch>>()
    for (m in ranked) {
        val target = clusters.firstOrNull { cluster ->
            cluster.any { sameWork(it.work, m.work) } && cluster.none { hardConflict(it.work, m.work) }
        }
        if (target != null) target.add(m) else clusters.add(mutableListOf(m))
    }
    return clusters
}

/** 两部作品明显不是同一部：季数、衍生类型、年份或媒介类型对不上（同一平台的同一编号除外）。 */
private fun hardConflict(a: Work, b: Work): Boolean {
    if (sameSourceItem(a, b)) return false
    val seasonDiffers = seasonNumberOf(a.formalTitleVariants()) != seasonNumberOf(b.formalTitleVariants())
    val editionDiffers = editionMarkersOf(a.formalTitleVariants()) != editionMarkersOf(b.formalTitleVariants())
    val yearDiffers = a.year != null && b.year != null && a.year != b.year
    return seasonDiffers || editionDiffers || yearDiffers || mediaTypesConflict(a.mediaType, b.mediaType)
}

/** 评分人数加成的满档（对数缩放）——达此人数即拿到最大加成。 */
public const val POPULARITY_BOOST_FULL: Double = 50_000.0

/** 评分人数加成上限——可弥补的相似度劣势上限（避免续作/小条目顶替正片）。 */
public const val POPULARITY_BOOST_MAX: Double = 0.18

/**
 * 代表条目的综合得分由相似度和评分人数对数加成组成。
 *
 * 人数加成按 `ln(1+人数)` 缩放、上限 [POPULARITY_BOOST_MAX]；据此让**正片**（评分人数远多）
 * 在相似度仅略低于**续作（第二季）/ 小条目**（PV / 广播剧 / 同人 / 废弃）时仍胜出，
 * 热度加成有上限，避免低票续作覆盖正片，也避免不相关的高热条目胜出。
 * 通用规则，不针对任何具体作品（人数仅用于排序、不决定合并）。
 */
public fun representativeScore(confidence: Double, popularity: Int): Double {
    val ratio = ln(1.0 + popularity.coerceAtLeast(0)) / ln(1.0 + POPULARITY_BOOST_FULL)
    return confidence + (POPULARITY_BOOST_MAX * ratio).coerceIn(0.0, POPULARITY_BOOST_MAX)
}

/**
 * 簇内**主导媒介类型**（按「条目数 + 评分人数」加权取最高）。用于选代表时在**同名混合媒介簇**
 * 中收敛到主流媒介——`mediaTypesConflict` 允许游戏家族（GAME↔VN）、文字家族（漫画↔小说）同簇，
 * 此时若代表落到少数派媒介（如 GALGAME 簇代表落到广播剧、或小说簇落到番外漫画），详情页评分人数/
 * 简介会错配。多数表决（评分人数加权）让代表归于主流媒介，修复跨源评分错配。纯通用规则，不针对具体作品。
 */
private fun dominantMediaType(cluster: List<WorkMatch>): com.acgcompass.domain.model.MediaType? = cluster
    .groupBy { it.work.mediaType }
    .mapValues { (_, ms) -> ms.size.toLong() + ms.sumOf { it.popularity.coerceAtLeast(0).toLong() } }
    .maxByOrNull { it.value }
    ?.key

/**
 * 选取簇代表：优先 Bangumi（提供中文名），否则综合得分最高者。
 *
 * 排序依次使用 [representativeScore] 和评分人数，均为降序。
 * 据此避免代表落到续作（第二季）或同名小条目，否则详情页评分人数严重偏低
 * （如正片「27000+ 人」被第二季「11 人」顶替）。通用规则，不针对任何具体作品。
 *
 * 在 Bangumi 候选中优先选择与簇主导媒介一致的条目（[dominantMediaType]），避免混合媒介簇
 * （游戏↔VN / 漫画↔小说家族）代表落到少数派媒介导致评分错配；无主导或同型时退化为原行为。
 */
public fun representativeOf(cluster: List<WorkMatch>): WorkMatch {
    val ranked = cluster.sortedWith(
        compareByDescending<WorkMatch> { representativeScore(it.matchConfidence.toDouble(), it.popularity) }
            .thenByDescending { it.popularity },
    )
    val dominant = dominantMediaType(cluster)
    return ranked.firstOrNull { it.sourceTag == SourceId.BANGUMI && it.work.mediaType == dominant }
        ?: ranked.firstOrNull { it.sourceTag == SourceId.BANGUMI }
        ?: ranked.firstOrNull { it.work.mediaType == dominant }
        ?: ranked.first()
}

/** 「第N期/第N季/第Nクール/第N部」与「N期」（中日，N 支持阿拉伯、全角和二至九）识别正则。 */
private val SEASON_CJK_REGEX = Regex("""第?\s*([0-9０-９一二三四五六七八九]+)\s*(?:期|季|クール|部)""")

/** 「2ndシーズン / シーズン2」。 */
private val SEASON_KATAKANA_REGEX =
    Regex("""([2-9])\s*(?:st|nd|rd|th)?\s*シーズン|シーズン\s*([2-9])""", RegexOption.IGNORE_CASE)

/** 标题末尾或分隔符前的罗马数字 II–V（含Ⅱ–Ⅴ），如「Overlord II」「ダンまち Ⅲ」。区分大小写，避免误伤普通单词。 */
private val SEASON_ROMAN_REGEX = Regex("""(?:^|[\s　:：・])(II|III|IV|V|Ⅱ|Ⅲ|Ⅳ|Ⅴ)(?=$|[\s　:：!！?？・])""")

/** 「続」「續」「续」作为独立的词出现（「続・夏目友人帳」「やはり俺の青春ラブコメはまちがっている。続」）。 */
private val SEQUEL_CJK_REGEX = Regex("""(?:^|[\s　・。!！?？])[続續续](?=$|[\s　・])""")

/** 「S2」「cour 2」。 */
private val SEASON_SHORT_EN_REGEX = Regex("""(?<![a-z0-9])(?:s|cour\s*)([2-9])(?![a-z0-9])""", RegexOption.IGNORE_CASE)

/** 「Season N / Nnd Season / Part N」（英，N≥2）识别正则。 */
private val SEASON_EN_REGEX = Regex(
    """(?:season\s*0*([2-9])|([2-9])\s*(?:st|nd|rd|th)\s*season|part\s*0*([2-9]))""",
    RegexOption.IGNORE_CASE,
)

/**
 * 从作品全部标题变体提取季度或续作序号：无明确续作标记视为第 1 季；
 * 命中「第N期/第N季」或「Season N / Nnd Season / Part N」（N≥2）取最大序号。仅匹配**明确续作模式**
 * （含「第…期/季」「season」「part」关键词），不把标题内任意数字（如 22/7、K-On!）误判为季度，降低误伤。
 * 供 [sameWork] 阻止正片与续作误合并。
 */
internal fun seasonNumberOf(titles: List<String>): Int {
    var max = 1
    for (raw in titles) {
        SEASON_CJK_REGEX.findAll(raw).forEach { m ->
            cjkOrArabicNumber(m.groupValues[1])?.let { if (it in 2..20) max = maxOf(max, it) }
        }
        listOf(SEASON_EN_REGEX, SEASON_KATAKANA_REGEX, SEASON_SHORT_EN_REGEX).forEach { regex ->
            regex.findAll(raw).forEach { m ->
                m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                    ?.let { if (it in 2..20) max = maxOf(max, it) }
            }
        }
        SEASON_ROMAN_REGEX.findAll(raw).forEach { m -> romanNumber(m.groupValues[1])?.let { max = maxOf(max, it) } }
        if (SEQUEL_CJK_REGEX.containsMatchIn(raw)) max = maxOf(max, 2)
    }
    return max
}

private fun romanNumber(s: String): Int? = when (s) {
    "II", "Ⅱ" -> 2
    "III", "Ⅲ" -> 3
    "IV", "Ⅳ" -> 4
    "V", "Ⅴ" -> 5
    else -> null
}

/** 解析阿拉伯 / 全角 / 简单中日数字（二~九）为整数；无法解析返回 null。 */
private fun cjkOrArabicNumber(s: String): Int? {
    val half = s.map { c -> if (c in '０'..'９') ('0'.code + (c.code - '０'.code)).toChar() else c }.joinToString("")
    half.toIntOrNull()?.let { return it }
    return when (half) {
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "七" -> 7
        "八" -> 8
        "九" -> 9
        else -> null
    }
}

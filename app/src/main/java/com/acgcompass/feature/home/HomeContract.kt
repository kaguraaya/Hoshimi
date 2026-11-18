package com.acgcompass.feature.home

import androidx.annotation.VisibleForTesting
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.Cta
import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 首页——今日决策中心的 UI 契约。
 *
 * [HomeUiState] 是纯展示模型，由 [HomeViewModel] 把领域数据（[Work] + [BacklogItem]）折叠为
 * 可直接渲染的字符串 / 列表，使界面与领域层解耦、可独立预览与单元测试。
 *
 * 缺失即标记、绝不伪造：评分等暂不可得的字段一律为 `null`，
 * 由 [com.acgcompass.core.designsystem.WorkCard] 显示「暂无数据」。
 *
 * 任务边界：同步提醒（[syncReminder]）与今日补番签（[dailyDraw]）由 实现。
 */
data class HomeUiState(
    /** 今日状态——重定义为「今晚看什么」快捷入口的心情 chips（点击带预填标签跳推荐器）。 */
    val moods: List<HomeMood> = HomeMood.DEFAULTS,
    /** 继续看 / 读 / 玩区。当前无进度数据源时为空，由界面渲染友好占位。 */
    val continueItems: List<ContinueItem> = emptyList(),
    /** 待补池概览。 */
    val backlogSummary: BacklogSummary = BacklogSummary(),
    /** 同步提醒。仅在至少配置一个可同步源时非空。 */
    val syncReminder: SyncReminder? = null,
    /** 今日补番签。每日宜 / 忌建议。 */
    val dailyDraw: DailyDraw? = null,
    /** 启用的首页模块 key 集合。 */
    val enabledModules: Set<String> = com.acgcompass.data.datastore.HomeModulePrefs.DEFAULT_KEYS,
    /** 首页即将放送的时间表条目。 */
    val seasonalHot: List<HomeWorkCard> = emptyList(),
    /** 本季正在播出或即将开播的作品。 */
    val seasonalNew: List<HomeWorkCard> = emptyList(),
    val upcomingBroadcasts: List<BroadcastScheduleEntry> = emptyList(),
    val scheduleRefreshing: Boolean = false,
    val scheduleError: String? = null,
)

/**
 * 今日状态（心情）选项。点击某心情即带着 [presetTags]
 * 跳转推荐器并预填「想看的标签」筛选。[action] 为稳定标识；[presetTags] 为该心情映射的社区标签关键词
 * （命中作品社区标签即算匹配，空则不预填、仅进入推荐器）。
 */
data class HomeMood(
    val label: String,
    val action: String,
    val presetTags: List<String> = emptyList(),
) {
    companion object {
        /** 默认今日状态选项；每项映射到推荐器的预填标签。 */
        val DEFAULTS: List<HomeMood> = listOf(
            HomeMood("轻松日常", "relaxed", listOf("日常", "治愈", "搞笑")),
            HomeMood("致郁虐心", "gut_punch", listOf("致郁")),
            HomeMood("热血燃向", "hot_blooded", listOf("热血")),
            HomeMood("恋爱", "romance", listOf("恋爱")),
            HomeMood("悬疑推理", "mystery", listOf("悬疑", "推理")),
            HomeMood("补经典名作", "masterpiece", listOf("神作")),
            HomeMood("科幻", "sci_fi", listOf("科幻")),
            HomeMood("奇幻冒险", "fantasy", listOf("奇幻", "冒险")),
            HomeMood("小众电波", "niche", listOf("电波")),
        )
    }
}

/**
 * 携带作品 id 的卡片包装。
 *
 * [WorkCardUiModel] 是纯展示模型、不含主键；首页各区点击卡片需进入对应作品详情
 * （`onOpenWork(workId)`），故在此把 [workId] 与卡片成对保存，供界面回调使用。
 *
 * @property workId 作品 id（待补条目主键）。
 * @property card 卡片展示模型。
 */
data class HomeWorkCard(
    val workId: String,
    val card: WorkCardUiModel,
)

/**
 * 「继续看 / 读 / 玩」单条目。
 *
 * @property workId 作品 id，供点击进入详情。
 * @property card 作品卡片展示模型。
 * @property progressText 进度文案（如「第 5 / 12 话」）；`null` 表示进度暂无数据。
 * @property nextUpText 下次应继续的位置文案（如「下一话：第 6 话」）；`null` 表示暂无数据。
 */
data class ContinueItem(
    val workId: String,
    val card: WorkCardUiModel,
    val progressText: String? = null,
    val nextUpText: String? = null,
)

/**
 * 待补池概览：数量 / 最近加入 / 吃灰最久 / 短篇可补 / 高匹配。
 *
 * @property totalCount 待补总数。
 * @property recentlyAdded 最近加入的作品卡片；`null` 表示无数据。
 * @property longestDust 吃灰最久的作品卡片；`null` 表示无数据。
 * @property shortPickable 短篇可补（今晚可看完，[CompletionCost.TONIGHT]）作品卡片。
 * @property highMatch 高匹配作品卡片；数据源尚未接入时为空列表。
 */
data class BacklogSummary(
    val totalCount: Int = 0,
    val recentlyAdded: HomeWorkCard? = null,
    val longestDust: HomeWorkCard? = null,
    val shortPickable: List<HomeWorkCard> = emptyList(),
    val highMatch: List<HomeWorkCard> = emptyList(),
)

/**
 * 同步提醒。
 *
 * 仅当用户至少配置一个可同步数据源（Bangumi / AniList / VNDB）时展示，
 * 列出每个已配置源的最近同步 / 测试时间、整体失败标记，并提供手动同步入口。
 *
 * @property lines 每个已配置可同步源一行的展示文案（如「Bangumi · 刚刚测试」/「AniList · 尚未同步」）。
 * @property hasFailure 是否存在任一源最近测试失败（用于高亮失败提醒）。
 */
data class SyncReminder(
    val lines: List<SyncReminderLine>,
    val hasFailure: Boolean,
)

/**
 * 同步提醒单行。
 *
 * @property sourceLabel 数据源展示名（Bangumi / AniList / VNDB）。
 * @property statusText 最近同步 / 测试状态文案（缺失即标记「尚未同步」，绝不伪造）。
 * @property failed 该源最近一次测试是否失败。
 */
data class SyncReminderLine(
    val sourceLabel: String,
    val statusText: String,
    val failed: Boolean,
)

/** 今日补番签：今日宜 / 忌建议。[byAi] 为 true 表示由 AI 结合待补池生成。 */
data class DailyDraw(
    val shouldText: String,
    val shouldNotText: String,
    val byAi: Boolean = false,
)

/**
 * 把领域 [BacklogItem]（可选关联其 [Work]）折叠为首页所需的一条聚合输入。
 *
 * @property item 待补条目。
 * @property work 关联作品；当本地尚未解析到作品（如仅 id）时为 `null`，卡片以兜底展示。
 */
data class HomeBacklogEntry(
    val item: BacklogItem,
    val work: Work?,
)

/** 首页空态「下一步」引导：去批量导入补番清单。 */
internal val HOME_EMPTY_CTA: Cta = Cta(label = "批量导入补番清单", action = AppHomeAction.OPEN_IMPORT)

/**
 * 从公共发现池构建本季新番首页模块卡片。**以 Bangumi 为主**，
 * 数量不足时用其它公共源（Jikan/AniList）补足。按年份降序近似「近期」；无公共数据时为空。
 */
internal fun buildSeasonalHot(works: List<Work>, limit: Int = 6): List<HomeWorkCard> {
    val bangumiSorted = works.asSequence()
        .filter { it.primarySource == SourceId.BANGUMI }
        .sortedByDescending { it.year ?: 0 }
        .toList()
    val othersSorted = works.asSequence()
        .filter { it.primarySource != SourceId.BANGUMI }
        .sortedByDescending { it.year ?: 0 }
        .toList()
    return (bangumiSorted + othersSorted)
        .take(limit)
        .map { w ->
            HomeWorkCard(
                workId = w.id,
                card = WorkCardUiModel(
                    coverUrl = w.coverUrl,
                    title = w.titles.canonical,
                    subtitle = listOfNotNull(w.titles.ja ?: w.titles.en, w.year?.toString()).joinToString(" · "),
                    type = w.mediaType.displayLabel(),
                    ratingText = null,
                    sourceTags = listOf(w.primarySource.displayLabel()),
                ),
            )
        }
}

/** 从公共池构建本季新番，在播作品优先，其次为本年度即将开播作品。 */
internal fun buildSeasonalNew(works: List<Work>, limit: Int = 6): List<HomeWorkCard> {
    val currentYear = LocalDate.now().year
    val airing = works.asSequence()
        .filter { it.status == ReleaseStatus.RELEASING }
        .sortedByDescending { it.year ?: 0 }
        .toList()
    val upcoming = works.asSequence()
        .filter { it.status == ReleaseStatus.NOT_RELEASED && (it.year ?: currentYear) >= currentYear }
        .sortedByDescending { it.year ?: 0 }
        .toList()
    return (airing + upcoming)
        .distinctBy { it.id }
        .take(limit)
        .map { work ->
            HomeWorkCard(
                workId = work.id,
                card = WorkCardUiModel(
                    coverUrl = work.coverUrl,
                    title = work.titles.canonical,
                    subtitle = listOfNotNull(work.titles.ja ?: work.titles.en, work.year?.toString())
                        .joinToString(" · "),
                    type = work.mediaType.displayLabel(),
                    ratingText = null,
                    sourceTags = listOf(work.primarySource.displayLabel()),
                ),
            )
        }
}

/** 首页空态友好文案。 */
internal const val HOME_EMPTY_MESSAGE: String = "待补池暂无作品，可通过搜索或导入添加"

/**
 * 「继续看 / 读 / 玩」——直接取 Bangumi「在看」收藏（来自同步入库的 user_collections），
 * 按最近更新排序。进度文案优先显示「第 N / 总集 话」；下一话提示便于继续。
 */
@VisibleForTesting
internal fun buildContinueItems(
    collections: List<com.acgcompass.data.local.entity.UserCollectionEntity>,
    worksById: Map<String, Work>,
    limit: Int = 3,
): List<ContinueItem> = collections.asSequence()
    .filter { it.status == "在看" }
    .sortedByDescending { it.updatedAt }
    .map { c ->
        val work = worksById[c.localWorkId]
        val card = WorkCardUiModel(
            coverUrl = work?.coverUrl,
            title = work?.titles?.canonical ?: c.localWorkId,
            subtitle = work?.let { workSubtitle(it) } ?: "",
            type = work?.mediaType?.displayLabel() ?: "",
            ratingText = null,
            sourceTags = listOfNotNull(work?.primarySource?.displayLabel()),
        )
        val total = work?.units?.episodes?.takeIf { it > 0 }
        val progressText = c.progress?.takeIf { it > 0 }?.let { p ->
            if (total != null) "第 $p / $total 话" else "第 $p 话"
        }
        val nextUp = c.progress?.takeIf { it >= 0 }?.let { p ->
            if (total != null && p >= total) "已看完" else "下一话：第 ${p + 1} 话"
        }
        ContinueItem(
            workId = c.localWorkId,
            card = card,
            progressText = progressText,
            nextUpText = nextUp,
        )
    }
    .take(limit)
    .toList()

/** 首页内部动作标识（空态 CTA 等用以路由）。 */
internal object AppHomeAction {
    const val OPEN_IMPORT = "open_import"
}

/**
 * 由待补条目集合构建待补池概览。纯函数，便于单元测试。
 *
 * - 最近加入：[BacklogItem.addedAt] 最大者。
 * - 吃灰最久：[BacklogItem.dustDays] 最大者（无吃灰，即全为 0 时仍取其一以展示）。
 * - 短篇可补：关联作品 [CompletionCost.TONIGHT] 的条目。
 * - 高匹配：依赖匹配置信度数据，当前数据层未暴露，保持为空占位（不伪造）。
 */
@VisibleForTesting
internal fun buildBacklogSummary(entries: List<HomeBacklogEntry>): BacklogSummary {
    if (entries.isEmpty()) return BacklogSummary()

    val recently = entries.maxByOrNull { it.item.addedAt }
    val dustiest = entries.maxByOrNull { it.item.dustDays }
        ?.takeIf { it.item.dustDays > 0 }
    val short = entries.filter { it.work?.completionCost == CompletionCost.TONIGHT }

    return BacklogSummary(
        totalCount = entries.size,
        recentlyAdded = recently?.toWorkCard(),
        longestDust = dustiest?.toWorkCard(),
        shortPickable = short.map { it.toWorkCard() },
        highMatch = emptyList(),
    )
}

/**
 * 把一条 [HomeBacklogEntry] 折叠为带作品 id 的卡片 [HomeWorkCard]。
 *
 * 关联作品缺失时以条目 id 兜底标题、其余字段走「暂无数据」语义，绝不伪造。
 */
@VisibleForTesting
internal fun HomeBacklogEntry.toWorkCard(): HomeWorkCard {
    val w = work
    val card = WorkCardUiModel(
        coverUrl = w?.coverUrl,
        title = w?.titles?.canonical ?: item.workId,
        subtitle = w?.let { workSubtitle(it) }.orEmpty(),
        type = w?.mediaType?.displayLabel() ?: "",
        // 评分需经聚合用例单独获取，首页概览不加载，保持「暂无数据」。
        ratingText = null,
        sourceTags = listOfNotNull(w?.primarySource?.displayLabel()),
        backlogStatus = backlogStatusText(item),
        completionCost = w?.completionCost?.displayLabel(),
        moodRiskTags = item.moodTags + item.riskTags,
    )
    return HomeWorkCard(workId = item.workId, card = card)
}

/** 拼接副标题：日文 / 罗马音 / 英文名（取其一）+ 年份。 */
private fun workSubtitle(work: Work): String {
    val altTitle = work.titles.ja ?: work.titles.romaji ?: work.titles.en
    return listOfNotNull(altTitle, work.year?.toString()).joinToString(separator = " · ")
}

/** 待补状态文案：吃灰中显示吃灰天数，否则显示「想看」。 */
@VisibleForTesting
internal fun backlogStatusText(item: BacklogItem): String = when {
    item.inDustMuseum -> "吃灰馆"
    item.dustDays > 0 -> "吃灰 ${item.dustDays} 天"
    else -> "想看"
}

/** 媒介类型展示文案。 */
internal fun MediaType.displayLabel(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
}

/** 补完成本展示文案。 */
internal fun CompletionCost.displayLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚"
    CompletionCost.WEEKEND -> "周末"
    CompletionCost.LONG_HAUL -> "长期坑"
}

/** 数据源展示文案。 */
internal fun SourceId.displayLabel(): String = when (this) {
    SourceId.BANGUMI -> "Bangumi"
    SourceId.ANILIST -> "AniList"
    SourceId.TENRAI -> "Tenrai"
    SourceId.JIKAN -> "Jikan"
    SourceId.MAL -> "MAL"
    SourceId.VNDB -> "VNDB"
}

// ---------------------------------------------------------------------------
// 同步提醒
// ---------------------------------------------------------------------------

/** 可同步的数据源及其展示名（仅 Bangumi / AniList / VNDB 参与同步提醒）。 */
private val SYNCABLE_SOURCES: List<Pair<CredentialSourceId, String>> = listOf(
    CredentialSourceId.BANGUMI to "Bangumi",
    CredentialSourceId.ANILIST to "AniList",
    CredentialSourceId.VNDB to "VNDB",
)

/**
 * 由凭据元数据构建同步提醒。纯函数，便于单元测试。
 *
 * - 仅纳入**已配置**（[CredentialStatus.configured]）的可同步源（Bangumi / AniList / VNDB）。
 * - 无任何已配置可同步源时返回 `null`（界面不展示该区，符合「WHERE 已配置」条件）。
 * - 每行显示最近同步 / 测试时间；从未测试显示「尚未同步」（缺失即标记，绝不伪造）。
 * - 任一源状态为 [CredentialStatus.Status.TEST_FAILED] 时置 [SyncReminder.hasFailure]。
 *
 * @param statuses 来自 `CredentialStore.observeStatus()` 的各源元数据。
 * @param nowMillis 当前时间戳（毫秒），用于计算相对时间，便于测试注入。
 */
@VisibleForTesting
internal fun buildSyncReminder(
    statuses: Map<CredentialSourceId, CredentialStatus>,
    nowMillis: Long,
    bangumiSync: com.acgcompass.data.sync.SyncStatus? = null,
): SyncReminder? {
    // 仅在「超过 24 小时未同步」（或从未同步）时展示同步提醒，避免刚同步完仍频繁提醒。
    // 例外：上次同步存在失败时始终提醒。
    val recentlySynced = bangumiSync != null &&
        bangumiSync.hasSynced &&
        bangumiSync.lastError == null &&
        (nowMillis - bangumiSync.lastSyncAt) <= TWENTY_FOUR_HOURS_MILLIS
    if (recentlySynced) return null

    val lines = SYNCABLE_SOURCES.mapNotNull { (source, label) ->
        val status = statuses[source] ?: return@mapNotNull null
        if (!status.configured) return@mapNotNull null
        // Bangumi 行优先展示真实同步状态（已同步 / 最后同步时间 / 本地收藏数 / 上次结果 / 错误）。
        if (source == CredentialSourceId.BANGUMI && bangumiSync != null) {
            val failed = bangumiSync.lastError != null
            return@mapNotNull SyncReminderLine(
                sourceLabel = label,
                statusText = bangumiSyncStatusText(bangumiSync),
                failed = failed,
            )
        }
        val failed = status.status == CredentialStatus.Status.TEST_FAILED
        SyncReminderLine(
            sourceLabel = label,
            statusText = syncStatusText(status, nowMillis),
            failed = failed,
        )
    }
    if (lines.isEmpty()) return null
    return SyncReminder(lines = lines, hasFailure = lines.any { it.failed })
}

/** 24 小时（毫秒），用于同步提醒的展示门控。 */
private const val TWENTY_FOUR_HOURS_MILLIS: Long = 24L * 60 * 60 * 1000

/** Bangumi 行的同步状态文案。 */
private fun bangumiSyncStatusText(s: com.acgcompass.data.sync.SyncStatus): String = when {
    s.lastError != null && !s.hasSynced -> "同步失败：${s.lastError}"
    s.lastError != null -> "上次同步失败：${s.lastError}（最后成功 ${s.lastSyncText()}）"
    s.hasSynced -> "已同步 · ${s.lastSyncText()} · 本地 ${s.localCollectionCount} 部 · ${s.resultSummary()}"
    else -> "尚未同步（已配置，点下方立即同步）"
}

/** 单源同步状态文案：失败优先标记，否则展示最近测试相对时间或「尚未同步」。 */
private fun syncStatusText(status: CredentialStatus, nowMillis: Long): String {
    val tested = status.lastTestedAt ?: return "尚未同步"
    val relative = relativeTimeText(nowMillis - tested)
    return when (status.status) {
        CredentialStatus.Status.TEST_FAILED -> "最近测试失败 · $relative"
        else -> "最近同步 · $relative"
    }
}

/** 把时间差（毫秒）折叠为中文相对时间文案。负值（时钟漂移）按「刚刚」处理。 */
private fun relativeTimeText(deltaMillis: Long): String {
    val minutes = deltaMillis / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

// ---------------------------------------------------------------------------
// 今日补番签
// ---------------------------------------------------------------------------

/** 今日宜建议候选池。 */
private val DRAW_SHOULD: List<String> = listOf(
    "补一部短篇，今晚就能看完",
    "翻翻吃灰最久的那部，给它一个机会",
    "挑一部神作补课，认真沉浸一回",
    "重温一部老番，找回当初的心动",
    "开一部新番，先看第一集合不合口味",
    "清一部追了一半的坑",
    "按今天的心情随手挑一部",
)

/** 今日忌建议候选池。 */
private val DRAW_SHOULD_NOT: List<String> = listOf(
    "忌一口气刷完，留点余味明天再续",
    "忌再往待补池里乱塞新坑",
    "忌边刷手机边看，错过好镜头",
    "忌挑超长篇硬啃，今晚时间不够",
    "忌剧透围观，保护好惊喜",
    "忌只看评分挑番，合口味最重要",
    "忌熬夜赶进度，番补完了人也得在",
)

/**
 * 生成今日补番签。**纯确定性函数**：同一 [date]（与可选 [backlogSize]）必产同一结果，
 * 便于单元测试与跨进程一致展示。
 *
 * 以日期序数派生稳定种子，再结合星期与待补池规模微调索引，使「宜 / 忌」每日不同但当日固定。
 *
 * @param date 目标日期（默认今天）。
 * @param backlogSize 待补池规模，参与种子让建议与个人池子相关；缺省 0。
 */
@VisibleForTesting
internal fun buildDailyDraw(date: LocalDate = LocalDate.now(), backlogSize: Int = 0): DailyDraw {
    val seed = date.toEpochDay() + backlogSize.toLong() * 31L
    val shouldIndex = Math.floorMod(seed, DRAW_SHOULD.size.toLong()).toInt()
    // 忌项叠加星期偏移，避免与宜项索引同步移动（更有「抽签」随机感）。
    val dowOffset = date.dayOfWeek.value.toLong()
    val shouldNotIndex = Math.floorMod(seed + dowOffset, DRAW_SHOULD_NOT.size.toLong()).toInt()
    return DailyDraw(
        shouldText = DRAW_SHOULD[shouldIndex],
        shouldNotText = DRAW_SHOULD_NOT[shouldNotIndex],
    )
}

/** AI 补番签每天生成一次：缓存按日期判断能否复用。 */
internal fun aiDailyDrawFingerprint(date: LocalDate): String = "daily-draw:$date"

/** AI 补番签的提示词版本；提示词改动后提高它，让当天的缓存失效。 */
internal const val AI_DAILY_DRAW_PROMPT_VERSION = 1

private const val AI_DRAW_MAX_ENTRIES = 12
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * AI 补番签的输入：日期、星期是否周末，以及待补池里最多 [AI_DRAW_MAX_ENTRIES] 部作品（作品名、集数、加入天数）。
 * 先列加入最久的，让「宜」有机会点到吃灰的作品。只包含作品名和集数，不含评分、短评等个人记录。
 */
@VisibleForTesting
internal fun buildAiDailyDrawInput(date: LocalDate, entries: List<HomeBacklogEntry>, nowMillis: Long): String =
    buildString {
        val weekday = date.dayOfWeek
        appendLine("日期：$date（${weekdayLabel(weekday)}，${if (weekday.isWeekend()) "周末" else "工作日"}）")
        val named = entries.filter { it.work != null }.sortedBy { it.item.addedAt }.take(AI_DRAW_MAX_ENTRIES)
        appendLine("待补池共 ${entries.size} 部，以下是其中 ${named.size} 部：")
        named.forEach { entry ->
            val work = entry.work ?: return@forEach
            val title = work.titles.cn ?: work.titles.canonical
            val episodes = work.units.episodes?.let { "，$it 集" }.orEmpty()
            val days = ((nowMillis - entry.item.addedAt) / MILLIS_PER_DAY).coerceAtLeast(0)
            appendLine("- $title$episodes，加入 $days 天")
        }
    }

private fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}

/** 是否周末。 */
private fun DayOfWeek.isWeekend(): Boolean = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

package com.acgcompass.feature.mine

import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.SourceId
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.Work

/**
 * 「我的」页 UI 状态契约。
 *
 * 仅承载**可安全展示**的非敏感数据：账号状态来自 [CredentialStore.observeStatus] 的元数据
 * （是否配置 / 最后测试时间 / 状态），**绝不**包含任何明文凭据。
 *
 * 统计遵循「缺失即标记、绝不伪造」：暂时无法从本地数据计算的指标一律为
 * `null`，由 UI 渲染为「暂无数据」，不编造数值。
 */

/** 「我的」页固定展示的账号顺序：Bangumi → AniList → MAL → VNDB → AI。 */
internal val MINE_ACCOUNT_ORDER: List<SourceId> = listOf(
    SourceId.BANGUMI,
    SourceId.ANILIST,
    SourceId.MAL,
    SourceId.VNDB,
    SourceId.AI_PROVIDER,
)

/** 账号在「我的」页的可读名。 */
fun SourceId.mineLabel(): String = when (this) {
    SourceId.BANGUMI -> "Bangumi"
    SourceId.ANILIST -> "AniList"
    SourceId.JIKAN -> "Jikan"
    SourceId.MAL -> "MyAnimeList"
    SourceId.VNDB -> "VNDB"
    SourceId.AI_PROVIDER -> "AI 服务"
}

/** 凭据状态的可读文案。null / 未知一律视为「未配置」，不伪造状态。 */
fun CredentialStatus?.mineStatusText(): String = when (this?.status) {
    CredentialStatus.Status.TEST_SUCCESS -> "连接正常"
    CredentialStatus.Status.TEST_FAILED -> "连接失败"
    CredentialStatus.Status.CONFIGURED -> "已配置"
    else -> if (this?.configured == true) "已配置" else "未配置"
}

/**
 * 单个账号的状态行。仅含非敏感元数据。
 *
 * @property sourceId 数据源标识。
 * @property label 可读名。
 * @property configured 是否已配置（加密存储中存在凭据）。
 * @property statusText 状态文案。
 * @property lastTestedAt 最后测试时间戳（毫秒）；从未测试为 `null` → UI 显示「从未测试」。
 */
data class AccountStatusRow(
    val sourceId: SourceId,
    val label: String,
    val configured: Boolean,
    val statusText: String,
    val lastTestedAt: Long?,
)

/** 社区标签覆盖的收藏作品数。 */
data class TagCount(
    val name: String,
    val count: Int,
)

/**
 * 个人收藏统计输入，由同步入库的用户收藏映射而来，不含敏感信息。
 * @property status 想看/在看/看过/搁置/抛弃；缺失为 null。
 * @property rating 个人评分 1–10；缺失为 null。
 * @property workId 本地作品标识，用于关联作品社区标签。
 */
data class UserCollectionStat(
    val workId: String,
    val status: String?,
    val rating: Int?,
)

/**
 * 数据统计。可计算的指标为非空，暂时无法计算的为 `null`（UI 显示「暂无数据」）。
 *
 * 当前仅「想看」可由本地待补池真实计算；其余个人观看状态（看过 / 在看 / 搁置 / 抛弃）与
 * 个人评分（平均分 / 最高分）暂未在本地建模，故为 `null`，绝不伪造。常见标签来自
 * 已缓存作品的社区标签。
 *
 * @property watched 看过数（暂无来源 → null）。
 * @property watching 在看数（暂无来源 → null）。
 * @property wantToWatch 想看数（= 本地待补池条目数）。
 * @property onHold 搁置数（暂无来源 → null）。
 * @property dropped 抛弃数（暂无来源 → null）。
 * @property averageRating 平均评分（暂无个人评分来源 → null）。
 * @property highestRating 最高分（暂无个人评分来源 → null）。
 * @property commonTags 常见番剧标签；计数表示该标签覆盖的不同收藏作品数。
 */
data class MineStats(
    val watched: Int? = null,
    val watching: Int? = null,
    val wantToWatch: Int? = null,
    val onHold: Int? = null,
    val dropped: Int? = null,
    val averageRating: Float? = null,
    val highestRating: Float? = null,
    val commonTags: List<TagCount> = emptyList(),
)

/**
 * 「我的」页聚合 UI 状态。
 *
 * @property accounts 账号状态行（固定顺序）。
 * @property stats 数据统计。
 */
data class MineUiState(
    val accounts: List<AccountStatusRow> = emptyList(),
    val stats: MineStats = MineStats(),
    val syncStatus: com.acgcompass.data.sync.SyncStatus = com.acgcompass.data.sync.SyncStatus(),
)

/**
 * 由凭据元数据按固定顺序构建账号状态行。纯函数，便于测试。
 *
 * @param statusMap [CredentialStore.observeStatus] 发射的非敏感元数据映射。
 */
fun buildAccountRows(statusMap: Map<SourceId, CredentialStatus>): List<AccountStatusRow> =
    MINE_ACCOUNT_ORDER.map { source ->
        val status = statusMap[source]
        AccountStatusRow(
            sourceId = source,
            label = source.mineLabel(),
            configured = status?.configured == true,
            statusText = status.mineStatusText(),
            lastTestedAt = status?.lastTestedAt,
        )
    }

/** 常见标签展示上限。 */
private const val MAX_COMMON_TAGS = 8

/**
 * 由本地作品与待补池真实计算数据统计。纯函数，便于测试。
 *
 * 「不伪造」原则：仅「想看」由待补池条目数得出；其余个人状态与个人评分暂无本地来源，保持 `null`。
 * 常见标签按其覆盖的不同作品数降序取前 [MAX_COMMON_TAGS]。
 *
 * @param works 本地规范化作品（保留参数以备后续接入更多统计；当前不参与个人状态计算）。
 * @param backlog 本地待补池条目。
 */
fun buildMineStats(
    works: List<Work>,
    backlog: List<BacklogItem>,
    collections: List<UserCollectionStat> = emptyList(),
): MineStats {
    // 已从 Bangumi 同步个人收藏时，统计基于真实个人数据；否则回退到本地待补池（仅「想看」可得）。
    if (collections.isNotEmpty()) {
        val ratings = collections.mapNotNull { it.rating }.filter { it in 1..10 }
        fun countOf(status: String) = collections.count { it.status == status }
        return MineStats(
            watched = countOf("看过"),
            watching = countOf("在看"),
            wantToWatch = countOf("想看"),
            onHold = countOf("搁置"),
            dropped = countOf("抛弃"),
            averageRating = ratings.takeIf { it.isNotEmpty() }?.let { it.sum().toFloat() / it.size },
            highestRating = ratings.maxOrNull()?.toFloat(),
            commonTags = communityTags(works, collections.mapTo(HashSet()) { it.workId }),
        )
    }

    return MineStats(
        // 个人观看状态暂未同步；仅「想看」可由待补池真实计算，其余保持「暂无数据」。
        watched = null,
        watching = null,
        wantToWatch = backlog.size,
        onHold = null,
        dropped = null,
        averageRating = null,
        highestRating = null,
        commonTags = communityTags(works, backlog.mapTo(HashSet()) { it.workId }),
    )
}

/** Count a community tag at most once per work, even if a source returned duplicates. */
private fun communityTags(works: List<Work>, selectedWorkIds: Set<String>): List<TagCount> = works
    .asSequence()
    .filter { it.id in selectedWorkIds }
    .flatMap { work -> work.tags.asSequence().map { it.name.trim() }.filter(String::isNotEmpty).distinct() }
    .toList()
    .groupingBy { it }
    .eachCount()
    .entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .take(MAX_COMMON_TAGS)
    .map { TagCount(it.key, it.value) }

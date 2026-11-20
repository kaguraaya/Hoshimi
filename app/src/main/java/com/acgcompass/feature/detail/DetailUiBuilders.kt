package com.acgcompass.feature.detail

import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TasteMatchResult
import kotlin.math.roundToInt

// region 领域 → UI 映射（纯函数）

/**
 * 把 [Work]、[RatingAggregate]、待补状态、个人记录与口味画像折叠为 [DetailUiState]（纯函数）。
 *
 * @param work 作品（来自 `WorkRepository.observeWork`）。
 * @param ratings 评分聚合（来自 `WorkRepository.aggregateRatings`）；缺失时四平台「暂无数据」、
 *   共识 [ConsensusUiModel.Insufficient]。
 * @param inBacklog 该作品是否已在待补池（决定加入 / 移出按钮形态）。
 * @param collectionState 个人记录（我的状态 / 评分 / 短评 / 进度）；`null` / 缺失字段显示「暂无数据」。
 * @param tasteProfile 口味画像；`null` 时口味匹配度为 [TasteMatchUiModel.Unavailable]（不伪造）。
 */
fun buildDetailUiState(
    work: Work,
    ratings: RatingAggregate?,
    inBacklog: Boolean = false,
    collectionState: CollectionState? = null,
    tasteProfile: TasteProfile? = null,
    radar: SpoilerRadarResult? = null,
    inDustMuseum: Boolean = false,
    extras: DetailExtras = DetailExtras(),
    advancedMatch: TasteMatchResult? = null,
    advancedProfile: AdvancedTasteProfile? = null,
): DetailUiState {
    val translatedSummary = extras.vndbDetail?.translatedDescription?.takeIf { it.isNotBlank() }
    val displayWork = if (translatedSummary != null && extras.vndbDetail.bangumiSummary.isNullOrBlank()) {
        work.copy(summary = "AI 翻译，可能不准确：\n$translatedSummary")
    } else {
        work
    }
    val consensus = ratings?.consensus.toConsensusUiModel()
    val tasteMatch = displayWork.toTasteMatch(advancedMatch, advancedProfile)
    val completionCost = displayWork.toCompletionCostUiModel()
    // Bangumi backing controls cloud sync. Unmatched VNDB records remain locally editable.
    val bangumiBacked = work.primarySource == SourceId.BANGUMI ||
        ratings?.perSource?.get(SourceId.BANGUMI) != null
    val personal = displayWork.toPersonalUiModel(collectionState, inBacklog, inDustMuseum, bangumiBacked)
    val isVndb = work.primarySource == SourceId.VNDB
    return DetailUiState(
        header = displayWork.toHeader(
            sensitiveMedia = isVndb && (
                extras.vndbDetail == null || extras.vndbDetail.adult || extras.vndbDetail.cover?.sensitive == true
                ),
            previewCoverUrl = extras.vndbDetail?.cover?.thumbnailUrl,
            originalCoverUrl = extras.vndbDetail?.cover?.url,
        ),
        ratings = ratings.toPlatformRatings(),
        consensus = consensus,
        personal = personal,
        decision = displayWork.toDecisionUiModel(consensus, tasteMatch, completionCost, radar),
        tabs = displayWork.toTabs(ratings, personal, extras, radar),
        completionCost = completionCost,
        routeEntries = extras.routeEntries,
        // 优先用 loadExtras 拉取的 Bangumi 社区标签；首帧未就绪时回退作品自带标签
        // （Bangumi 主源作品 work.tags 即社区标签），都空则不展示「标签」区。
        tags = extras.tags.ifEmpty { displayWork.tags.map { it.name }.filter { it.isNotBlank() } },
        vndbDetail = extras.vndbDetail,
        vndbLoading = extras.vndbLoading,
        vndbError = extras.vndbError,
        isVndb = isVndb,
        vndbBangumiLinked = extras.vndbDetail?.bangumiSubjectId != null,
    )
}

internal fun Work.toHeader(
    sensitiveMedia: Boolean = false,
    previewCoverUrl: String? = null,
    originalCoverUrl: String? = null,
): DetailHeader {
    val rows = buildList {
        add(infoRow(label = "原名", value = titles.originalName()))
        add(infoRow(label = "别名", value = titles.aliases.takeIf { it.isNotEmpty() }?.joinToString("、")))
        add(DetailInfoRow(label = "类型", value = mediaType.label(), isMissing = false))
        add(infoRow(label = "年份", value = year?.toString()))
        // 开播 / 发行日期，精确到天（yyyy-MM-dd）；缺失显示「暂无数据」。
        add(infoRow(label = "开播日期", value = airDate))
        add(statusRow(status))
        add(infoRow(label = "集数 · 卷数 · 游玩时长", value = units.describe(mediaType)))
    }
    return DetailHeader(
        coverUrl = previewCoverUrl ?: coverUrl,
        originalCoverUrl = originalCoverUrl ?: coverUrl,
        title = titles.canonical,
        infoRows = rows,
        sensitiveMedia = sensitiveMedia,
    )
}

/** 原文名优先级：日文 → 罗马音 → 英文。 */
internal fun com.acgcompass.domain.model.Titles.originalName(): String? = ja ?: romaji ?: en

internal fun infoRow(label: String, value: String?): DetailInfoRow = if (value.isNullOrBlank()) {
    DetailInfoRow(label = label, value = NO_DATA, isMissing = true)
} else {
    DetailInfoRow(label = label, value = value, isMissing = false)
}

internal fun statusRow(status: ReleaseStatus): DetailInfoRow {
    val label = "状态"
    return when (status) {
        ReleaseStatus.UNKNOWN -> DetailInfoRow(label, NO_DATA, isMissing = true)
        ReleaseStatus.NOT_RELEASED -> DetailInfoRow(label, "未发布", isMissing = false)
        ReleaseStatus.RELEASING -> DetailInfoRow(label, "连载 / 放送中", isMissing = false)
        ReleaseStatus.FINISHED -> DetailInfoRow(label, "已完结", isMissing = false)
        ReleaseStatus.ON_HIATUS -> DetailInfoRow(label, "休刊 / 暂停", isMissing = false)
        ReleaseStatus.CANCELLED -> DetailInfoRow(label, "已取消", isMissing = false)
    }
}

internal fun MediaType.label(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
}

/** 体量 / 时长描述（按媒介各取所需）；全部缺失返回 `null`（→「暂无数据」）。 */
internal fun com.acgcompass.domain.model.Units.describe(mediaType: MediaType): String? {
    val parts = buildList {
        episodes?.let { add("$it 集") }
        episodeMinutes?.let { add("单集约 $it 分钟") }
        volumes?.let { add("$it 卷") }
        estPlayMinutes?.let { add("预计游玩约 ${(it / 60f).roundToInt()} 小时") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// endregion

// region 个人区 / 决策区 / 详情 Tab / 补完成本（纯函数）

/** 个人区：从本地个人记录折叠；缺失字段保持 `null` → UI 显示「暂无数据」。 */
internal fun Work.toPersonalUiModel(
    collectionState: CollectionState?,
    inBacklog: Boolean,
    inDustMuseum: Boolean = false,
    bangumiBacked: Boolean = true,
): PersonalUiModel = PersonalUiModel(
    statusText = collectionState?.status?.takeIf { it.isNotBlank() },
    ratingText = collectionState?.rating?.let { "$it / 10" },
    progressText = collectionState?.progress?.let { progressLabel(it) },
    reviewText = collectionState?.shortReview?.takeIf { it.isNotBlank() },
    tags = collectionState?.tags.orEmpty(),
    inBacklog = inBacklog,
    inDustMuseum = inDustMuseum,
    // 存在任意个人收藏记录时即视为已加入我的库。
    inLibrary = collectionState != null && (
        !collectionState.status.isNullOrBlank() ||
            collectionState.rating != null ||
            collectionState.progress != null ||
            !collectionState.shortReview.isNullOrBlank()
        ),
    totalEpisodes = units.episodes?.takeIf { it > 0 },
    progressEditable = mediaType == MediaType.ANIME,
    recordEditable = bangumiBacked || mediaType == MediaType.VN,
    isPrivate = collectionState?.isPrivate ?: false,
)

/** 进度文案按媒介类型选择量词（动画/漫画/小说=集·话，其余统一「进度」）。 */
internal fun Work.progressLabel(progress: Int): String = when (mediaType) {
    MediaType.ANIME -> "已看 $progress 集"
    MediaType.MANGA, MediaType.NOVEL -> "已读 $progress 话"
    MediaType.GAME, MediaType.VN, MediaType.OTHER -> "进度 $progress"
}

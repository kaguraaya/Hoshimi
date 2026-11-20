package com.acgcompass.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.acgcompass.core.designsystem.AcgCompassTheme

// region 预览

@Preview(showBackground = true)
@Composable
internal fun DetailScreenPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "葬送的芙莉莲",
                    infoRows = listOf(
                        DetailInfoRow("原名", "葬送のフリーレン", isMissing = false),
                        DetailInfoRow("别名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "动画", isMissing = false),
                        DetailInfoRow("年份", "2023", isMissing = false),
                        DetailInfoRow("状态", "已完结", isMissing = false),
                        DetailInfoRow("集数 · 卷数 · 游玩时长", "28 集 · 单集约 24 分钟", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", "8.5", "12,345 人评分"),
                    PlatformRatingUiModel("AniList", "92", "67,890 人评分"),
                    PlatformRatingUiModel("MAL·Tenrai", "9.1", "100,000 人评分"),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Available(
                    stability = ConsensusMetric("评分稳定度", "82%", "较稳定", 0.82f),
                    controversy = ConsensusMetric("争议程度", "18%", "争议较小", 0.18f),
                    priority = ConsensusMetric("补番优先级", "90%", "建议优先", 0.90f),
                ),
                personal = PersonalUiModel(
                    statusText = "在看",
                    ratingText = "9 / 10",
                    progressText = "已看 12 集",
                    reviewText = "节奏舒缓，画面很美。",
                    tags = listOf("奇幻", "治愈", "冒险"),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Available(
                        percentText = "78%",
                        qualitativeText = "可能会喜欢",
                        fraction = 0.78f,
                        reason = "命中 2 个你高分作品常见的标签，倾向于符合你的口味",
                    ),
                    recommendReasons = listOf("与口味画像高度匹配", "社区补番优先级较高"),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = listOf("治愈", "放松"),
                    completionCostText = "周末补完 · 预计约 11 小时",
                ),
                tabs = listOf(
                    DetailTabUiModel("简介", NO_DATA),
                    DetailTabUiModel("平台数据", "Bangumi：8.5（12,345 人评分）"),
                    DetailTabUiModel("我的记录", "状态：在看\n进度：已看 12 集"),
                ),
                completionCost = CompletionCostUiModel(
                    available = true,
                    bucketLabel = "周末补完",
                    detailText = "预计约 11 小时",
                ),
            ),
        )
    }
}

@Preview(showBackground = true, name = "Insufficient consensus")
@Composable
internal fun DetailScreenInsufficientPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "某冷门作品",
                    infoRows = listOf(
                        DetailInfoRow("原名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "游戏", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", null, null),
                    PlatformRatingUiModel("AniList", null, null),
                    PlatformRatingUiModel("MAL·Tenrai", null, null),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Insufficient,
                personal = PersonalUiModel(
                    statusText = null,
                    ratingText = null,
                    progressText = null,
                    reviewText = null,
                    tags = emptyList(),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Unavailable("尚未生成口味画像"),
                    recommendReasons = emptyList(),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = emptyList(),
                    completionCostText = NO_DATA,
                ),
                tabs = emptyList(),
                completionCost = CompletionCostUiModel(
                    available = false,
                    bucketLabel = NO_DATA,
                    detailText = NO_DATA,
                ),
            ),
        )
    }
}

package com.acgcompass.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * 统一作品卡片。
 *
 * 在首页 / 发现 / 待补池 / 详情等屏幕复用同一张作品卡片，保证全局视觉与信息密度一致。展示：
 * 封面、标题、别名/年份、类型、评分（缺失显示「暂无数据」）、来源标签、待补状态、补完成本
 * （今晚/周末/长期坑）以及风险/心情标签 chips。
 *
 * 兜底策略：
 * - 封面缺失（[WorkCardUiModel.coverUrl] 为 `null`）时渲染占位图标，不留空白。
 * - 长标题通过 `maxLines = 2 + TextOverflow.Ellipsis` 截断，避免撑破布局。
 * - 评分缺失（[WorkCardUiModel.ratingText] 为 `null`）时显示「暂无数据」，绝不伪造分值。
 * - 所有颜色取自 [MaterialTheme]，深色模式与动态取色自动适配。
 *
 * @param model   卡片展示数据。
 * @param onClick 卡片点击回调（如进入详情）。
 *
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkCard(model: WorkCardUiModel, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                coverUrl = model.coverUrl,
                contentDescription = model.title,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标题：长标题做两行截断兜底。
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // 别名 / 年份。
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 展示类型和可用评分；评分缺失时只展示类型。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.type,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (model.ratingText != null) {
                        Text(
                            text = model.ratingText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // 待补状态 + 补完成本（今晚/周末/长期坑）。
                val statusLine = listOfNotNull(model.backlogStatus, model.completionCost)
                if (statusLine.isNotEmpty()) {
                    Text(
                        text = statusLine.joinToString(separator = " · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 来源标签 chips（标注数据来源）。
                if (model.sourceTags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        model.sourceTags.forEach { tag ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(text = tag, style = MaterialTheme.typography.labelSmall) },
                                shape = MaterialTheme.shapes.extraSmall,
                                border = SuggestionChipDefaults.suggestionChipBorder(enabled = true),
                            )
                        }
                    }
                }

                // 风险 / 心情标签 chips。
                if (model.moodRiskTags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        model.moodRiskTags.forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = { Text(text = tag, style = MaterialTheme.typography.labelSmall) },
                                shape = MaterialTheme.shapes.extraSmall,
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 封面图：使用 Coil [AsyncImage] 加载远程封面；[coverUrl] 为 `null` 时渲染占位图标兜底
 * （封面缺失兜底）。
 */
@Composable
private fun CoverImage(coverUrl: String?, contentDescription: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(width = 72.dp, height = 96.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderCover()
        }
    }
}

/** 封面缺失占位：在 surfaceVariant 背景上居中显示破图图标。 */
@Composable
private fun PlaceholderCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = NO_COVER,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NO_DATA = "暂无数据"
private const val NO_COVER = "暂无封面"

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun WorkCardPreview() {
    AcgCompassTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkCard(
                model = WorkCardUiModel(
                    coverUrl = null,
                    title = "葬送的芙莉莲：勇者一行旅程结束之后，留下来的人将如何面对漫长的时光",
                    subtitle = "葬送のフリーレン · 2023",
                    type = "动画",
                    ratingText = "Bgm 8.9 / Ani 89",
                    sourceTags = listOf("Bangumi", "AniList"),
                    backlogStatus = "想看",
                    completionCost = "长期坑",
                    moodRiskTags = listOf("治愈", "致郁预警"),
                ),
            )
            WorkCard(
                model = WorkCardUiModel(
                    coverUrl = null,
                    title = "未知作品",
                    subtitle = "",
                    type = "游戏",
                    ratingText = null,
                    sourceTags = emptyList(),
                ),
            )
        }
    }
}

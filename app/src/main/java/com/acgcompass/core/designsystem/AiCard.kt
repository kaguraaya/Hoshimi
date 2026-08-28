package com.acgcompass.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AiCardUiModel.Confidence
import com.acgcompass.core.designsystem.AiCardUiModel.Generator

/**
 * AI 结果卡片。
 *
 * 对 AI / 规则生成的结果做透明标注，让用户清楚结果来源与可信度，避免「AI 黑箱」误导。展示：
 * - 「AI 生成 / 规则生成」徽标（[AiCardUiModel.generator]）。
 * - 生成时间（[AiCardUiModel.generatedAtText]）。
 * - 数据来源（[AiCardUiModel.sources]，渲染为 chips）。
 * - 置信度（高 / 中 / 低，[AiCardUiModel.confidence]）。
 * - 「重新生成」按钮（[onRegenerate]）。
 *
 * 该卡片作为内容容器，把具体的 AI 结果（雷达 / 口味画像 / 推荐理由等）通过 [content] 槽渲染，
 * 元信息统一在头尾展示，保证不同 AI 任务的卡片外观一致。
 *
 * @param model        AI 卡片元信息。
 * @param onRegenerate 「重新生成」回调。
 * @param content      AI 结果主体内容槽，可为空。
 *
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCard(
    model: AiCardUiModel,
    modifier: Modifier = Modifier,
    onRegenerate: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 头部：生成方式徽标 + 置信度徽标。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GeneratorBadge(generator = model.generator)
                ConfidenceBadge(confidence = model.confidence)
            }

            // 生成时间。
            Text(
                text = "生成时间：${model.generatedAtText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 数据来源 chips。
            if (model.sources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "数据来源",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        model.sources.forEach { source ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = source,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                        }
                    }
                }
            }

            // 结果主体。
            content()

            // 底部：重新生成。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRegenerate) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(text = "重新生成")
                }
            }
        }
    }
}

/** 「AI 生成 / 规则生成」徽标。 */
@Composable
private fun GeneratorBadge(generator: Generator, modifier: Modifier = Modifier) {
    val (label, icon) = when (generator) {
        Generator.AI -> "AI 生成" to Icons.Outlined.AutoAwesome
        Generator.RULE -> "规则生成" to Icons.AutoMirrored.Outlined.Rule
    }
    Badge(
        text = label,
        icon = icon,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    )
}

/** 置信度徽标（高 / 中 / 低）。 */
@Composable
private fun ConfidenceBadge(confidence: Confidence, modifier: Modifier = Modifier) {
    val (label, container, content) = when (confidence) {
        Confidence.HIGH -> Triple(
            "置信度：高",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Confidence.MEDIUM -> Triple(
            "置信度：中",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Confidence.LOW -> Triple(
            "置信度：低",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Badge(
        text = label,
        icon = null,
        containerColor = container,
        contentColor = content,
        modifier = modifier,
    )
}

/** 通用徽标：圆角填充背景 + 可选图标 + 文案。 */
@Composable
private fun Badge(
    text: String,
    icon: ImageVector?,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AiCardPreview() {
    AcgCompassTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiCard(
                model = AiCardUiModel(
                    generator = Generator.AI,
                    generatedAtText = "2024-09-30 21:15",
                    sources = listOf("短评", "Reviews", "标签"),
                    confidence = Confidence.HIGH,
                ),
            ) {
                Text(
                    text = "总体印象：节奏舒缓的治愈系奇幻，重情感刻画。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AiCard(
                model = AiCardUiModel(
                    generator = Generator.RULE,
                    generatedAtText = "2024-09-30 21:16",
                    sources = listOf("标签"),
                    confidence = Confidence.LOW,
                ),
            )
        }
    }
}

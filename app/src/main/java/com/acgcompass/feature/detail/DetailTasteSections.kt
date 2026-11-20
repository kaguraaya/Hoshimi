package com.acgcompass.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillTone

// endregion

// region 决策区

@Composable
internal fun DecisionSection(
    decision: DecisionUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    SectionCard(title = "决策助手", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TasteMatchBlock(
                tasteMatch = decision.tasteMatch,
                aiMatch = aiMatch,
                onAnalyzeMatchWithAi = onAnalyzeMatchWithAi,
            )
            // 补番优先级（可解释：综合口味/社区/补完成本/情绪风险）。
            if (decision.priorityLevel != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "补番优先级", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "优先级：${decision.priorityLevel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    decision.priorityReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ReasonsBlock(title = "推荐理由", reasons = decision.recommendReasons)
            ReasonsBlock(title = "需注意 / 不推荐", reasons = decision.notRecommendReasons)
            // 无剧透评价雷达：展示无剧透摘要 + 生成方式 / 剧透等级 / 来源标注 / 置信度；
            // 未生成时摘要为「暂无数据」，绝不伪造。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "无剧透评价雷达", style = MaterialTheme.typography.titleSmall)
                // 生成方式 + 剧透等级标注。
                val radarMeta = listOfNotNull(
                    decision.spoilerRadarGenerator,
                    decision.spoilerRadarLevel,
                    decision.spoilerRadarConfidenceText,
                ).joinToString(" · ")
                if (radarMeta.isNotEmpty()) {
                    Text(
                        text = radarMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = decision.spoilerRadarSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 摘要来源标注（短评 / Reviews / 标签 / AI），帮助用户判断摘要可信度。
                if (decision.spoilerRadarSources.isNotEmpty()) {
                    Text(
                        text = "来源：${decision.spoilerRadarSources.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 补完成本一句话摘要。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "补完成本", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = decision.completionCostText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TasteMatchBlock(
    tasteMatch: TasteMatchUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 标题行内嵌「AI 分析」按钮——口味匹配度默认本地算法，可点击 AI 给出数值+简评。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "口味匹配度", style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(
                onClick = onAnalyzeMatchWithAi,
                enabled = aiMatch !is AiMatchUi.Loading,
            ) {
                Text(text = if (aiMatch is AiMatchUi.Loading) "分析中…" else "AI 分析", maxLines = 1)
            }
        }
        // AI 分析完成后主数值换成 AI 的结果，内置算法的数值在下方保留一行；否则展示内置算法的结果。
        if (aiMatch is AiMatchUi.Result) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "AI 评估", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = aiMatch.matchPercentText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LinearProgressIndicator(progress = { aiMatch.fraction }, modifier = Modifier.fillMaxWidth())
            AiPredictionExtras(aiMatch)
            aiMatch.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
            aiMatch.riskReasons.forEach {
                Text(
                    text = "- $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${aiMatch.confidenceText} · 由 AI 分析得出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LocalEstimateLine(tasteMatch)
        } else {
            when (tasteMatch) {
                is TasteMatchUiModel.Available -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tasteMatch.qualitativeText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = tasteMatch.percentText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { tasteMatch.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 分维度贡献理由（+ 命中 / − 拉低），正负分色，逐条展示可解释性。
                    tasteMatch.reasons.forEach { r ->
                        Text(
                            text = (if (r.positive) "+ " else "− ") + r.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.positive) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (tasteMatch.reason.isNotBlank()) {
                        Text(
                            text = tasteMatch.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tasteMatch.prediction?.let { RatingPredictionBlock(it) }
                    // 置信度文案（独立展示，不塞进理由长句）。
                    tasteMatch.confidenceText?.let { conf ->
                        Text(
                            text = conf,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is TasteMatchUiModel.Unavailable -> {
                    // 未生成或样本不足时不显示推测分数，并给出具体原因。
                    Text(
                        text = tasteMatch.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Loading / NotConfigured / Error 提示（Result 已在上方替换主值）。
            AiMatchContent(aiMatch = aiMatch)
        }
    }
}

/**
 * AI 匹配度分析结果内容；仅渲染状态，不含标题和按钮（按钮在
 * [TasteMatchBlock] 标题行）。失败或未配置引导回退本地模型，页面不无响应。
 */
@Composable
internal fun AiMatchContent(aiMatch: AiMatchUi, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (aiMatch) {
            is AiMatchUi.Idle -> Unit // 未触发：不额外显示。
            is AiMatchUi.Loading -> AiMatchLoadingContent()
            is AiMatchUi.Result -> AiMatchResultContent(aiMatch)
            is AiMatchUi.NotConfigured -> Text(
                text = aiMatch.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is AiMatchUi.Error -> Text(
                text = aiMatch.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun AiMatchLoadingContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(
            text = "AI 分析中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** AI 结果下方保留一行内置算法的数值，方便对照。 */
@Composable
internal fun LocalEstimateLine(tasteMatch: TasteMatchUiModel) {
    val local = tasteMatch as? TasteMatchUiModel.Available ?: return
    val predicted = local.prediction?.let { "，预计你会打 ${it.predictedText} 分" }.orEmpty()
    Text(
        text = "内置算法：口味匹配度 ${local.percentText}$predicted",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AiMatchResultContent(result: AiMatchUi.Result) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AI · ${result.confidenceText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = result.matchPercentText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    result.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
    result.riskReasons.forEach {
        Text(
            text = "- $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReasonsBlock(title: String, reasons: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (reasons.isEmpty()) {
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// endregion

/**
 * 评分预测块：预计评分 / 社区评分 / 你的均分三个数字，一句「对你来说算高还是低」，
 * 一句「和社区差在哪」，以及个人口味部分的归因。
 * 与上方「口味匹配度」回答的问题不同——匹配度是「像不像你的口味」，这里是「你会打几分」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RatingPredictionBlock(prediction: RatingPredictionUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Surfaces.raisedColor,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PredictionStat(label = "预计你会打", value = prediction.predictedText, emphasized = true)
                PredictionStat(label = "社区评分", value = prediction.communityText ?: "—")
                PredictionStat(label = "你的均分", value = prediction.userMeanText)
            }
            Text(
                text = prediction.selfSummary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = prediction.communitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prediction.factors.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    prediction.factors.forEach { factor ->
                        com.acgcompass.core.designsystem.TagPill(
                            text = factor.text,
                            tone = if (factor.positive) {
                                com.acgcompass.core.designsystem.TagPillTone.Primary
                            } else {
                                com.acgcompass.core.designsystem.TagPillTone.Neutral
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PredictionStat(label: String, value: String, emphasized: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            // 数值一律正文色：颜色只表示状态（选中 / 开启 / 出错），不用来强调数字。
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** AI 给出的预计评分、分歧原因与评分角度（旧缓存 / 不支持的模型不返回时不展示）。 */
@Composable
internal fun AiPredictionExtras(aiMatch: AiMatchUi.Result) {
    aiMatch.predictedRatingText?.let { predicted ->
        Text(
            text = "AI 预计你会打 $predicted 分" + aiMatch.divergenceReason?.let { "：$it" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
    if (aiMatch.ratingAngles.isNotEmpty()) {
        Text(
            text = "你打分最看重：" + aiMatch.ratingAngles.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

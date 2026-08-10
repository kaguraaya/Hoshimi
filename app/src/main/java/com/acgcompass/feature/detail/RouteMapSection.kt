package com.acgcompass.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.ai.RouteRecommendation
import com.acgcompass.domain.model.RouteNode
import com.acgcompass.domain.model.RouteRelationType

/**
 * 单个路线节点的 UI 模型（纯展示，与 [RouteNode] 解耦，便于预览与测试）。
 *
 * 领域 [RouteNode] 只携带 [RouteNode.workId]，标题需由调用方（如详情 ViewModel）解析后传入，
 * 故此处单列 [title]。映射见 [toRouteNodeUiModel]。
 *
 * @property workId             节点作品 id（点击打开用）。
 * @property title              展示标题（缺失时由调用方填「暂无数据」）。
 * @property relationLabel      关联类型本地化标签（续作 / 前传 / …）。
 * @property recommendationLabel 观看建议本地化标签（必看 / 可选 / 可跳过 / 总集篇）。
 * @property isMust             是否为「必看」节点（用于「仅加入必看」筛选展示）。
 * @property orderLabel         推荐观看顺序序号标签（如「①」）；路线待确认时为 `null`，不展示顺序。
 */
data class RouteNodeUiModel(
    val workId: String,
    val title: String,
    val relationLabel: String,
    val recommendationLabel: String,
    val isMust: Boolean,
    val orderLabel: String? = null,
)

/** 关联类型 → 本地化标签。 */
fun RouteRelationType.toLabel(): String = when (this) {
    RouteRelationType.SEQUEL -> "续作"
    RouteRelationType.PREQUEL -> "前传"
    RouteRelationType.SIDE_STORY -> "外传"
    RouteRelationType.OVA -> "OVA"
    RouteRelationType.MOVIE -> "剧场版"
    RouteRelationType.RECAP -> "总集篇"
    RouteRelationType.OTHER -> "关联"
}

/** 观看建议 → 本地化标签。 */
fun RouteRecommendation.toLabel(): String = when (this) {
    RouteRecommendation.MUST -> "必看"
    RouteRecommendation.OPTIONAL -> "可选"
    RouteRecommendation.SKIP -> "可跳过"
    RouteRecommendation.RECAP -> "总集篇"
}

/**
 * 领域 [RouteNode] → [RouteNodeUiModel]（纯函数）。标题由调用方解析后传入，
 * 缺失时使用 [NO_DATA] 兜底。
 */
fun RouteNode.toRouteNodeUiModel(title: String?): RouteNodeUiModel = RouteNodeUiModel(
    workId = workId,
    title = title?.takeIf { it.isNotBlank() } ?: NO_DATA,
    relationLabel = relationType.toLabel(),
    recommendationLabel = recommendation.toLabel(),
    isMust = recommendation == RouteRecommendation.MUST,
)

/** 顺序序号 → 带圈数字标签（①②③…）；超出范围回退为「N.」。 */
fun orderLabelFor(position: Int): String {
    val circled = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
    return circled.getOrNull(position) ?: "${position + 1}."
}

/**
 * 补番路线图 · 关联作品区块（详情页「关联作品 / 观看路线」Tab）。
 *
 * 纯展示组件，可被 [DetailScreen] 或独立的路线页复用：
 * - 列出续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇等关联作品；点击节点回调 [onOpenWork]。
 * - 提供「一键加入系列」与「仅加入必看」两个动作，分别回调 `onAddSeries(false/true)`。
 *
 * **注意**：本组件只负责展示与触发加入动作；推荐观看顺序与确认状态由上层提供。
 *
 * @param nodes        路线节点 UI 列表（顺序由调用方决定，本组件不重排）。
 * @param onOpenWork   点击某节点打开作品详情的回调（参数为 workId）。
 * @param onAddSeries  加入待补池的回调，参数 `mustOnly`：`true` 仅加入必看节点，`false` 加入整个系列。
 *
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteMapSection(
    nodes: List<RouteNodeUiModel>,
    modifier: Modifier = Modifier,
    routeConfirmed: Boolean = true,
    progressText: String? = null,
    onOpenWork: (String) -> Unit = {},
    onAddSeries: (Boolean) -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "关联作品 · 补番路线",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (nodes.isEmpty()) {
                // 空态：无关联资料，不伪造。
                Text(
                    text = "$NO_DATA · 暂无关联作品",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // 系列进度节点：展示已看 / 总数等进度概览。
            if (!progressText.isNullOrBlank()) {
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 路线待确认：资料不足时明示，绝不编造观看顺序。
            if (!routeConfirmed) {
                Text(
                    text = "路线待确认：资料不足，暂不推荐确定观看顺序（不编造）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nodes.forEach { node -> RouteNodeRow(node = node, onOpenWork = onOpenWork) }
            }

            // 一键加入系列 / 仅加入必看。
            val hasMust = nodes.any { it.isMust }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onAddSeries(false) }) {
                    Text(text = "一键加入系列")
                }
                if (hasMust) {
                    OutlinedButton(onClick = { onAddSeries(true) }) {
                        Text(text = "仅加入必看")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteNodeRow(node: RouteNodeUiModel, onOpenWork: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenWork(node.workId) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = node.orderLabel?.let { "$it ${node.title}" } ?: node.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SuggestionChip(
                    onClick = { onOpenWork(node.workId) },
                    label = {
                        Text(
                            text = node.relationLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    border = SuggestionChipDefaults.suggestionChipBorder(enabled = true),
                )
                AssistChip(
                    onClick = { onOpenWork(node.workId) },
                    label = {
                        Text(
                            text = node.recommendationLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = if (node.isMust) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// region 预览

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RouteMapSectionPreview() {
    AcgCompassTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RouteMapSection(
                nodes = listOf(
                    RouteNodeUiModel("w1", "本篇 第一季", "续作", "必看", isMust = true),
                    RouteNodeUiModel("w2", "剧场版：序章", "剧场版", "可选", isMust = false),
                    RouteNodeUiModel("w3", "总集篇", "总集篇", "可跳过", isMust = false),
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Empty route")
@Composable
private fun RouteMapSectionEmptyPreview() {
    AcgCompassTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RouteMapSection(nodes = emptyList())
        }
    }
}

// endregion

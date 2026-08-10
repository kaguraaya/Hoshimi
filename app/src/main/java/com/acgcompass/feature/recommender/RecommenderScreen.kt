package com.acgcompass.feature.recommender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/** 推荐器路由参数键——首页「今日状态」预填的「想看标签」（逗号分隔）。 */
const val RECOMMENDER_ARG_TAGS: String = "presetTags"

/** 推荐器路由模板（含可选预填标签参数；无参时进入默认推荐器）。 */
const val RECOMMENDER_ROUTE_PATTERN: String = "recommender?$RECOMMENDER_ARG_TAGS={$RECOMMENDER_ARG_TAGS}"

/**
 * 构造跳转「今晚看什么」推荐器的路由。
 *
 * [presetTags] 为首页今日状态映射的「想看标签」，会预填进推荐器的标签筛选；为空时进入无预填的默认推荐器。
 * 标签可能含中文，故对拼接串做 URL 编码（读取侧由 Navigation 自动解码）。
 */
fun recommenderRoute(presetTags: List<String> = emptyList()): String = if (presetTags.isEmpty()) {
    "recommender"
} else {
    "recommender?$RECOMMENDER_ARG_TAGS=" + android.net.Uri.encode(presetTags.joinToString(","))
}

/**
 * 推荐器路由入口。连接 [RecommenderViewModel] 并把
 * 状态与回调下发给无状态的 [RecommenderScreen]。
 *
 * @param onOpenWork 点击某推荐卡片时打开作品详情（导航由上层注入）。
 */
@Composable
fun RecommenderRoute(
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecommenderViewModel = hiltViewModel(),
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()

    RecommenderScreen(
        input = input,
        result = result,
        availableTags = availableTags,
        onSelectTime = viewModel::onSelectTime,
        onToggleTag = viewModel::onToggleTag,
        onToggleAcceptance = viewModel::onToggleAcceptance,
        onToggleIndecision = viewModel::onToggleIndecisionMode,
        onToggleFinalsProtection = viewModel::onToggleFinalsProtection,
        onToggleLateNight = viewModel::onToggleLateNight,
        onSelectCandidatePool = viewModel::onSelectCandidatePool,
        onSubmit = viewModel::onSubmit,
        onOpenWork = onOpenWork,
        modifier = modifier,
    )
}

/**
 * 无状态推荐器界面（Material 3）。上半部为三组选择（时间 / 心情 / 接受程度）与提交按钮；
 * 下半部为三类推荐（稳妥 / 探索 / 随机）结果区，由 [StateScaffold] 统一渲染七态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommenderScreen(
    input: RecommenderInput,
    result: UiState<List<RecommendationUiModel>>,
    availableTags: List<String>,
    onSelectTime: (TimeBudget) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleAcceptance: (AcceptanceOption) -> Unit,
    onToggleIndecision: () -> Unit,
    onToggleFinalsProtection: () -> Unit,
    onToggleLateNight: () -> Unit,
    onSelectCandidatePool: (CandidatePool) -> Unit = {},
    onSubmit: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("今晚看什么") },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // 标题栏下方额外 8dp 呼吸（共约 24dp），底部额外留白避免末项贴边。
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 候选池选择——待补池 / 全部作品。
            Text("候选池", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CandidatePool.entries.forEach { pool ->
                    androidx.compose.material3.FilterChip(
                        selected = input.candidatePool == pool,
                        onClick = { onSelectCandidatePool(pool) },
                        label = { Text(pool.label()) },
                    )
                }
            }

            InputSection(
                input = input,
                availableTags = availableTags,
                onSelectTime = onSelectTime,
                onToggleTag = onToggleTag,
                onToggleAcceptance = onToggleAcceptance,
            )

            Button(
                onClick = onSubmit,
                enabled = input.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (input.canSubmit) "生成今晚推荐" else "先选择可用时间")
            }

            // 可选的推荐约束与提醒。
            ModeSwitches(
                input = input,
                onToggleIndecision = onToggleIndecision,
                onToggleFinalsProtection = onToggleFinalsProtection,
                onToggleLateNight = onToggleLateNight,
            )

            // 深夜提醒仅显示状态提示，不改变筛选结果。
            if (input.lateNightMode) {
                Text(
                    text = "已启用深夜提醒，请合理安排休息时间。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ResultSection(
                result = result,
                onSubmit = onSubmit,
                onOpenWork = onOpenWork,
            )
        }
    }
}

/** 推荐约束与提醒开关。 */
@Composable
private fun ModeSwitches(
    input: RecommenderInput,
    onToggleIndecision: () -> Unit,
    onToggleFinalsProtection: () -> Unit,
    onToggleLateNight: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("特别模式", style = MaterialTheme.typography.titleMedium)
        ModeSwitchRow(
            label = "单一推荐模式",
            checked = input.indecisionMode,
            onToggle = onToggleIndecision,
        )
        ModeSwitchRow(
            label = "低负担模式（过滤长篇 / 致郁 / 高投入 / 未完结）",
            checked = input.finalsProtectionMode,
            onToggle = onToggleFinalsProtection,
        )
        ModeSwitchRow(
            label = "深夜提醒",
            checked = input.lateNightMode,
            onToggle = onToggleLateNight,
        )
    }
}

/** 单行模式开关：标签 + Switch。 */
@Composable
private fun ModeSwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 三组选择区。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputSection(
    input: RecommenderInput,
    availableTags: List<String>,
    onSelectTime: (TimeBudget) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleAcceptance: (AcceptanceOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 时间（单选）。
        Text("我有多少时间", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeBudget.entries.forEach { option ->
                FilterChip(
                    selected = input.time == option,
                    onClick = { onSelectTime(option) },
                    label = { Text(option.label()) },
                )
            }
        }

        // 标签（多选）——来自候选池作品的真实 Bangumi 社区标签，动态生成。
        // 并入预填标签（来自首页今日状态），即使不在候选池高频标签内也展示并高亮，避免「已选却看不到」。
        Text("想看的标签", style = MaterialTheme.typography.titleMedium)
        val displayTags = (availableTags + input.selectedTags.toList()).distinct()
        if (displayTags.isEmpty()) {
            Text(
                text = "暂无可用标签，先补充待补池或切换上方「全部作品」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                displayTags.forEach { tag ->
                    FilterChip(
                        selected = tag in input.selectedTags,
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag) },
                    )
                }
            }
        }

        // 接受程度（多选）。
        Text("接受程度", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcceptanceOption.entries.forEach { option ->
                FilterChip(
                    selected = option in input.acceptances,
                    onClick = { onToggleAcceptance(option) },
                    label = { Text(option.label()) },
                )
            }
        }
    }
}

/** 三推荐结果区：由 [StateScaffold] 统一渲染加载 / 空 / 错误 / 成功。 */
@Composable
private fun ResultSection(
    result: UiState<List<RecommendationUiModel>>,
    onSubmit: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    StateScaffold(
        state = result,
        modifier = Modifier.fillMaxWidth(),
        onRetry = onSubmit,
        // 空态 CTA（选择条件 / 调整条件）仅作引导，点击重新提交一次。
        onCta = { onSubmit() },
    ) { recommendations ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recommendations.forEach { rec ->
                RecommendationCard(rec = rec, onOpenWork = onOpenWork)
            }
        }
    }
}

/** 单条推荐：种类标题 + 作品卡片 + 理由。 */
@Composable
private fun RecommendationCard(rec: RecommendationUiModel, onOpenWork: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rec.kind.label(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            WorkCard(
                model = rec.card,
                onClick = { onOpenWork(rec.workId) },
            )
            Text(
                text = rec.reason,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// region Preview

@Preview(showBackground = true)
@Composable
private fun RecommenderScreenInitialPreview() {
    AcgCompassTheme {
        RecommenderScreen(
            input = RecommenderInput(time = TimeBudget.TWO_THREE_HOURS, selectedTags = setOf("治愈")),
            result = UiState.Empty(com.acgcompass.core.ui.Cta("选择条件后生成推荐", "submit")),
            availableTags = listOf("治愈", "日常", "搞笑", "热血", "悬疑", "科幻"),
            onSelectTime = {},
            onToggleTag = {},
            onToggleAcceptance = {},
            onToggleIndecision = {},
            onToggleFinalsProtection = {},
            onToggleLateNight = {},
            onSubmit = {},
            onOpenWork = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecommenderScreenResultPreview() {
    val sample = listOf(
        RecommendationUiModel(
            kind = RecommendationKind.SAFE,
            workId = "w1",
            card = WorkCardUiModel(
                coverUrl = null,
                title = "孤独摇滚！",
                subtitle = "ぼっち・ざ・ろっく！ · 2022",
                type = "动画",
                ratingText = null,
                sourceTags = listOf("Bangumi"),
                backlogStatus = "吃灰 12 天",
                completionCost = "今晚可看完",
                moodRiskTags = listOf("轻松", "治愈"),
            ),
            reason = "优先满足已选条件。在 5 个候选作品中按优先级与等待时间选出。",
        ),
    )
    AcgCompassTheme {
        RecommenderScreen(
            input = RecommenderInput(time = TimeBudget.TWO_THREE_HOURS),
            result = UiState.Success(sample),
            availableTags = listOf("治愈", "日常", "搞笑"),
            onSelectTime = {},
            onToggleTag = {},
            onToggleAcceptance = {},
            onToggleIndecision = {},
            onToggleFinalsProtection = {},
            onToggleLateNight = {},
            onSubmit = {},
            onOpenWork = {},
        )
    }
}

// endregion

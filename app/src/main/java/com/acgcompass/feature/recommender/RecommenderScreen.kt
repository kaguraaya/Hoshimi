@file:Suppress("TooManyFunctions") // 一个页面拆成多个小型私有 Composable。

package com.acgcompass.feature.recommender

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.FlatDefaults
import com.acgcompass.core.designsystem.PillTabs
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.core.designsystem.TonalFilterChip
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
    onBack: () -> Unit = {},
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
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * 推荐器页面：条件选择（候选池 / 时间 / 标签 / 接受程度）→ 特别模式 → 生成 → 三档结果。
 *
 * 版式是扁平的色块卡片：每组条件一张无边框卡片（小图标 + 标题），互斥选项用药丸式标签页，
 * 多选用无描边的色块 chip，选中即实心填充；生成后自动滚动到结果区，不需要用户自己往下找。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Suppress("LongMethod", "LongParameterList") // 页面骨架：参数为各交互回调，主体是按顺序排布的区块。
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
    onBack: (() -> Unit)? = null,
) {
    val resultAnchor = remember { BringIntoViewRequester() }
    // 结果（或「无候选」提示）出来后把结果区滚进视野；初始空态不滚动。
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(result) {
        if (submitted && result !is UiState.Loading) resultAnchor.bringIntoView()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("今晚看什么", fontWeight = FontWeight.SemiBold) },
                // 这是从首页进入的二级页面，需要显式的返回入口（此前只能靠系统返回手势）。
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
        // 主操作固定在底部：条件区再长，「生成」也始终在手边，不用滚到页面最下面找。
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SubmitButton(
                        enabled = input.canSubmit,
                        submitted = submitted,
                        onClick = {
                            submitted = true
                            onSubmit()
                        },
                    )
                }
            }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CandidatePoolSelector(selected = input.candidatePool, onSelect = onSelectCandidatePool)

            InputSection(
                input = input,
                availableTags = availableTags,
                onSelectTime = onSelectTime,
                onToggleTag = onToggleTag,
                onToggleAcceptance = onToggleAcceptance,
            )

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
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Box(modifier = Modifier.bringIntoViewRequester(resultAnchor)) {
                ResultSection(
                    result = result,
                    onSubmit = onSubmit,
                    onOpenWork = onOpenWork,
                )
            }
        }
    }
}

/** 候选池——待补池 / 全部作品（互斥二选一，药丸式标签页）。 */
@Composable
private fun CandidatePoolSelector(selected: CandidatePool, onSelect: (CandidatePool) -> Unit) {
    FlatCard(title = "从哪里挑", icon = Icons.Outlined.Inventory2) {
        PillTabs(
            options = CandidatePool.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.label() },
        )
    }
}

/** 主操作按钮：未选时间时禁用并提示；生成过一次后文案变为「换一批」。 */
@Composable
private fun SubmitButton(enabled: Boolean, submitted: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        if (enabled) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = when {
                !enabled -> "先选择可用时间"
                submitted -> "换一批"
                else -> "生成今晚推荐"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 推荐约束与提醒开关：一张卡片里的三行，行与行之间只靠留白分隔。 */
@Composable
private fun ModeSwitches(
    input: RecommenderInput,
    onToggleIndecision: () -> Unit,
    onToggleFinalsProtection: () -> Unit,
    onToggleLateNight: () -> Unit,
) {
    FlatCard(
        title = "特别模式",
        icon = Icons.Outlined.Tune,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Column {
            ModeSwitchRow(
                label = "单一推荐模式",
                supporting = "只给一部最稳妥的，不用纠结",
                checked = input.indecisionMode,
                onToggle = onToggleIndecision,
            )
            ModeSwitchRow(
                label = "低负担模式",
                supporting = "过滤长篇 / 致郁 / 高投入 / 未完结",
                checked = input.finalsProtectionMode,
                onToggle = onToggleFinalsProtection,
            )
            ModeSwitchRow(
                label = "深夜提醒",
                supporting = "提醒合理安排休息，不影响结果",
                checked = input.lateNightMode,
                onToggle = onToggleLateNight,
            )
        }
    }
}

/** 单行模式开关：标题 + 说明 + Switch；整行可点（toggleable），读屏按「开关」朗读。 */
@Composable
private fun ModeSwitchRow(label: String, supporting: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 点击由整行处理；Switch 只负责显示状态。
        Switch(
            checked = checked,
            onCheckedChange = null,
            // 未选中态去掉描边：实心轨道 + 实心滑块，与页面其余部分的无线条风格一致。
            colors = SwitchDefaults.colors(
                uncheckedBorderColor = Color.Transparent,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** 三组选择区：每组一张无边框的色块卡片。 */
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
        FlatCard(title = "我有多少时间", icon = Icons.Outlined.Schedule) {
            PillTabs(
                options = TimeBudget.entries,
                selected = input.time,
                onSelect = onSelectTime,
                label = { it.label() },
            )
        }

        // 标签选项来自候选池作品的真实 Bangumi 社区标签（频次降序）；并入已选标签
        // 以保证预填（如首页「今日状态」）或已选项即使不在当前前 N 也能显示、可取消。
        val selectedCount = input.selectedTags.size
        FlatCard(
            title = "想看的标签",
            icon = Icons.Outlined.Sell,
            supporting = if (selectedCount > 1) "已选 $selectedCount 个，命中越多越优先" else "可多选，不选则按长期口味推荐",
        ) {
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
                        TonalFilterChip(tag, tag in input.selectedTags, onClick = { onToggleTag(tag) })
                    }
                }
            }
        }

        FlatCard(title = "接受程度", icon = Icons.Outlined.Shield) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcceptanceOption.entries.forEach { option ->
                    TonalFilterChip(
                        option.label(),
                        option in input.acceptances,
                        onClick = { onToggleAcceptance(option) },
                    )
                }
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
        onCta = { onSubmit() },
    ) { recommendations ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recommendations.forEach { rec ->
                RecommendationCard(rec = rec, onOpenWork = onOpenWork)
            }
        }
    }
}

/** 单条推荐：一张色块卡片——档位（图标 + 名称）与匹配度 → 作品 → 理由。三档用不同色调区分。 */
@Composable
private fun RecommendationCard(rec: RecommendationUiModel, onOpenWork: (String) -> Unit) {
    val (icon, accent) = when (rec.kind) {
        RecommendationKind.SAFE -> Icons.Filled.Verified to MaterialTheme.colorScheme.primary
        RecommendationKind.GAMBLE -> Icons.Filled.Explore to MaterialTheme.colorScheme.tertiary
        RecommendationKind.WILDCARD -> Icons.Filled.Casino to MaterialTheme.colorScheme.secondary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = FlatDefaults.cardColor,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    text = rec.kind.label(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                rec.matchPercent?.let { TagPill(text = "匹配 $it%", tone = TagPillTone.Primary) }
            }
            WorkCard(
                model = rec.card,
                containerColor = FlatDefaults.tileColor,
                onClick = { onOpenWork(rec.workId) },
            )
            Text(
                text = rec.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

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

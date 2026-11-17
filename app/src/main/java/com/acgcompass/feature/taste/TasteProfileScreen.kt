package com.acgcompass.feature.taste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.PersonalDivergenceModel
import com.acgcompass.domain.taste.TagClassificationPhase
import com.acgcompass.domain.taste.TagClassificationState
import com.acgcompass.domain.taste.TagClassifyProgress
import com.acgcompass.domain.taste.TagDimensionSummary
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteLocalAnalysisPhase
import com.acgcompass.domain.taste.TasteLocalAnalysisState
import com.acgcompass.domain.taste.TasteProcessingSummary
import com.acgcompass.domain.taste.TasteRefreshProgress
import com.acgcompass.domain.taste.TopicCombo
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import kotlin.math.roundToInt

/** 口味画像路由入口。 */
@Composable
fun TasteProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenManualReview: () -> Unit = {},
    viewModel: TasteProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val refreshProgress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val localAnalysisState by viewModel.localAnalysisState.collectAsStateWithLifecycle()
    val classifying by viewModel.classifying.collectAsStateWithLifecycle()
    val pendingTagCount by viewModel.pendingTagCount.collectAsStateWithLifecycle()
    val tagClassifyProgress by viewModel.tagClassifyProgress.collectAsStateWithLifecycle()
    val advancedProfile by viewModel.advancedProfile.collectAsStateWithLifecycle()
    val dimensionSummary by viewModel.dimensionSummary.collectAsStateWithLifecycle()
    val processingSummary by viewModel.processingSummary.collectAsStateWithLifecycle()
    val classificationState by viewModel.classificationState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    // 进入画像页时按内部节流策略触发自动分析。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.onScreenOpened() }
    TasteProfileScreen(
        state = state,
        onBack = onBack,
        importing = importing,
        refreshProgress = refreshProgress,
        localAnalysisState = localAnalysisState,
        // CTA 已配置 Bangumi 则真正导入并刷新画像；未配置则跳转设置登录。
        onImportData = { viewModel.onImportFromBangumi(onNotConfigured = onOpenSettings) },
        // 手动重新收集优先作品标签并重算本地画像。
        onRefreshAnalysis = { viewModel.onRefreshAnalysis() },
        classifying = classifying,
        pendingTagCount = pendingTagCount,
        tagClassifyProgress = tagClassifyProgress,
        // 12 维引擎画像，供画像页展示更丰富的维度 / 组合 / 概况。
        advancedProfile = advancedProfile,
        // AI 升维效果摘要（把多少兜底为「题材」的标签细化到了哪些精确维度）。
        dimensionSummary = dimensionSummary,
        processingSummary = processingSummary,
        manualReviewCount = classificationState.manualReview,
        classificationState = classificationState,
        onOpenManualReview = onOpenManualReview,
        // 手动 AI 标签升维分类（细化本地兜底为题材的未知标签）。
        onClassifyTags = {
            if (classifying) viewModel.onStopClassifyingTags() else viewModel.onClassifyTags()
        },
        modifier = modifier,
    )
}

/** 无状态口味画像界面：评分习惯 / 称号 / 高低分标签 / 常弃坑类型；低样本用保守措辞。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun TasteProfileScreen(
    state: UiState<TasteProfile>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    importing: Boolean = false,
    refreshProgress: TasteRefreshProgress? = null,
    localAnalysisState: TasteLocalAnalysisState = TasteLocalAnalysisState(),
    classifying: Boolean = false,
    pendingTagCount: Int? = null,
    tagClassifyProgress: TagClassifyProgress? = null,
    advancedProfile: AdvancedTasteProfile? = null,
    dimensionSummary: TagDimensionSummary? = null,
    processingSummary: TasteProcessingSummary? = null,
    manualReviewCount: Int = 0,
    classificationState: TagClassificationState = TagClassificationState(),
    onImportData: () -> Unit = {},
    onRefreshAnalysis: () -> Unit = {},
    onClassifyTags: () -> Unit = {},
    onOpenManualReview: () -> Unit = {},
) {
    AcgScreenScaffold(
        title = "口味画像",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { innerPadding ->
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            // 空态 CTA 不再是死按钮——前往设置（Bangumi/同步）导入口味数据。
            onCta = { onImportData() },
        ) { profile ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 统一使用 AcgScreenScaffold 和 ScreenContentPadding，避免首张卡片被标题栏遮挡。
                    .padding(
                        start = ScreenContentPadding.Horizontal,
                        end = ScreenContentPadding.Horizontal,
                        top = ScreenContentPadding.Top,
                        bottom = ScreenContentPadding.Bottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 把「重新分析」「AI 升维分类」两个操作理清进同一张「分析与优化」卡片；按钮只保留短标签、
                // 长说明使用辅助文本，进度条紧跟对应操作。
                SectionCard("分析与优化") {
                    val localAnalysisRunning = localAnalysisState.isRunning
                    // 个别作品可能一直取不到完整标签（需要登录才能看的条目等），覆盖八成以上就允许 AI 细分。
                    val featureSnapshotReady = processingSummary?.let {
                        it.totalPriorityWorks > 0 && it.coverageFraction >= AI_CLASSIFY_MIN_COVERAGE
                    } == true
                    Text(
                        "上次分析：${formatLastUpdated(profile.generatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onRefreshAnalysis,
                        enabled = !importing && !localAnalysisRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                localAnalysisRunning -> "正在分析本地画像"
                                importing -> "正在同步数据…"
                                else -> "重新分析画像"
                            },
                        )
                    }
                    Text(
                        "补齐优先作品标签并重算本地画像（评分 / 标签变动后使用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        localAnalysisRunning -> LocalAnalysisProgressContent(localAnalysisState)
                        refreshProgress != null -> RefreshProgressContent(refreshProgress)
                        localAnalysisState.error != null -> Text(
                            text = "上次分析未完成：${localAnalysisState.error}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FilledTonalButton(
                        onClick = onClassifyTags,
                        enabled = classifying || (
                            !importing && !localAnalysisRunning && featureSnapshotReady && (pendingTagCount ?: 0) > 0
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                classifying -> "停止标签分类"
                                localAnalysisRunning -> "本地画像完成后可进行 AI 分类"
                                !featureSnapshotReady -> processingSummary?.let {
                                    "正在收集完整标签（${it.coveredPriorityWorks}/${it.totalPriorityWorks}）…"
                                } ?: "正在收集完整标签…"
                                pendingTagCount == null -> "正在检查待分类标签…"
                                pendingTagCount == 0 -> "没有待分类的新标签"
                                else -> "用 AI 细分标签（$pendingTagCount 个待处理）"
                            },
                        )
                    }
                    Text(
                        "让 AI 把认不出类别的社区标签归到更准确的维度，口味画像和预计评分会更准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "建议先生成本地画像，再用 AI 细分标签。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 升维分类进度（紧跟按钮下方）。
                    tagClassifyProgress?.let { TagClassifyProgressContent(it) }
                    if (classificationState.total > 0) {
                        Text(
                            "计划 ${classificationState.total} · 已处理 ${classificationState.completed} · " +
                                "自动 ${classificationState.classified} · 复核 ${classificationState.manualReview} · " +
                                "噪声 ${classificationState.rejected} · 失败 ${classificationState.failed} · " +
                                "剩余 ${classificationState.remaining}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        classificationState.lastError?.let { error ->
                            Text(
                                "最后错误：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    processingSummary?.let { summary ->
                        Text(
                            "本地唯一标签 ${summary.localUniqueTags} · AI 终态处理 ${summary.aiTerminalUniqueTags} · " +
                                "累计去重 ${summary.cumulativeUniqueTags}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "优先作品标签覆盖 ${summary.coveredPriorityWorks}/${summary.totalPriorityWorks} " +
                                "（${(summary.coverageFraction * 100).roundToInt()}%）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "本地作品资料 ${summary.localFeatureCount} 部（完整标签 ${summary.verifiedFeatureCount} 部）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (manualReviewCount > 0) {
                        FilledTonalButton(
                            onClick = onOpenManualReview,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("人工复核低置信标签（$manualReviewCount）")
                        }
                    }
                }
                // AI 升维效果——展示升维分类把多少笼统「题材」标签细化到了哪些精确维度。
                dimensionSummary?.takeIf { !it.isEmpty }?.let { TagDimensionUpgradeSection(it) }
                val lowSample = profile.confidence < 0.3f
                if (lowSample) {
                    Text(
                        "样本较少，以下为初步倾向，仅供参考（可能 / 倾向于）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (profile.titles.isNotEmpty()) {
                    SectionCard("口味称号") {
                        FlowRowChips(profile.titles)
                    }
                }

                SectionCard("评分习惯") {
                    val hedge = if (lowSample) "可能" else ""
                    StatLine(
                        "平均分",
                        if (profile.avgScore > 0f) "$hedge ${oneDecimal(profile.avgScore)} 分".trim() else "暂无数据",
                    )
                    StatLine("评分严格度", percentText(profile.strictness, lowSample))
                    StatLine("高分稀有度", percentText(profile.highScoreRarity, lowSample))
                    StatLine("常见分段", profile.commonScoreBand ?: "暂无数据")
                }

                // 12 维引擎画像——按维度拆解偏好 + 偏爱题材组合（仅在画像可用时展示）。
                advancedProfile?.takeIf { it.isUsable }?.let { adv ->
                    adv.divergence?.let { DivergenceSection(it) }
                    TasteDimensionsSection(adv)
                    TasteCombosSection(adv.combos)
                }

                val highTags = profile.tagStats
                    .filter { it.bucket == TagBucket.HIGH_SCORE && TasteTagTaxonomy.isSelectableGenre(it.tagName) }
                    .map { it.tagName }
                val lowTags = profile.tagStats
                    .filter { it.bucket == TagBucket.LOW_SCORE && TasteTagTaxonomy.isSelectableGenre(it.tagName) }
                    .map { it.tagName }
                SectionCard("高分倾向标签") {
                    if (highTags.isEmpty()) Text("暂无数据") else FlowRowChips(highTags)
                }
                SectionCard("低分倾向标签") {
                    if (lowTags.isEmpty()) {
                        Text("低分样本不足（评分较低的作品太少，暂无法统计避雷标签）")
                    } else {
                        FlowRowChips(lowTags)
                    }
                    val overlap = highTags.toSet().intersect(lowTags.toSet())
                    if (overlap.isNotEmpty()) {
                        Text(
                            "同一标签可能同时出现在高分和低分作品中，表示它在不同作品里的表现并不一致；这不会改变画像计算。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SectionCard("常被搁置或弃坑的类型") {
                    if (profile.blackHole.isEmpty()) Text("暂无数据") else FlowRowChips(profile.blackHole)
                }

                // 画像概况——真实样本量 + 各维度数据覆盖率（透明度 / 可信度）。
                advancedProfile?.takeIf { it.isUsable }?.let { adv ->
                    ProfileOverviewSection(adv)
                }
            }
        }
    }
}

@Composable
private fun LocalAnalysisProgressContent(state: TasteLocalAnalysisState) {
    when (state.phase) {
        TasteLocalAnalysisPhase.COLLECTING -> {
            val suffix = if (state.total > 0) "（${state.completed}/${state.total}）" else ""
            Text(
                "正在收集优先作品标签$suffix",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        TasteLocalAnalysisPhase.BUILDING -> {
            Text("正在分析本地画像", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        TasteLocalAnalysisPhase.IDLE -> Unit
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 你和大众的分歧——个人分歧模型的可读版本：打分宽严、跟不跟口碑、哪些题材你比大众更偏爱 /
 * 更不买账，以及小众 / 年代 / 篇幅上的取向。数字都是「10 分制下大约差多少分」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DivergenceSection(model: PersonalDivergenceModel) {
    SectionCard("你和大众的分歧") {
        Text(
            "大多数作品你的评分会和社区接近；下面是你稳定偏离大众的地方（基于 ${model.sampleCount} 部评分）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatLine(
            "打分宽严",
            when {
                model.leniency >= LENIENCY_NOTABLE -> "比社区均分高 ${oneDecimal(model.leniency.toFloat())} 分，手偏松"
                model.leniency <= -LENIENCY_NOTABLE -> "比社区均分低 ${oneDecimal((-model.leniency).toFloat())} 分，手偏紧"
                else -> "和社区均分基本持平"
            },
        )
        StatLine(
            "跟随口碑",
            when {
                model.slope >= SLOPE_STRONG -> "口碑好坏对你的评分影响很大（拉得比大众更开）"
                model.slope >= SLOPE_WEAK -> "基本跟着口碑走"
                else -> "不太看口碑，更按自己的标准打分"
            },
        )
        StatLine("分歧幅度", "平均偏离大众水准 ±${oneDecimal(model.residualStd.toFloat())} 分")

        val (liked, disliked) = model.strongestTags(DIVERGENCE_TAG_LIMIT)
        if (liked.isNotEmpty()) {
            Text("比大众更偏爱", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                liked.forEach { TagPill(text = "${it.label} ${signedDelta(it.delta)}", tone = TagPillTone.Primary) }
            }
        }
        if (disliked.isNotEmpty()) {
            Text("不如大众买账", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                disliked.forEach { TagPill(text = "${it.label} ${signedDelta(it.delta)}", tone = TagPillTone.Neutral) }
            }
        }
        model.axisTendencies().filter { kotlin.math.abs(it.delta) >= AXIS_NOTABLE }.forEach { axis ->
            val (favored, other) = if (axis.delta > 0) {
                axis.positiveLabel to axis.negativeLabel
            } else {
                axis.negativeLabel to axis.positiveLabel
            }
            Text(
                "同等口碑下，你给$favored 的分比$other 高约 ${oneDecimal(kotlin.math.abs(axis.delta).toFloat())} 分",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun signedDelta(delta: Double): String = "%+.1f".format(delta)

private const val LENIENCY_NOTABLE = 0.3
private const val SLOPE_STRONG = 1.25
private const val SLOPE_WEAK = 0.6
private const val AXIS_NOTABLE = 0.15
private const val DIVERGENCE_TAG_LIMIT = 6

/** 口味维度——把偏好按 12 维（题材 / 角色类型 / 声优 / 来源…）拆解，每维展示代表标签。 */
@Composable
private fun TasteDimensionsSection(profile: AdvancedTasteProfile) {
    val dims = DIMENSION_DISPLAY_ORDER.mapNotNull { cat ->
        val pref = profile.categories[cat] ?: return@mapNotNull null
        val likes = pref.positive.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key }
        if (likes.isEmpty()) null else cat to likes
    }
    if (dims.isEmpty()) return
    SectionCard("口味维度") {
        Text(
            "按不同维度拆解你的偏好：标签来自你高分作品的社区标签，越靠前越偏爱。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        dims.forEach { (cat, likes) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cat.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRowChips(likes)
            }
        }
    }
}

/** 偏爱的题材组合——从高分样本挖掘的题材 / 情节组合，按强度取前若干。 */
@Composable
private fun TasteCombosSection(combos: List<TopicCombo>) {
    val top = combos.sortedByDescending { it.strength }.take(8)
    if (top.isEmpty()) return
    SectionCard("偏爱的题材组合") {
        Text(
            "从你的高分作品里挖掘出的题材 / 情节组合，越靠前越常见。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowChips(top.map { it.tags.joinToString(" + ") })
    }
}

/**
 * AI「升维」效果——本地规则把大量未知社区标签统统兜底成笼统「题材」；这里展示 AI 升维分类
 * 已把多少这类标签细化到了哪些**更精确**的维度（情节 / 角色类型 / 来源 / 年代 / 社区梗…），让用户看到
 * 「AI 标签升维分类」按钮的实际收益，而非只有一次性 Toast。噪声维度另行说明（已从画像主分剔除）。
 */
@Composable
private fun TagDimensionUpgradeSection(summary: TagDimensionSummary) {
    val refined = summary.buckets.filter { it.category != TasteCategory.NOISE }
    val noiseCount = summary.buckets.firstOrNull { it.category == TasteCategory.NOISE }?.count ?: 0
    val refinedTotal = refined.sumOf { it.count }
    SectionCard("AI 标签细分") {
        Text(
            "原本被笼统归为「题材」的社区标签，经 AI 细分后归到了更具体的维度，" +
                "用于提高画像分类与匹配评分的准确度。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (refinedTotal > 0) {
            Text(
                "已细分 $refinedTotal 个标签",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRowChips(refined.map { "${it.category.label} × ${it.count}" })
        }
        if (noiseCount > 0) {
            Text(
                "另有 $noiseCount 个被识别为噪声（如泄漏标题 / 联想梗），已从画像主分中剔除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 画像概况——真实样本量 + 用户均分 + 各维度数据覆盖率（透明度 / 可信度）。 */
@Composable
private fun ProfileOverviewSection(profile: AdvancedTasteProfile) {
    SectionCard("画像概况") {
        StatLine("参与画像的评分样本", "${profile.sampleCount} 部")
        StatLine("有评分的作品", "${profile.ratedSubjectScores.size} 部")
        StatLine(
            "进入评分模型",
            profile.divergence?.let { "${it.examples.size} 部" } ?: "未建立（有社区评分的作品不足）",
        )
        StatLine("口味匹配度算法", profile.matchBranch.label)
        if (profile.userAvgRating > 0.0) {
            StatLine("你的平均分", "${oneDecimal(profile.userAvgRating.toFloat())} 分")
        }
        Text(
            "各维度数据覆盖（越高说明该维度线索越充分，匹配越可信）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatLine("题材 / 标签", coveragePercent(profile.coverage.tag))
        StatLine("制作阵容", coveragePercent(profile.coverage.staff))
        StatLine("评价语义", coveragePercent(profile.coverage.comment))
        StatLine("年代", coveragePercent(profile.coverage.time))
    }
}

/** 覆盖率 [0,1] → 百分比文案；≤0 显示「暂无」。 */
private fun coveragePercent(v: Double): String = if (v <= 0.0) "暂无" else "${(v * 100).roundToInt()}%"

/** 口味维度展示顺序（跳过 COMBO 单列 / COMMUNITY 技术先验 / NOISE 噪声）。 */
private val DIMENSION_DISPLAY_ORDER: List<TasteCategory> = listOf(
    TasteCategory.TOPIC,
    TasteCategory.DEVICE,
    TasteCategory.XP,
    TasteCategory.CHARACTER,
    TasteCategory.STAFF,
    TasteCategory.CV,
    TasteCategory.SOURCE,
    TasteCategory.TIME,
    TasteCategory.MEME,
    TasteCategory.COMMENT,
)

/** 联网分析进度内容；补齐特征阶段显示确定进度，构建阶段显示不确定进度。 */
@Composable
private fun RefreshProgressContent(progress: TasteRefreshProgress) {
    val label = when (progress.phase) {
        TasteRefreshProgress.Phase.FETCHING_RATED -> "正在补齐已评分作品特征"
        TasteRefreshProgress.Phase.FETCHING_POOL -> "正在补齐候选池特征（提升未评分作品打分准确度）"
        TasteRefreshProgress.Phase.BUILDING -> "正在构建口味画像"
    }
    val suffix = if (progress.isDeterminate) "（${progress.current}/${progress.total}）" else ""
    Text("$label$suffix", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    if (progress.isDeterminate) {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** AI 标签升维分类进度内容（内联于「分析与优化」卡片）——确定进度条 + “done/total”。 */
@Composable
private fun TagClassifyProgressContent(progress: TagClassifyProgress) {
    val total = progress.total.coerceAtLeast(1)
    val phase = when (progress.phase) {
        TagClassificationPhase.REQUESTING -> "请求中"
        TagClassificationPhase.VALIDATING -> "校验中"
        TagClassificationPhase.WRITING -> "写入中"
    }
    Text(
        "第 ${progress.batchIndex.coerceAtLeast(1)}/${progress.batchCount.coerceAtLeast(1)} 批$phase" +
            "（${progress.done}/${progress.total}）",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
    if (progress.done == 0 && progress.phase == TagClassificationPhase.REQUESTING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { progress.done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 将画像生成时间格式化为本地时间；非正值表示尚未分析。 */
private fun formatLastUpdated(millis: Long): String {
    if (millis <= 0L) return "尚未分析"
    val zoned = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(zoned)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FlatAssistChip(onClick = {}, label = { Text(cleanTagLabel(it)) }) }
    }
}

/** 标签清洗：将下划线和连字符转为空格，并折叠多余空白。 */
private fun cleanTagLabel(raw: String): String =
    raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

private fun oneDecimal(v: Float): String = ((v * 10).roundToInt() / 10.0).toString()

private fun percentText(v: Float, lowSample: Boolean): String {
    if (v <= 0f) return "暂无数据"
    val pct = (v * 100).roundToInt()
    val prefix = if (lowSample) "倾向于 " else ""
    return "$prefix$pct%"
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun TasteProfileScreenPreview() {
    AcgCompassTheme {
        TasteProfileScreen(
            state = UiState.Success(
                TasteProfile(
                    id = "t1",
                    strictness = 0.4f,
                    avgScore = 7.6f,
                    highScoreRarity = 0.7f,
                    commonScoreBand = "7-8 分",
                    titles = listOf("严格评分官", "「治愈」头号粉丝"),
                    confidence = 0.6f,
                    generatedAt = 0L,
                    blackHole = listOf("长篇", "致郁"),
                ),
            ),
            onBack = {},
        )
    }
}

private const val AI_CLASSIFY_MIN_COVERAGE = 0.8f

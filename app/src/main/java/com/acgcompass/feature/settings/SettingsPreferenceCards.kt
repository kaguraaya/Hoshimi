package com.acgcompass.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.OptionList
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.domain.model.ThemePreset
import com.acgcompass.domain.taste.TasteMatchBranch

internal val ThemeModeOptions: List<Pair<String, ThemeMode>> = listOf(
    "跟随系统" to ThemeMode.SYSTEM,
    "浅色" to ThemeMode.LIGHT,
    "深色" to ThemeMode.DARK,
)

@Composable
internal fun AppearanceCard(data: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val showThemePresets = !supportsDynamicColor || !data.dynamicColor
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "外观", style = MaterialTheme.typography.titleMedium)
            Text(text = "主题模式", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                ThemeModeOptions.forEach { (label, mode) ->
                    FlatFilterChip(
                        selected = data.themeMode == mode,
                        onClick = { actions.onSetThemeMode(mode) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            if (supportsDynamicColor) {
                ToggleRow(
                    label = "动态取色（Material You）",
                    description = "使用系统壁纸配色；关闭后使用应用内置配色。",
                    checked = data.dynamicColor,
                    enabled = true,
                    onCheckedChange = actions.onSetDynamicColor,
                )
            }
            if (showThemePresets) {
                ThemeColorPicker(data = data, actions = actions)
            }
            ToggleRow(
                label = "预测性返回手势",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "返回时预览上一页；关闭后使用普通返回。"
                } else {
                    "需要 Android 13 或更高版本；当前自动使用普通返回。"
                },
                checked = data.predictiveBackEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                onCheckedChange = actions.onSetPredictiveBackEnabled,
            )
        }
    }
}

/** 照 FlClash：只选一个主题色，浅色 / 深色各自用它生成对应的配色（同一套搭配）。 */
@Composable
internal fun ThemeColorPicker(data: SettingsUiState, actions: SettingsActions) {
    Text("主题色", style = MaterialTheme.typography.bodyMedium)
    ThemePresetRow(
        selected = data.lightThemePreset,
        onSelect = { preset ->
            actions.onSetLightThemePreset(preset)
            actions.onSetDarkThemePreset(preset)
        },
    )
    Text(
        "浅色和深色会各自用这个颜色生成对应的配色。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 主题色：一排色块（照 FlClash），选中的一块里打勾；颜色就是选项本身。 */
@Composable
internal fun ThemePresetRow(selected: ThemePreset, onSelect: (ThemePreset) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
    ) {
        ThemePreset.entries.forEach { preset ->
            val color = Color(preset.seed)
            val isSelected = preset == selected
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(color)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(preset) })
                    .semantics { contentDescription = preset.label },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (color.luminance() > SWATCH_DARK_CHECK) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal val SWATCH_SIZE = 40.dp

internal const val SWATCH_DARK_CHECK = 0.5f

// region 隐私卡片

/** 设置二级页里的一组设置：标题 + 内容，直接展开。 */
@Composable
internal fun SettingsGroupCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun PrivacyToggles(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "允许把短评发给 AI",
        description = "开启后，详情页的「AI 匹配分析」和「评论摘要」会把你在 Bangumi 写的短评、" +
            "以及 Bangumi 社区的公开短评发给 AI，用来判断口味、总结口碑。关闭（默认）时只发标题、标签和评分；" +
            "本机计算的口味画像不受影响。",
        checked = card.allowAiAnalyzeReviews,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.ALLOW_AI_ANALYZE_REVIEWS, it) },
    )
    ToggleRow(
        label = "记录本地时光机快照",
        description = "在本机记录评分、短评和观看状态的变化，用于时光机和年度报告",
        checked = card.recordTimeMachineSnapshots,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.RECORD_TIME_MACHINE_SNAPSHOTS, it) },
    )
    ToggleRow(
        label = "记录最近搜索",
        description = "在发现页的搜索框下面显示最近搜过的关键词，只存在本机。关掉后停止记录，并清空已有记录。",
        checked = card.searchHistoryEnabled,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.SEARCH_HISTORY, it) },
    )
}

@Composable
internal fun AutoSyncSettings(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "加入待补池时同步为 Bangumi「想看」",
        description = "已登录 Bangumi 时，从任何地方加入待补池的作品都会在后台标成「想看」；" +
            "Bangumi 上已经有收藏状态（看过、在看等）的不会改动，没网时会稍后重试",
        checked = card.syncBacklogToBangumi,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.SYNC_BACKLOG_TO_BANGUMI, it) },
    )
    Text(text = "自动同步 Bangumi", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "按所选间隔在后台自动拉取最新收藏（需已配置 Bangumi 账号、联网）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        AutoSyncIntervalOptions.forEach { (label, minutes) ->
            FlatFilterChip(
                selected = card.autoSyncIntervalMinutes == minutes,
                onClick = { actions.onSetAutoSyncInterval(minutes) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun RecommendationSettings(card: PrivacyCardState, actions: SettingsActions) {
    RecommendPreferencesEntry(actions)
    val branch = TasteMatchBranch.fromKey(card.tasteMatchBranch)
    OptionList(
        title = "口味匹配度算法",
        options = TasteMatchBranch.entries,
        selected = branch,
        label = { it.label },
        onSelect = { actions.onSetTasteMatchBranch(it.key) },
    )
    Text(
        text = branch.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlatDivider()
    Text(
        text = "「今晚看什么」只推荐社区均分 ≥ 下限、且口味匹配度 ≥ 阈值的作品；阈值越高越严格（结果可能更少）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = "推荐最低社区分", style = MaterialTheme.typography.bodyMedium)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        RecommendMinScoreOptions.forEach { (label, score) ->
            FlatFilterChip(
                selected = kotlin.math.abs(card.recommendMinCommunityScore - score) < 0.01f,
                onClick = { actions.onSetRecommendMinScore(score) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    Text(text = "口味匹配度阈值（低于则不推）", style = MaterialTheme.typography.bodyMedium)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        TasteMatchThresholdOptions.forEach { (label, threshold) ->
            FlatFilterChip(
                selected = kotlin.math.abs(card.tasteMatchThreshold - threshold) < 0.01f,
                onClick = { actions.onSetTasteMatchThreshold(threshold) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    ToggleRow(
        label = "自动补充其它平台评分",
        description = "打开后马上补一批，之后联网且电量不低时约每 6 小时一次：为只有一个平台评分的动画去 AniList、" +
            "Tenrai 等补查评分，每次最多 20 部，收藏和待补池里的作品优先；请求都让你正在看的页面先走",
        checked = card.scoreDiffAutoBackfill,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.SCORE_DIFF_AUTO_BACKFILL, it) },
    )
    AiDailyDrawToggle(card, actions)
}

/** 「推荐偏好」入口（探索队列和今晚看什么共用的排序偏好）。 */
@Composable
private fun RecommendPreferencesEntry(actions: SettingsActions) {
    SettingsSummaryRow(
        title = "推荐偏好",
        statusText = "探索队列和今晚看什么看重什么：口味、口碑、新番、冷门，或专找社区低估的作品",
        lastTestedAtText = null,
        onClick = actions.onOpenRecommendPreferences,
    )
    FlatDivider()
}

/** 首页「今日补番签」由 AI 生成：配置了 AI 提供商之后才能打开。 */
@Composable
internal fun AiDailyDrawToggle(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "今日补番签由 AI 生成",
        description = if (card.aiConfigured) {
            "每天一次，把待补池里的作品名和集数发给你配置的 AI 提供商，生成针对你待补池的「宜 / 忌」；" +
                "失败时用内置的签"
        } else {
            "需要先在「数据源 → AI 提供商」里配置后才能打开"
        },
        checked = card.aiDailyDraw && card.aiConfigured,
        enabled = card.aiConfigured,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.AI_DAILY_DRAW, it) },
    )
}

@Composable
internal fun DataManagementActions(actions: SettingsActions) {
    FilledTonalButton(onClick = actions.onExport, modifier = Modifier.fillMaxWidth()) {
        Text("导出备份（默认不含 key）")
    }
    FilledTonalButton(onClick = actions.onImport, modifier = Modifier.fillMaxWidth()) {
        Text("导入备份")
    }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.BACKLOG) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 待补池", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.RATINGS) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 评分", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.RATED_WORKS) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 已评分作品", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.TIME_MACHINE) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 时光机", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.TASTE_PROFILE) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 口味画像标签", maxLines = 1) }
}

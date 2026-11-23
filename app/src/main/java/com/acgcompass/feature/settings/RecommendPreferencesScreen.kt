package com.acgcompass.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.recommend.RecommendPreferences

@Composable
fun RecommendPreferencesRoute(onBack: () -> Unit, viewModel: RecommendPreferencesViewModel = hiltViewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    RecommendPreferencesScreen(
        prefs = prefs ?: RecommendPreferences.DEFAULT,
        onBack = onBack,
        onChange = viewModel::update,
        onReset = viewModel::reset,
    )
}

/**
 * 设置 → 推荐偏好：探索队列和「今晚看什么」在平常的排序（预计满意度）之上额外看重什么。
 * 预设一键切换；口味契合、社区口碑（含「被低估」）、新番、冷门四项按档位（不看 / 低 / 中 / 高）加权；探索队列的跨界名额。
 * 全部默认时排序不变。组合方式见 [com.acgcompass.domain.recommend.PreferenceRanking]。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecommendPreferencesScreen(
    prefs: RecommendPreferences,
    onBack: () -> Unit,
    onChange: ((RecommendPreferences) -> RecommendPreferences) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AcgScreenScaffold(
        title = "推荐偏好",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenContentPadding.lazyList(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    "探索队列和「今晚看什么」平常按「预计满意度」排，也就是预计你会打几分（相对你自己的平均分）。" +
                        "这里可以在它之上再看重几项，下次生成时生效；全部选「不看」就是平常的排序。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "presets") { PresetCard(prefs, onChange) }
            item(key = "weights") { WeightCard(prefs, onChange) }
            item(key = "how") { HowCard() }
            item(key = "discovery") { DiscoveryCard(prefs, onChange) }
            item(key = "reset") {
                FilledTonalButton(onClick = onReset, enabled = !prefs.isDefault) { Text("恢复默认") }
            }
        }
    }
}

@Composable
private fun PresetCard(
    prefs: RecommendPreferences,
    onChange: ((RecommendPreferences) -> RecommendPreferences) -> Unit,
) {
    // 预设只管排序的几项；跨界名额是单独的设置，切预设不动它。
    val current = RecommendPreferences.PRESETS.firstOrNull { (_, p) -> p.sameRanking(prefs) }?.first
    SettingsGroupCard("快速选择") {
        ChipRow {
            RecommendPreferences.PRESETS.forEach { (label, preset) ->
                FlatFilterChip(
                    selected = label == current,
                    onClick = { onChange { it.withRankingOf(preset) } },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Text(
            text = (current?.let { "当前：$it。" } ?: "当前：自定义。") + (PRESET_NOTES[current] ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightCard(
    prefs: RecommendPreferences,
    onChange: ((RecommendPreferences) -> RecommendPreferences) -> Unit,
) {
    SettingsGroupCard("看重什么") {
        LevelRow(
            label = "口味契合",
            description = "口味匹配度高的往前：题材、相似作品、常看的类型对得上。推荐更贴合你，" +
                "公认的经典名作会少一些。想多找可能成为心头好的作品，先调这一项。",
            labels = LEVEL_LABELS,
            selected = prefs.taste,
            onSelect = { level -> onChange { it.copy(taste = level) } },
        )
        FlatDivider()
        LevelRow(
            label = "社区口碑",
            description = "社区分高的往前，推荐更稳妥（评分人数少的作品，社区分按中间值打折）。" +
                "选「不看」时平常的排序仍会参考社区分，只是不额外加权。" +
                "「被低估」：只看社区分低于下面这条线的作品，专找社区评价一般、但你可能喜欢的。",
            labels = COMMUNITY_LABELS,
            selected = prefs.community - RecommendPreferences.UNDERRATED,
            onSelect = { index -> onChange { it.copy(community = index + RecommendPreferences.UNDERRATED) } },
        )
        if (prefs.underrated) UnderratedThreshold(prefs, onChange)
        FlatDivider()
        LevelRow(
            label = "新番",
            description = "首播越近越往前。",
            labels = LEVEL_LABELS,
            selected = prefs.freshness,
            onSelect = { level -> onChange { it.copy(freshness = level) } },
        )
        FlatDivider()
        LevelRow(
            label = "冷门",
            description = "评分人数少的往前。人数和同一年开播的 TV 动画比：前 15% 算霸权、前 25% 算热门、" +
                "前 45% 算普通，其余算冷门；对现在的新番，大约是播完半年 3000 / 2000 / 1000 人。" +
                "冷门作品看过的人少，调高后推荐的把握会变小。",
            labels = LEVEL_LABELS,
            selected = prefs.obscurity,
            onSelect = { level -> onChange { it.copy(obscurity = level) } },
        )
    }
}

@Composable
private fun UnderratedThreshold(
    prefs: RecommendPreferences,
    onChange: ((RecommendPreferences) -> RecommendPreferences) -> Unit,
) {
    Text(text = "社区分低于", style = MaterialTheme.typography.bodyMedium)
    ChipRow {
        RecommendPreferences.UNDERRATED_THRESHOLDS.forEach { below ->
            FlatFilterChip(
                selected = prefs.underratedBelow == below,
                onClick = { onChange { it.copy(underratedBelow = below) } },
                label = { Text("%.1f 分".format(below / TENTHS), maxLines = 1) },
            )
        }
    }
    Text(
        text = "评分人数不到 ${RecommendPreferences.UNDERRATED_MIN_VOTES} 的作品不算（社区分还不可信）。" +
            "线越低，越难找到对口味的作品。开着「被低估」时，「今晚看什么」的「推荐最低社区分」不起作用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HowCard() {
    SettingsGroupCard("几项怎么组合") {
        Text(
            text = "每一项先换算成「在这次的候选里比平均高多少」（以标准差为单位），几项就能放在一起比；" +
                "再按档位加到预计满意度上：低 = 0.25，中 = 0.5，高 = 1，冷门减半。" +
                "比如口味契合选「中」，匹配度比平均高 1 个标准差，就相当于预计满意度高半个标准差。" +
                "几项同时调，效果直接相加。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiscoveryCard(
    prefs: RecommendPreferences,
    onChange: ((RecommendPreferences) -> RecommendPreferences) -> Unit,
) {
    SettingsGroupCard("探索队列的跨界名额") {
        Text(
            text = "每批留几张给「口碑好、但不在你常看题材里」的作品，让口味有机会往新方向长。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipRow {
            DISCOVERY_LABELS.forEachIndexed { level, label ->
                FlatFilterChip(
                    selected = prefs.discovery == level,
                    onClick = { onChange { it.copy(discovery = level) } },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun LevelRow(label: String, description: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipRow {
            labels.forEachIndexed { value, text ->
                FlatFilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) { content() }
}

/** 各项 0–3 档。 */
private val LEVEL_LABELS = listOf("不看", "低", "中", "高")

/** 社区口碑：被低估 + 0–3 档。 */
private val COMMUNITY_LABELS = listOf("被低估") + LEVEL_LABELS

/** 跨界名额 0–3 的叫法。 */
private val DISCOVERY_LABELS = listOf("不留", "少", "默认", "多")

private const val TENTHS = 10.0

/** 预设的一句说明。 */
private val PRESET_NOTES = mapOf(
    "均衡（默认）" to "平常的排序。",
    "更合口味" to "口味契合「中」：更贴合你，经典名作少一些。",
    "社区低估" to "社区口碑「被低估」（低于 7 分）+ 口味契合「中」。",
    "口碑稳妥" to "社区口碑「中」：更稳妥。",
    "新番优先" to "新番「中」。",
    "冷门但对口" to "冷门「中」+ 口味契合「中」，避免冷门但不对口味的作品。",
)

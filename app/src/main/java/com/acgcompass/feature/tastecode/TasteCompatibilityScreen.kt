package com.acgcompass.feature.tastecode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.RadarChart
import com.acgcompass.core.designsystem.RadarSeries
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.tastecode.CompatibilityPart
import com.acgcompass.domain.tastecode.CompatibilityResult

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TasteCompatibilityRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: TasteCompatibilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasteCompatibilityScreen(state = state, onBack = onBack, onOpenWork = onOpenWork)
}

/** 口味相性页：契合度、构成雷达、维度对比、结论、喜好与雷点、共同代表作和对方的推荐。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TasteCompatibilityScreen(state: CompatibilityUiState, onBack: () -> Unit, onOpenWork: (String) -> Unit) {
    AcgScreenScaffold(
        title = "口味相性",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    ) { innerPadding ->
        when (val s = state) {
            CompatibilityUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            CompatibilityUiState.InvalidCode -> Message("这段口味码打不开", "可能复制得不完整，或者被改动过。请让对方重新发一次。")
            CompatibilityUiState.NoOwnProfile -> Message("还没有你的口味画像", "先同步 Bangumi 收藏并给看过的作品打分，算出口味画像后就能对比。")
            is CompatibilityUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ScreenContentPadding.lazyList(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "score") { ScoreCard(s.otherName, s.result) }
                item(key = "parts") { PartsCard(s.result) }
                if (dimensionAxes(s).size >= MIN_RADAR_AXES) item(key = "dimensions") { DimensionsCard(s) }
                if (s.insights.isNotEmpty()) item(key = "insights") { InsightsCard(s.insights) }
                item(key = "tags") { TagsCard(s.otherName, s.result) }
                if (s.result.commonWorks.isNotEmpty()) item(key = "common") { CommonWorksCard(s, onOpenWork) }
                if (s.picks.isNotEmpty()) item(key = "picks") { PicksCard(s.otherName, s.picks, onOpenWork) }
                item(key = "note") {
                    Text(
                        "契合度只由两份口味码计算，你们看到的是同一个分数；推荐已去掉你收藏过的作品。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 总分、分档称号和相处模式。 */
@Composable
private fun ScoreCard(otherName: String, result: CompatibilityResult) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "我 × $otherName",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${result.score}",
                fontSize = SCORE_SIZE,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(result.tier.label, style = MaterialTheme.typography.headlineSmall)
            Text(
                result.tier.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TagPill("相处模式 · ${result.type.label}")
            }
            Text(
                result.type.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (result.algorithmMismatch) {
                Text(
                    "你们的口味算法版本不同（有一方没更新应用），结果仅供参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 契合度怎么算的：各部分的分数画成雷达图（部分不足三项时画分数条）。 */
@Composable
private fun PartsCard(result: CompatibilityResult) {
    Section("契合度的构成") {
        val parts = result.parts.entries.toList()
        if (parts.size >= MIN_RADAR_AXES) {
            RadarChart(
                axes = parts.map { (part, value) -> "${part.label}\n${(value * PERCENT).toInt()}" },
                series = listOf(RadarSeries("契合度", parts.map { it.value }, MaterialTheme.colorScheme.primary)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = RADAR_SIDE_PADDING),
            )
        } else {
            parts.forEach { (part, value) -> ScoreBar(part.label, value) }
        }
        if (CompatibilityPart.WORKS !in result.parts) {
            Text(
                "两人都放进代表作的作品不足 3 部，这一次没有计算「共同作品的评分」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 两人的口味维度叠在一张雷达图上。 */
@Composable
private fun DimensionsCard(state: CompatibilityUiState.Ready) {
    val axes = dimensionAxes(state)
    Section("口味维度对比") {
        RadarChart(
            axes = axes.map { CompatibilityInsights.DIMENSION_LABELS.getValue(it) },
            series = listOf(
                RadarSeries("我", axes.map { state.mine.dimensions[it] ?: 0f }, MaterialTheme.colorScheme.primary),
                RadarSeries(
                    state.otherName,
                    axes.map { state.other.dimensions[it] ?: 0f },
                    MaterialTheme.colorScheme.tertiary,
                ),
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = RADAR_SIDE_PADDING),
        )
        val mine = axes.map { state.mine.dimensions[it] ?: 0f }
        val theirs = axes.map { state.other.dimensions[it] ?: 0f }
        if (mine.zip(theirs).all { (a, b) -> kotlin.math.abs(a - b) < SAME_DIMENSION }) {
            Text(
                "两条线完全重合（虚线是${state.otherName}）：你们的口味维度一模一样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "每个人按自己影响最大的一项为满格，比较的是侧重，不是谁看得多。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dimensionAxes(state: CompatibilityUiState.Ready): List<String> =
    CompatibilityInsights.DIMENSION_LABELS.keys.filter { it in state.mine.dimensions || it in state.other.dimensions }

private val SCORE_SIZE = 64.sp
private const val MIN_RADAR_AXES = 3
private const val SAME_DIMENSION = 0.02f
private const val PERCENT = 100
private val RADAR_SIDE_PADDING = 8.dp

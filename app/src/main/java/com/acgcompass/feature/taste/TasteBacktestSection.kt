package com.acgcompass.feature.taste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.domain.taste.TasteBacktest
import com.acgcompass.domain.taste.TasteMatchBranch

/**
 * 评分和口味的关系（回测）：你的评分和口味匹配度的排序关联，以及预计评分的回测误差。
 * 只描述统计上的关系，不推断「你打分有百分之几看口味」。
 */
@Composable
internal fun TasteBacktestSection(branch: TasteMatchBranch, backtest: TasteBacktest?) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("评分和口味的关系（回测）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                backtest == null -> Note("回测计算中：重建口味画像后，后台约一分钟算完")
                backtest.entries.isEmpty() -> Note("评过分的作品少于 ${TasteBacktest.MIN_RATED} 部，暂不回测")
                else -> {
                    if (backtest.updating) Note("画像刚更新，正在重新回测；下面先显示上一次的结果")
                    BacktestLines(branch, backtest)
                }
            }
            Note(
                "回测：把你评过的作品分成 5 份，每次用其余作品建画像，再给留出的那份打分；" +
                    "所以这些数是算法自己算出来的，不是按你的评分换算的。",
            )
        }
    }
}

@Composable
private fun BacktestLines(branch: TasteMatchBranch, backtest: TasteBacktest) {
    backtest.coupling(branch)?.let { r ->
        Line("评分和口味匹配度（${branch.label}）的关联", "%.2f".format(r))
        Note(couplingMeaning(r))
    }
    val mae = backtest.ratingMae
    val rho = backtest.ratingSpearman
    if (mae != null && rho != null) {
        Line("预计评分和你的实际评分", "平均差 %.2f 分 · 排序一致 %.2f".format(mae, rho))
    }
}

/** 关联的读法（统计说法）。 */
internal fun couplingMeaning(r: Double): String = when {
    r >= STRONG -> "关联较强：口味越对的作品，你打分通常也越高"
    r >= WEAK -> "有一定关联：口味对的作品你常打高分，但打分也明显看别的（例如口碑、剧情完成度）"
    else -> "关联不大：你的打分主要不由口味契合决定，口味很对但分数一般的情况会比较常见"
}

private const val STRONG = 0.4
private const val WEAK = 0.15

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

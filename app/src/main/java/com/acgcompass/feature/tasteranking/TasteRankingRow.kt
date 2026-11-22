package com.acgcompass.feature.tasteranking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.domain.taste.RankOwnership
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.WorkFormat

/** 口味排行的一行：名次、封面、标题与形式、理由和我与它的关系，右侧匹配度与预计评分。 */
@Composable
internal fun RankRow(rank: Int, item: TasteRankItem, onClick: () -> Unit) {
    PrismCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
            Cover(item.coverUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.reason?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                ownershipLabel(item)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ScoreColumn(item)
        }
    }
}

/** 右侧的匹配度和预计评分；已评分作品的回测还没算完时只写「回测中」（当前画像下的分把你自己的评分也算进去了）。 */
@Composable
private fun ScoreColumn(item: TasteRankItem) {
    Column(horizontalAlignment = Alignment.End) {
        if (item.scorePending) {
            Text("回测中", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(
            "${item.score}%",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        predictedLabel(item)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 预计评分：和详情页一样写分布的中位数（整数）；没有分布时写一位小数。 */
private fun predictedLabel(item: TasteRankItem): String? =
    item.predictedText?.let { "预计 $it 分" } ?: item.predictedRating?.let { "预计 %.1f".format(it) }

private fun meta(item: TasteRankItem): String = listOfNotNull(
    item.year?.let { "$it 年" },
    item.format.takeIf { it != WorkFormat.OTHER }?.label,
    when (item.finished) {
        true -> "已完结"
        false -> "连载中"
        null -> null
    },
).joinToString(" · ")

private fun ownershipLabel(item: TasteRankItem): String? = when (item.ownership) {
    RankOwnership.RATED -> "你评过 ${item.myRating} 分"
    RankOwnership.COLLECTED -> "已在收藏里"
    RankOwnership.BACKLOG -> "已在待补池"
    null -> null
}

@Composable
private fun Cover(coverUrl: String?) {
    Box(Modifier.size(width = 48.dp, height = 66.dp).clip(PrismCorner.Thumb).background(Surfaces.raisedColor)) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

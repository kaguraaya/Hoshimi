package com.acgcompass.feature.tastecode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TagPillRow
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.domain.tastecode.CompatibilityResult
import com.acgcompass.domain.tastecode.TagGroup
import kotlin.math.abs

/** 「你们俩」：从两份口味码里挑出来的差异和共同点。 */
@Composable
internal fun InsightsCard(insights: List<Insight>) {
    Section("你们俩") {
        insights.forEachIndexed { index, insight ->
            if (index > 0) FlatDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    insight.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(insight.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 喜好与雷点：都喜欢的按题材 / 角色属性 / 创作者分组，再列各自独爱、都回避和冲突。 */
@Composable
internal fun TagsCard(otherName: String, result: CompatibilityResult) {
    Section("喜好与雷点") {
        val groups = result.sharedLikes.groupBy { TagGroup.of(it.category) }
        TagGroup.entries.forEach { group ->
            LabeledTags("都喜欢的${group.label}", groups[group].orEmpty().map { it.tag }, TagPillTone.Primary)
        }
        LabeledTags("你独爱", result.onlyLiked.first.map { it.tag })
        LabeledTags("${otherName}独爱", result.onlyLiked.second.map { it.tag })
        LabeledTags("都回避", result.sharedAvoids.map { it.tag }, TagPillTone.Neutral)
        LabeledTags("你喜欢、${otherName}回避", result.clashes.first, TagPillTone.Neutral)
        LabeledTags("${otherName}喜欢、你回避", result.clashes.second, TagPillTone.Neutral)
        val nothing = listOf<List<Any>>(
            result.sharedLikes,
            result.sharedAvoids,
            result.clashes.first,
            result.clashes.second,
            result.onlyLiked.first,
            result.onlyLiked.second,
        ).all { it.isEmpty() }
        if (nothing) {
            Text(
                "口味标签没有明显的交集。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledTags(label: String, tags: List<String>, tone: TagPillTone = TagPillTone.Secondary) {
    if (tags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TagPillRow(tags, tone = tone)
    }
}

/** 两人都放进代表作的作品：最大分歧排在前面并单独标出来。 */
@Composable
internal fun CommonWorksCard(state: CompatibilityUiState.Ready, onOpenWork: (String) -> Unit) {
    val common = state.result.commonWorks
    Section("都放进代表作的 ${common.size} 部") {
        common.forEachIndexed { index, (id, mine, theirs) ->
            if (index > 0) FlatDivider()
            val info = state.works[id]
            val diff = abs(mine - theirs)
            val badge = when {
                diff >= BIG_DISAGREEMENT -> "分歧"
                diff == 0 -> "默契"
                else -> null
            }
            WorkRow(
                title = info?.title ?: "条目 $id",
                coverUrl = info?.coverUrl,
                supporting = "我 $mine 分 · ${state.otherName} $theirs 分",
                badge = badge,
            ) { onOpenWork(id.toString()) }
        }
    }
}

@Composable
internal fun PicksCard(otherName: String, picks: List<RepresentativeItem>, onOpenWork: (String) -> Unit) {
    Section("${otherName}推荐给你") {
        picks.forEachIndexed { index, item ->
            if (index > 0) FlatDivider()
            WorkRow(item.title ?: "条目 ${item.work.subjectId}", item.coverUrl, "${otherName}打了 ${item.work.rating} 分") {
                onOpenWork(item.work.subjectId.toString())
            }
        }
    }
}

@Composable
private fun WorkRow(title: String, coverUrl: String?, supporting: String, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(PrismCorner.Badge).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 40.dp, height = 54.dp).clip(PrismCorner.Thumb).background(Surfaces.raisedColor)) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (badge == "分歧") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 分数条：标签 + 横条 + 百分数。 */
@Composable
internal fun ScoreBar(label: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(BAR_LABEL_WEIGHT))
        Box(Modifier.weight(1f).height(6.dp).clip(PrismCorner.Badge).background(Surfaces.raisedColor)) {
            Box(Modifier.fillMaxWidth(value).height(6.dp).background(MaterialTheme.colorScheme.primary))
        }
        Text("${(value * PERCENT).toInt()}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private const val BIG_DISAGREEMENT = 2
private const val BAR_LABEL_WEIGHT = 0.9f
private const val PERCENT = 100

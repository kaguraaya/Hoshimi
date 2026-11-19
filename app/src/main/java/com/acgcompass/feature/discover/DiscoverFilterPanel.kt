package com.acgcompass.feature.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatFilterChip

/**
 * 发现 → 筛选的展开面板：每组一行小标题 + 一行可横向滑动的选项药丸（和「为你排行」的筛选面板同一种写法）。
 * 面板跟着列表一起滚，自己不再套一层滚动。
 */
@Composable
internal fun DiscoverFilterPanel(
    filter: DiscoverFilter,
    facets: FilterFacets,
    onFilterChange: (DiscoverFilter) -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TypeGenreGroups(filter, facets, onFilterChange)
        StatusLengthRatingGroups(filter, facets, onFilterChange)
        YearFinishedSourceGroups(filter, facets, onFilterChange)
        TagGroups(filter, facets, onFilterChange)
        if (!filter.isEmpty) {
            TextButton(onClick = onClearFilter) {
                Text("清除全部筛选")
            }
        }
    }
}

/** 类型和题材。 */
@Composable
private fun TypeGenreGroups(filter: DiscoverFilter, facets: FilterFacets, onFilterChange: (DiscoverFilter) -> Unit) {
    FilterGroup(title = "类型") {
        // 发现页以动画为主，不提供音乐、真人和未知类型筛选。
        com.acgcompass.domain.model.MediaType.entries
            .filter { it != com.acgcompass.domain.model.MediaType.OTHER }
            .forEach { type ->
                ToggleChip(
                    label = type.filterLabel(),
                    selected = type in filter.types,
                    onToggle = {
                        onFilterChange(filter.copy(types = filter.types.toggle(type)))
                    },
                )
            }
    }
    if (facets.genres.isNotEmpty()) {
        FilterGroup(title = "题材") {
            facets.genres.forEach { genre ->
                ToggleChip(
                    label = genre,
                    selected = genre in filter.genres,
                    onToggle = {
                        onFilterChange(filter.copy(genres = filter.genres.toggle(genre)))
                    },
                )
            }
        }
    }
}

/** 状态、篇幅和最低评分。 */
@Composable
private fun StatusLengthRatingGroups(
    filter: DiscoverFilter,
    facets: FilterFacets,
    onFilterChange: (DiscoverFilter) -> Unit,
) {
    FilterGroup(title = "状态") {
        com.acgcompass.domain.model.ReleaseStatus.entries.forEach { status ->
            ToggleChip(
                label = status.filterLabel(),
                selected = status in filter.statuses,
                onToggle = {
                    onFilterChange(filter.copy(statuses = filter.statuses.toggle(status)))
                },
            )
        }
    }
    if (facets.lengths.isNotEmpty()) {
        FilterGroup(title = "篇幅") {
            facets.lengths.forEach { length ->
                ToggleChip(
                    label = length,
                    selected = length in filter.lengths,
                    onToggle = {
                        onFilterChange(filter.copy(lengths = filter.lengths.toggle(length)))
                    },
                )
            }
        }
    }
    FilterGroup(title = "评分（不低于）") {
        RATING_THRESHOLDS.forEach { threshold ->
            ToggleChip(
                label = formatScore(threshold),
                selected = filter.minRating == threshold,
                onToggle = {
                    val next = if (filter.minRating == threshold) null else threshold
                    onFilterChange(filter.copy(minRating = next))
                },
            )
        }
    }
}

/** 年份、完结状态和来源平台。 */
@Composable
private fun YearFinishedSourceGroups(
    filter: DiscoverFilter,
    facets: FilterFacets,
    onFilterChange: (DiscoverFilter) -> Unit,
) {
    if (facets.years.isNotEmpty()) {
        FilterGroup(title = "年份") {
            facets.years.forEach { year ->
                ToggleChip(
                    label = year.toString(),
                    selected = year in filter.years,
                    onToggle = {
                        onFilterChange(filter.copy(years = filter.years.toggle(year)))
                    },
                )
            }
        }
    }
    FilterGroup(title = "完结状态") {
        FinishedFilter.entries.forEach { option ->
            ToggleChip(
                label = option.filterLabel(),
                selected = filter.finished == option,
                onToggle = { onFilterChange(filter.copy(finished = option)) },
            )
        }
    }
    FilterGroup(title = "来源平台") {
        com.acgcompass.domain.model.SourceId.entries.forEach { source ->
            ToggleChip(
                label = source.discoverLabel(),
                selected = source in filter.sources,
                onToggle = {
                    onFilterChange(filter.copy(sources = filter.sources.toggle(source)))
                },
            )
        }
    }
}

/** 风险标签和心情标签。 */
@Composable
private fun TagGroups(filter: DiscoverFilter, facets: FilterFacets, onFilterChange: (DiscoverFilter) -> Unit) {
    if (facets.riskTags.isNotEmpty()) {
        FilterGroup(title = "风险标签") {
            facets.riskTags.forEach { tag ->
                ToggleChip(
                    label = tag,
                    selected = tag in filter.riskTags,
                    onToggle = {
                        onFilterChange(filter.copy(riskTags = filter.riskTags.toggle(tag)))
                    },
                )
            }
        }
    }
    if (facets.moodTags.isNotEmpty()) {
        FilterGroup(title = "心情标签") {
            facets.moodTags.forEach { tag ->
                ToggleChip(
                    label = tag,
                    selected = tag in filter.moodTags,
                    onToggle = {
                        onFilterChange(filter.copy(moodTags = filter.moodTags.toggle(tag)))
                    },
                )
            }
        }
    }
}

@Composable
internal fun FilterGroup(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun ToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    FlatFilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
    )
}

/** 「评分（不低于）」的几个档位（10 分制）。 */
@Suppress("MagicNumber") // 评分档位
private val RATING_THRESHOLDS = listOf(6f, 7f, 8f, 9f)

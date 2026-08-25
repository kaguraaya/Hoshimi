package com.acgcompass.feature.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.FlatCard
import com.acgcompass.core.designsystem.FlatDefaults
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillRow
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportItemStatus

/** 批量导入路由入口。 */
@Composable
fun ImportRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    ImportScreen(
        state = state,
        items = items,
        onBack = onBack,
        onTextChange = viewModel::onTextChange,
        onParse = viewModel::onParseAndMatch,
        onAddToBacklog = viewModel::onAddToBacklog,
        modifier = modifier,
    )
}

/** 无状态批量导入界面：粘贴 → 拆分预览 → 匹配 → 识别结果 → 一键加入待补池。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportUiState,
    items: List<ImportItem>,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onParse: () -> Unit,
    onAddToBacklog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("批量导入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                // 标题栏下方额外 8dp 呼吸（共约 24dp），底部额外留白避免末项贴边。
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlatCard(
                title = "粘贴清单",
                icon = Icons.Outlined.ContentPaste,
                supporting = "支持书名号 / 顿号 / 逗号 / 换行 / 编号列表",
            ) {
                // 无描边的色块输入框：与页面其余部分一致，不用线框。
                FlatTextField(
                    value = state.pastedText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    placeholder = { Text("例如：《孤独摇滚》、葬送的芙莉莲\n1. 链锯人") },
                )
                if (state.previewTitles.isNotEmpty()) {
                    Text(
                        "将拆分为 ${state.previewTitles.size} 部",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TagPillRow(state.previewTitles.take(PREVIEW_LIMIT), tone = TagPillTone.Neutral)
                    if (state.previewTitles.size > PREVIEW_LIMIT) {
                        Text(
                            "…以及另外 ${state.previewTitles.size - PREVIEW_LIMIT} 部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(
                onClick = onParse,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = state.pastedText.isNotBlank() && !state.processing,
            ) {
                if (state.processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.processing) "正在匹配…" else "解析并匹配", maxLines = 1)
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (items.isNotEmpty()) {
                val matched = items.count { it.status == ImportItemStatus.MATCHED }
                FlatCard(
                    title = "识别结果",
                    icon = Icons.Outlined.Checklist,
                    supporting = "共 ${items.size} 条，可加入 $matched 条",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { item -> ImportItemRow(item) }
                    }
                }
                Button(
                    onClick = onAddToBacklog,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    enabled = !state.processing && matched > 0,
                ) { Text("一键加入待补池", maxLines = 1) }
            }
        }
    }
}

private const val PREVIEW_LIMIT = 24

/** 单条识别结果：标题 + 状态色块（已匹配为主色，其余为中性色）。 */
@Composable
private fun ImportItemRow(item: ImportItem) {
    val (statusText, tone) = when (item.status) {
        ImportItemStatus.MATCHED -> "已匹配" to TagPillTone.Primary
        ImportItemStatus.NEEDS_CONFIRMATION -> "需确认" to TagPillTone.Tertiary
        ImportItemStatus.UNMATCHED -> "未匹配" to TagPillTone.Neutral
        ImportItemStatus.ADDED -> "已加入" to TagPillTone.Secondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(FlatDefaults.tileColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.parsedTitle ?: item.rawText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.status == ImportItemStatus.NEEDS_CONFIRMATION) {
                Text(
                    "匹配把握不大，可在搜索页手动确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TagPill(text = statusText, tone = tone)
    }
}

package com.acgcompass.feature.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Text(
                "粘贴推荐清单（支持书名号 / 顿号 / 逗号 / 换行 / 编号列表）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.pastedText,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                placeholder = { Text("例如：《孤独摇滚》、葬送的芙莉莲\n1. 链锯人") },
            )

            if (state.previewTitles.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("拆分预览（${state.previewTitles.size}）", fontWeight = FontWeight.Bold)
                        Text(
                            state.previewTitles.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onParse,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.pastedText.isNotBlank() && !state.processing,
            ) { Text("解析并匹配", maxLines = 1) }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            if (items.isNotEmpty()) {
                Text("识别结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        items.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            ImportItemRow(item)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onAddToBacklog,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.processing && items.any { it.status == ImportItemStatus.MATCHED },
                ) { Text("一键加入待补池", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ImportItemRow(item: ImportItem) {
    val statusText = when (item.status) {
        ImportItemStatus.MATCHED -> "已匹配"
        ImportItemStatus.NEEDS_CONFIRMATION -> "需确认（低置信，可在搜索页手动匹配）"
        ImportItemStatus.UNMATCHED -> "未匹配"
        ImportItemStatus.ADDED -> "已加入"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.parsedTitle ?: item.rawText, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

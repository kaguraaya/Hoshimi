package com.acgcompass.feature.taste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.taste.ManualTagReview
import com.acgcompass.domain.taste.TagClassificationController
import com.acgcompass.domain.taste.TasteCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagManualReviewUiState(
    val items: List<ManualTagReview> = emptyList(),
    val selected: Set<String> = emptySet(),
    val category: TasteCategory = TasteCategory.TOPIC,
    val saving: Boolean = false,
)

@HiltViewModel
class TagManualReviewViewModel @Inject constructor(
    private val controller: TagClassificationController,
) : ViewModel() {
    private val _state = MutableStateFlow(TagManualReviewUiState())
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun toggle(tag: String) {
        _state.update { old ->
            old.copy(selected = if (tag in old.selected) old.selected - tag else old.selected + tag)
        }
    }

    fun selectCategory(category: TasteCategory) {
        _state.update { it.copy(category = category) }
    }

    fun applySelection() {
        val snapshot = _state.value
        if (snapshot.selected.isEmpty() || snapshot.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            controller.resolveManual(snapshot.selected, snapshot.category)
            reload()
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val items = runCatching { controller.manualReviewItems() }.getOrDefault(emptyList())
            _state.update { it.copy(items = items, selected = emptySet(), saving = false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun TagManualReviewRoute(onBack: () -> Unit, viewModel: TagManualReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AcgScreenScaffold(
        title = "标签人工复核",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = ScreenContentPadding.top(padding))
                .padding(horizontal = ScreenContentPadding.Horizontal),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "这些标签的 AI 置信度不足。选中标签并指定一个维度后，将以 MANUAL 来源保存，后续不会被自动覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TasteCategory.SINGLE_TAG_CATEGORIES.forEach { category ->
                    FlatFilterChip(
                        selected = state.category == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(category.label) },
                    )
                }
            }
            Button(
                onClick = viewModel::applySelection,
                enabled = state.selected.isNotEmpty() && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "正在保存…" else "应用到已选标签（${state.selected.size}）")
            }
            if (state.items.isEmpty()) {
                Text("没有待人工复核的标签")
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.items, key = { it.tag }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.tag in state.selected,
                                onCheckedChange = { viewModel.toggle(item.tag) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.tag)
                                Text(
                                    "建议：${item.proposedCategory?.label ?: "未确定"} · " +
                                        "置信度 ${item.confidence?.let { "%.2f".format(it) } ?: "未知"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

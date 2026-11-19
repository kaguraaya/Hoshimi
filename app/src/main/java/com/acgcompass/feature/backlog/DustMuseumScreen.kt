package com.acgcompass.feature.backlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/**
 * 吃灰馆独立页面。展示已移入吃灰馆（待补池吃灰区）的作品，
 * 可点击进详情，可逐条「移出吃灰馆」回到待补池非吃灰区。复用 [BacklogViewModel] 的吃灰流。
 */
@Composable
fun DustMuseumRoute(
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BacklogViewModel = hiltViewModel(),
) {
    val state by viewModel.dustMuseumCards.collectAsStateWithLifecycle()
    DustMuseumScreen(
        state = state,
        onOpenWork = onOpenWork,
        onRestore = viewModel::onRestoreFromDust,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DustMuseumScreen(
    state: UiState<List<BacklogCardItem>>,
    onOpenWork: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AcgScreenScaffold(
        title = "吃灰馆",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { _ ->
        StateScaffold(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) { cards ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenContentPadding.Horizontal,
                    end = ScreenContentPadding.Horizontal,
                    top = ScreenContentPadding.Top,
                    bottom = ScreenContentPadding.Bottom,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.workId }) { card ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkCard(
                            model = card.toWorkCardUiModel(),
                            onClick = { onOpenWork(card.workId) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = { onRestore(card.workId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("移出吃灰馆")
                        }
                    }
                }
            }
        }
    }
}

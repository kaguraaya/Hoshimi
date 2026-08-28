package com.acgcompass.feature.visualnovel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.domain.repository.VisualNovelRepository
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VndbProducerUiState(
    val producerId: String,
    val producerName: String? = null,
    val items: List<VisualNovelListItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VndbProducerViewModel @Inject constructor(
    private val repository: VisualNovelRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val producerId = savedStateHandle.get<String>("producerId").orEmpty()
    private val producerName = savedStateHandle.get<String>("producerName")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(VndbProducerUiState(producerId, producerName))
    val state: StateFlow<VndbProducerUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val current = _state.value
        if (shouldSkipLoad(current)) return
        viewModelScope.launch {
            val page = _state.value.page + 1
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.producerWorks(producerId, page)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        items = (it.items + result.data.items).distinctBy { item -> item.work.id },
                        page = page,
                        hasMore = result.data.hasMore,
                        loading = false,
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.nextStep ?: "厂商作品加载失败")
                }
            }
        }
    }

    fun retry() {
        if (_state.value.error != null) load()
    }

    private fun shouldSkipLoad(current: VndbProducerUiState): Boolean =
        producerId.isBlank() || current.loading || (current.page > 0 && !current.hasMore)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VndbProducerRoute(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: VndbProducerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size, state.hasMore, state.loading, state.error) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && lastVisible >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (!nearEnd || !state.hasMore) return@collect
                if (!state.loading && state.error == null) viewModel.load()
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("厂商作品 · ${state.producerName ?: state.producerId}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { message ->
                item { Text(message, modifier = Modifier.padding(16.dp)) }
            }
            items(state.items, key = { it.work.id }) { item ->
                ProducerWorkCard(item, onClick = { onOpenWork(item.work.id) })
            }
            if (state.loading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }
            } else if (state.error != null) {
                item {
                    Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProducerWorkCard(item: VisualNovelListItem, onClick: () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.previewUrl ?: item.work.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 64.dp, height = 88.dp),
                )
                Column {
                    Text(item.work.titles.canonical)
                    Text(item.rating?.let { "VNDB %.1f/100".format(it) } ?: "暂无评分")
                }
            }
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 5

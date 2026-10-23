package com.acgcompass.feature.backlog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.LocalFloatingBarInset
import com.acgcompass.core.ui.LocalHeaderInset
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarBlurSource
import com.acgcompass.core.ui.withoutTop
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import dev.chrisbanes.haze.HazeState

/**
 * 待补池路由入口。连接 [BacklogViewModel] 并把
 * 状态与回调下发给无状态的 [BacklogScreen]。
 */
@Composable
fun BacklogRoute(
    modifier: Modifier = Modifier,
    onOpenDetail: (String) -> Unit = {},
    onGoImport: () -> Unit = {},
    onGoSearch: () -> Unit = {},
    onOpenDustMuseum: () -> Unit = {},
    viewModel: BacklogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val drawResult by viewModel.drawResult.collectAsStateWithLifecycle()
    val gridMode by viewModel.gridMode.collectAsStateWithLifecycle()

    BacklogScreen(
        state = uiState,
        filter = filter,
        sort = sort,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        drawResult = drawResult,
        gridMode = gridMode,
        onToggleGridMode = viewModel::onToggleGridMode,
        onSortSelected = viewModel::onSortSelected,
        onTogglePriorityFilter = viewModel::onTogglePriorityFilter,
        onToggleMediaTypeFilter = viewModel::onToggleMediaTypeFilter,
        onToggleDustMuseumFilter = viewModel::onToggleDustMuseumFilter,
        onClearFilters = viewModel::onClearFilters,
        onSetPriority = viewModel::onSetPriority,
        onSetNote = viewModel::onSetNote,
        onToggleSelectionMode = viewModel::onToggleSelectionMode,
        onToggleSelect = viewModel::onToggleSelect,
        onBulk = viewModel::onBulk,
        onDraw = viewModel::onDraw,
        onDismissDraw = viewModel::onDismissDraw,
        onOpenDetail = onOpenDetail,
        onGoImport = onGoImport,
        onGoSearch = onGoSearch,
        onOpenDustMuseum = onOpenDustMuseum,
        modifier = modifier,
    )
}

/**
 * 无状态待补池界面（Material 3）。展示全部待补条目的统一作品卡片，附带筛选 / 排序控件、
 * 优先级 / 备注编辑、多选批量操作与一键抽番。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklogScreen(
    state: UiState<List<BacklogCardItem>>,
    filter: BacklogFilter,
    sort: BacklogSort,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    drawResult: DrawUiResult?,
    gridMode: Boolean = false,
    onToggleGridMode: () -> Unit = {},
    onSortSelected: (BacklogSort) -> Unit,
    onTogglePriorityFilter: (Priority) -> Unit,
    onToggleMediaTypeFilter: (MediaType) -> Unit,
    onToggleDustMuseumFilter: (Boolean?) -> Unit,
    onClearFilters: () -> Unit,
    onSetPriority: (String, Priority) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onToggleSelectionMode: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onBulk: (BulkOp) -> Unit,
    onDraw: () -> Unit,
    onDismissDraw: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onGoImport: () -> Unit = {},
    onGoSearch: () -> Unit = {},
    onOpenDustMuseum: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 多选态下拦截系统返回（手势 / 按键）——先退出多选并清空已选，而非直接退回首页，符合原生多选交互直觉。
    BackHandler(enabled = selectionMode) { onToggleSelectionMode() }
    val isEmpty = state is UiState.Empty ||
        (state is UiState.Success && state.data.isEmpty())
    val hasActiveFilter = filter.priorities.isNotEmpty() ||
        filter.mediaTypes.isNotEmpty() ||
        // inDustMuseum=false 是待补池主列表默认基线（不含吃灰），不计为「已激活筛选」；
        // 仅当切到「全部」(null) 或「仅吃灰」(true) 才算激活，便于「清除筛选」回到默认。
        filter.inDustMuseum != false
    // 列表 / 网格排版切换（网格=3 列只显示封面）；形态由 DataStore 持久化记忆。
    val hazeState = remember { HazeState() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            AcgTopLevelTopBar(
                title = if (selectionMode) "已选 ${selectedIds.size} 项" else "待补池",
                hazeState = hazeState,
                actions = {
                    // 多选仅在有内容时有意义。
                    if (!isEmpty) {
                        if (!selectionMode) {
                            IconButton(onClick = onOpenDustMuseum) {
                                Icon(Icons.Outlined.Inventory2, contentDescription = "吃灰馆")
                            }
                            IconButton(onClick = onToggleGridMode) {
                                Icon(
                                    imageVector = if (gridMode) {
                                        Icons.AutoMirrored.Outlined.ViewList
                                    } else {
                                        Icons.Outlined.GridView
                                    },
                                    contentDescription = if (gridMode) "切换为列表" else "切换为网格",
                                )
                            }
                        }
                        IconButton(onClick = onToggleSelectionMode) {
                            Icon(
                                imageVector = if (selectionMode) Icons.Outlined.Done else Icons.Outlined.Checklist,
                                contentDescription = if (selectionMode) "完成多选" else "多选",
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {},
    ) { innerPadding ->
        // 列表铺到毛玻璃顶栏下面滚过；顶部空间由列表留白（LocalHeaderInset）和空状态各自让出。
        val topInset = innerPadding.calculateTopPadding() + 8.dp
        Box(Modifier.fillMaxSize().topBarBlurSource(hazeState)) {
            CompositionLocalProvider(LocalHeaderInset provides topInset) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding.withoutTop()),
                ) {
                    when {
                        isEmpty && hasActiveFilter -> {
                            // 待补池有作品但当前筛选无结果——保留筛选栏，显示「无匹配」+清除筛选，不当成空池。
                            FilterCard(
                                filter = filter,
                                sort = sort,
                                onSortSelected = onSortSelected,
                                onTogglePriorityFilter = onTogglePriorityFilter,
                                onToggleMediaTypeFilter = onToggleMediaTypeFilter,
                                onToggleDustMuseumFilter = onToggleDustMuseumFilter,
                                onClearFilters = onClearFilters,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(top = topInset),
                            )
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    "没有符合当前筛选条件的作品",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Button(
                                    onClick = onClearFilters,
                                    modifier = Modifier.padding(top = 12.dp),
                                ) { Text("清除筛选", maxLines = 1) }
                            }
                        }
                        isEmpty -> {
                            // 空池友好引导卡（导入 / 搜索添加）。
                            Box(Modifier.padding(top = topInset)) {
                                BacklogEmptyState(
                                    onGoImport = onGoImport,
                                    onGoSearch = onGoSearch,
                                )
                            }
                        }
                        selectionMode -> {
                            // 批量操作栏作为列表 header 随内容一起滚动。
                            BacklogList(
                                state = state,
                                selectionMode = selectionMode,
                                selectedIds = selectedIds,
                                gridMode = gridMode,
                                onSetPriority = onSetPriority,
                                onSetNote = onSetNote,
                                onToggleSelect = onToggleSelect,
                                onOpenDetail = onOpenDetail,
                                modifier = Modifier.weight(1f),
                                header = {
                                    BulkActionBar(
                                        selectedCount = selectedIds.size,
                                        onBulk = onBulk,
                                    )
                                },
                            )
                        }
                        else -> {
                            // 一键抽番按钮 + 筛选/排序卡作为列表 header 随内容一起滚动。
                            BacklogList(
                                state = state,
                                selectionMode = selectionMode,
                                selectedIds = selectedIds,
                                gridMode = gridMode,
                                onSetPriority = onSetPriority,
                                onSetNote = onSetNote,
                                onToggleSelect = onToggleSelect,
                                onOpenDetail = onOpenDetail,
                                modifier = Modifier.weight(1f),
                                header = {
                                    // 一键抽番移入内容区操作行，避免悬浮 FAB 遮挡卡片「查看详情」按钮。
                                    Button(
                                        onClick = onDraw,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                    ) { Text("一键抽番", maxLines = 1) }
                                    FilterCard(
                                        filter = filter,
                                        sort = sort,
                                        onSortSelected = onSortSelected,
                                        onTogglePriorityFilter = onTogglePriorityFilter,
                                        onToggleMediaTypeFilter = onToggleMediaTypeFilter,
                                        onToggleDustMuseumFilter = onToggleDustMuseumFilter,
                                        onClearFilters = onClearFilters,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (drawResult != null) {
        DrawResultDialog(
            result = drawResult,
            onDismiss = onDismissDraw,
            onOpenDetail = onOpenDetail,
        )
    }
}

/**
 * 列表区渲染待补卡片，多选模式与普通模式共用；普通模式支持列表和网格切换。
 * 可选 [header] 作为 lazy 容器首个 item 随内容一起滚动（一键抽番 / 筛选排序 / 批量操作栏），
 * 不再固定在页面顶部；网格模式下 header 占满整行。
 */
@Composable
private fun BacklogList(
    state: UiState<List<BacklogCardItem>>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onSetPriority: (String, Priority) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onToggleSelect: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    gridMode: Boolean = false,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    StateScaffold(
        state = state,
        modifier = modifier.fillMaxSize(),
    ) { cards ->
        if (gridMode) {
            // 网格排版——3 列，仅显示封面 + 标题，省空间。
            // 多选态保持网格（不再强制切列表）——BacklogGridItem 已支持多选勾选遮罩。
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                // 外沿 16dp、列间距 12dp；header 全行项也内缩 16dp（与卡片对齐）。
                contentPadding = backlogListPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (header != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.fillMaxWidth()) { header() }
                    }
                }
                gridItems(cards, key = { it.workId }) { card ->
                    BacklogGridItem(
                        card = card,
                        onOpenDetail = onOpenDetail,
                        selectionMode = selectionMode,
                        selected = card.workId in selectedIds,
                        onToggleSelect = onToggleSelect,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 外沿 16dp；底部 96dp 保证末项不被底栏/手势条遮挡。header 与卡片同宽对齐。
                contentPadding = backlogListPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (header != null) {
                    item { Column(modifier = Modifier.fillMaxWidth()) { header() } }
                }
                items(cards, key = { it.workId }) { card ->
                    BacklogListItem(
                        card = card,
                        selectionMode = selectionMode,
                        selected = card.workId in selectedIds,
                        onSetPriority = onSetPriority,
                        onSetNote = onSetNote,
                        onToggleSelect = onToggleSelect,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }
    }
}

/** 网格单元包含 2:3 封面和两行标题；多选模式下点击切换选中状态，否则进入详情。 */
@Composable
private fun BacklogGridItem(
    card: BacklogCardItem,
    onOpenDetail: (String) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
) {
    val model = card.toWorkCardUiModel()
    Column(
        modifier = Modifier.clickable {
            if (selectionMode) onToggleSelect(card.workId) else onOpenDetail(card.workId)
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(PrismCorner.Poster)
                .background(Surfaces.raisedColor),
            contentAlignment = Alignment.Center,
        ) {
            if (model.coverUrl != null) {
                // 裁切铺满。
                AsyncImage(
                    model = model.coverUrl,
                    contentDescription = model.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "暂无封面",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 多选态——选中加主色半透明遮罩，右上角勾选图标（实心=选中 / 空心=未选）。
            if (selectionMode) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    )
                }
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(50),
                        ),
                )
            }
        }
        Text(
            text = model.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 空待补池引导卡。明确「下一步」：去导入补番清单 / 去搜索添加。
 * 一键抽番在空池时不出现，避免无候选时的孤立悬浮按钮。
 */
@Composable
private fun BacklogEmptyState(onGoImport: () -> Unit, onGoSearch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PrismCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "待补池还是空的",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "收纳想看的作品，支持筛选、排序、批量管理和随机抽取。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onGoImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("批量导入补番清单", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onGoSearch,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("搜索添加", maxLines = 1)
                }
            }
        }
    }
}

/** 待补池列表的内边距：底部让出悬浮底栏。 */
@Composable
private fun backlogListPadding(): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    // 顶栏加高到 72dp 后仍保持原来离屏幕顶端的距离（待补池自己的间距，不跟其它页面联动）。
    top = LocalHeaderInset.current - 4.dp,
    bottom = 96.dp + LocalFloatingBarInset.current,
)

/** 批量操作工具栏。空选择时操作为无效（仓库侧忽略）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkActionBar(selectedCount: Int, onBulk: (BulkOp) -> Unit) {
    PrismCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (selectedCount == 0) "请选择要批量操作的作品" else "对选中的 $selectedCount 项执行：",
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BulkOp.entries.forEach { op ->
                    FlatAssistChip(
                        onClick = { onBulk(op) },
                        label = { Text(op.label()) },
                    )
                }
            }
        }
    }
}

/** 一键抽番结果弹窗：展示抽中作品与可解释理由（非纯随机）。 */
@Composable
private fun DrawResultDialog(result: DrawUiResult, onDismiss: () -> Unit, onOpenDetail: (String) -> Unit) {
    val pickId = result.workId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pickId != null) "今晚就看它" else "抽番结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickId != null) {
                    // 显示作品名（缺失才回退 id）。
                    Text(result.title ?: pickId, style = MaterialTheme.typography.titleMedium)
                }
                Text(result.reason, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (pickId != null) {
                TextButton(onClick = {
                    onDismiss()
                    onOpenDetail(pickId)
                }) { Text("查看详情") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = if (pickId != null) {
            { TextButton(onClick = onDismiss) { Text("关闭") } }
        } else {
            null
        },
    )
}

/**
 * 筛选 / 排序卡片。把折叠开关做成清晰可见的卡片头（标题 + 「展开/收起」按钮 + 已选数量提示），
 * 展开后内嵌 [FilterSortBar]。比之前弱化的纯文字行更有主体感。
 */
@Composable
private fun FilterCard(
    filter: BacklogFilter,
    sort: BacklogSort,
    onSortSelected: (BacklogSort) -> Unit,
    onTogglePriorityFilter: (Priority) -> Unit,
    onToggleMediaTypeFilter: (MediaType) -> Unit,
    onToggleDustMuseumFilter: (Boolean?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val activeCount = filter.priorities.size + filter.mediaTypes.size +
        // 默认基线 inDustMuseum=false 不计入激活筛选数。
        (if (filter.inDustMuseum != false) 1 else 0)
    PrismCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (activeCount > 0) "筛选 / 排序 · 已选 $activeCount" else "筛选 / 排序",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Text(if (filtersExpanded) "收起" else "展开")
                }
            }
            if (filtersExpanded) {
                FilterSortBar(
                    filter = filter,
                    sort = sort,
                    onSortSelected = onSortSelected,
                    onTogglePriorityFilter = onTogglePriorityFilter,
                    onToggleMediaTypeFilter = onToggleMediaTypeFilter,
                    onToggleDustMuseumFilter = onToggleDustMuseumFilter,
                    onClearFilters = onClearFilters,
                )
            }
        }
    }
}

/** 筛选与排序控件区：类型 / 优先级 / 完结(吃灰) 过滤 + 排序下拉。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSortBar(
    filter: BacklogFilter,
    sort: BacklogSort,
    onSortSelected: (BacklogSort) -> Unit,
    onTogglePriorityFilter: (Priority) -> Unit,
    onToggleMediaTypeFilter: (MediaType) -> Unit,
    onToggleDustMuseumFilter: (Boolean?) -> Unit,
    onClearFilters: () -> Unit,
) {
    // 展开后的筛选 / 排序内容较多，给板块限高 + 竖向滚动，避免撑高卡片挤压下方列表区。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            // 手指落在面板里就只滚面板：滚到头之后的余量在这里吃掉，不传给外层页面继续滚。
            .nestedScroll(remember { ConsumeLeftoverScrollConnection })
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 排序与优先级一样用药丸单选。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("排序", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClearFilters) { Text("清除筛选") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BacklogSort.entries.forEach { option ->
                FlatFilterChip(
                    selected = option == sort,
                    onClick = { onSortSelected(option) },
                    label = { Text(option.label()) },
                )
            }
        }

        // 优先级过滤。
        Text("优先级", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { priority ->
                FlatFilterChip(
                    selected = priority in filter.priorities,
                    onClick = { onTogglePriorityFilter(priority) },
                    label = { Text(priority.label()) },
                )
            }
        }

        // 媒介类型过滤。
        Text("类型", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 音乐、真人和未知类型不作为待补池的可筛选媒介。
            MediaType.entries.filter { it != MediaType.OTHER }.forEach { mediaType ->
                FlatFilterChip(
                    selected = mediaType in filter.mediaTypes,
                    onClick = { onToggleMediaTypeFilter(mediaType) },
                    label = { Text(mediaType.label()) },
                )
            }
        }

        // 完结状态 / 吃灰过滤。
        Text("吃灰状态", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatFilterChip(
                selected = filter.inDustMuseum == null,
                onClick = { onToggleDustMuseumFilter(null) },
                label = { Text("全部") },
            )
            FlatFilterChip(
                selected = filter.inDustMuseum == true,
                onClick = { onToggleDustMuseumFilter(true) },
                label = { Text("仅吃灰") },
            )
            FlatFilterChip(
                selected = filter.inDustMuseum == false,
                onClick = { onToggleDustMuseumFilter(false) },
                label = { Text("仅非吃灰") },
            )
        }
    }
}

/**
 * 单条待补条目：多选模式下显示复选框；否则展示统一作品卡片 + 可展开的优先级 / 备注编辑区
 * 。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklogListItem(
    card: BacklogCardItem,
    selectionMode: Boolean,
    selected: Boolean,
    onSetPriority: (String, Priority) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onToggleSelect: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var expanded by rememberSaveable(card.workId) { mutableStateOf(false) }
    if (selectionMode) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect(card.workId) },
            )
            WorkCard(
                model = card.toWorkCardUiModel(),
                onClick = { onToggleSelect(card.workId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkCard(
            model = card.toWorkCardUiModel(),
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            EditPanel(
                card = card,
                onSetPriority = onSetPriority,
                onSetNote = onSetNote,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

/** 优先级与备注编辑面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditPanel(
    card: BacklogCardItem,
    onSetPriority: (String, Priority) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("优先级", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Priority.entries.forEach { priority ->
                    FlatFilterChip(
                        selected = card.item.priority == priority,
                        onClick = { onSetPriority(card.workId, priority) },
                        label = { Text(priority.label()) },
                    )
                }
            }

            Text("自定义备注", style = MaterialTheme.typography.labelMedium)
            var noteDraft by rememberSaveable(card.workId) {
                mutableStateOf(card.item.note.orEmpty())
            }
            FlatTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("写点想看的理由 / 提醒…") },
                maxLines = 3,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { onSetNote(card.workId, noteDraft) }) {
                    Text("保存备注")
                }
                TextButton(onClick = {
                    noteDraft = ""
                    onSetNote(card.workId, null)
                }) {
                    Text("清除")
                }
                TextButton(onClick = { onOpenDetail(card.workId) }) {
                    Text("查看详情")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BacklogScreenEmptyPreview() {
    AcgCompassTheme {
        BacklogScreen(
            state = UiState.Empty(com.acgcompass.core.ui.Cta("去导入 / 搜索添加", "import")),
            filter = BacklogFilter.NONE,
            sort = BacklogSort.ADDED_DESC,
            selectionMode = false,
            selectedIds = emptySet(),
            drawResult = null,
            onSortSelected = {},
            onTogglePriorityFilter = {},
            onToggleMediaTypeFilter = {},
            onToggleDustMuseumFilter = {},
            onClearFilters = {},
            onSetPriority = { _, _ -> },
            onSetNote = { _, _ -> },
            onToggleSelectionMode = {},
            onToggleSelect = {},
            onBulk = {},
            onDraw = {},
            onDismissDraw = {},
            onOpenDetail = {},
        )
    }
}

/**
 * 嵌套滚动里「到头即止」：内层滚动容器滚到边界后，把剩余的拖动与惯性全部消费掉，
 * 阻止滚动链把它交给外层容器（否则在小面板里一滑到底，整页就跟着滚）。
 */
private object ConsumeLeftoverScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

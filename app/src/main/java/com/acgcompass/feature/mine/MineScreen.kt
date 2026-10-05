package com.acgcompass.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.IconWell
import com.acgcompass.core.designsystem.NavRow
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.RowGroup
import com.acgcompass.core.designsystem.RowGroupDivider
import com.acgcompass.core.designsystem.SectionHeader
import com.acgcompass.core.designsystem.entryIconFor
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarBlurSource
import com.acgcompass.data.credential.SourceId
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 「暂无数据」统一兜底文案。 */
private const val NO_DATA = "暂无数据"

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * 「我的」页路由入口。连接 [MineViewModel]，
 * 把状态与导航回调下发给无状态的 [MineScreen]。
 *
 * @param onNavigateToSettings 进入设置页入口。
 * @param onOpenTasteProfile 进入口味画像入口。
 */
@Composable
fun MineRoute(
    onNavigateToSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenTasteProfile: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLibrary: (String?) -> Unit = {},
    viewModel: MineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(syncMessage) {
        syncMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearSyncMessage()
        }
    }
    MineScreen(
        state = uiState,
        syncing = syncing,
        onSyncBangumi = viewModel::onSyncBangumi,
        onNavigateToSettings = onNavigateToSettings,
        onOpenPrivacySettings = onOpenPrivacySettings,
        onOpenTasteProfile = onOpenTasteProfile,
        onOpenAbout = onOpenAbout,
        onOpenLibrary = onOpenLibrary,
        modifier = modifier,
    )
}

/**
 * 无状态「我的」界面（Material 3）。展示账号状态、数据统计，
 * 提供口味画像、设置、隐私与关于等入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    state: UiState<MineUiState>,
    onNavigateToSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenTasteProfile: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
    onSyncBangumi: () -> Unit = {},
    onOpenLibrary: (String?) -> Unit = {},
) {
    val hazeState = remember { HazeState() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { AcgTopLevelTopBar(title = "我的", hazeState = hazeState) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().topBarBlurSource(hazeState)) {
            StateScaffold(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { ui ->
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 父级 StateScaffold 已消费标题栏 innerPadding；此处只叠加统一留白（顶部 16dp 呼吸 + 底部 96dp 不被底栏遮挡）。
                    contentPadding = PaddingValues(
                        start = ScreenContentPadding.Horizontal,
                        end = ScreenContentPadding.Horizontal,
                        top = ScreenContentPadding.Top,
                        bottom = ScreenContentPadding.Bottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "accounts") { AccountStatusCard(accounts = ui.accounts) }
                    item(key = "sync") {
                        SyncCard(
                            bangumiConfigured = ui.accounts.firstOrNull {
                                it.sourceId == SourceId.BANGUMI
                            }?.configured == true,
                            syncStatus = ui.syncStatus,
                            syncing = syncing,
                            onSyncBangumi = onSyncBangumi,
                            onOpenLibrary = onOpenLibrary,
                        )
                    }
                    item(key = "stats") { StatisticsCard(stats = ui.stats) }
                    item(key = "actions") {
                        ActionsCard(
                            onOpenTasteProfile = onOpenTasteProfile,
                            onNavigateToSettings = onNavigateToSettings,
                            onOpenPrivacySettings = onOpenPrivacySettings,
                            onOpenAbout = onOpenAbout,
                        )
                    }
                }
            }
        }
    }
}

/** 从 Bangumi 同步个人数据入口卡。已配置才可点击同步；统计与详情/口味/时光机消费同步结果。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncCard(
    bangumiConfigured: Boolean,
    syncStatus: com.acgcompass.data.sync.SyncStatus,
    syncing: Boolean,
    onSyncBangumi: () -> Unit,
    onOpenLibrary: (String?) -> Unit = {},
) {
    SectionCard(title = "个人数据同步") {
        Text(
            if (bangumiConfigured) {
                "从 Bangumi 同步你的收藏 / 评分 / 进度 / 短评，用于统计、详情页「我的记录」、口味画像与时光机。"
            } else {
                "未配置 Bangumi 账号。在本页下方的「设置 → 数据源」登录后即可同步个人收藏与评分。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 展示统一同步状态（来源 / 最后同步时间 / 本地收藏数 / 上次结果 / 错误）。
        if (syncStatus.hasSynced || syncStatus.localCollectionCount > 0 || syncStatus.lastError != null) {
            FlatDivider()
            StatRow("同步来源", syncStatus.currentSource)
            StatRow("最后同步", syncStatus.lastSyncText())
            StatRow("本地收藏数", "${syncStatus.localCollectionCount} 部")
            if (syncStatus.hasSynced) {
                StatRow("上次结果", syncStatus.resultSummary())
            }
            syncStatus.lastError?.let {
                Text(
                    "上次同步出错：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        FilledTonalButton(
            onClick = onSyncBangumi,
            enabled = bangumiConfigured && !syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (syncing) "同步中…" else "从 Bangumi 同步我的数据", maxLines = 1)
        }
        // 同步入库后查看具体作品的入口。说明信息架构，避免「只看到数字」。
        FlatDivider()
        Text(
            "「收藏入库」= 把 Bangumi 收藏 / 评分 / 进度 / 短评同步到本地。" +
                "待补池 = 你准备补的作品；我的库 = 所有同步 / 记录过的作品。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { onOpenLibrary(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("查看我的收藏（全部）", maxLines = 1)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlatAssistChip(onClick = { onOpenLibrary("watching") }, label = { Text("在看") })
            FlatAssistChip(onClick = { onOpenLibrary("watched") }, label = { Text("看过") })
            FlatAssistChip(onClick = { onOpenLibrary("want") }, label = { Text("想看") })
        }
    }
}

/** 账号状态卡片。仅展示非敏感元数据：配置状态与最后测试时间。 */
@Composable
private fun AccountStatusCard(accounts: List<AccountStatusRow>) {
    SectionCard(title = "账号状态") {
        if (accounts.isEmpty()) {
            Text(NO_DATA, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        accounts.forEachIndexed { index, row ->
            if (index > 0) FlatDivider()
            AccountStatusRowItem(row = row)
        }
    }
}

@Composable
private fun AccountStatusRowItem(row: AccountStatusRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWell(entryIconFor(row.label))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(row.label, style = MaterialTheme.typography.titleSmall)
            val testedText = row.lastTestedAt
                ?.let { "最后测试：${TIME_FORMATTER.format(Instant.ofEpochMilli(it))}" }
                ?: "最后测试：从未测试"
            Text(
                text = testedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.statusText,
            style = MaterialTheme.typography.labelLarge,
            color = if (row.configured) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 数据统计卡片。缺失指标显示「暂无数据」，绝不伪造。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatisticsCard(stats: MineStats) {
    SectionCard(title = "数据统计") {
        // 观看状态计数。
        StatRow(label = "看过", value = stats.watched?.toString() ?: NO_DATA)
        StatRow(label = "在看", value = stats.watching?.toString() ?: NO_DATA)
        StatRow(label = "想看", value = stats.wantToWatch?.toString() ?: NO_DATA)
        StatRow(label = "搁置", value = stats.onHold?.toString() ?: NO_DATA)
        StatRow(label = "抛弃", value = stats.dropped?.toString() ?: NO_DATA)

        FlatDivider(modifier = Modifier.padding(vertical = 4.dp))

        // 评分统计。
        StatRow(
            label = "平均评分",
            value = stats.averageRating?.let { "%.1f".format(it) } ?: NO_DATA,
        )
        StatRow(
            label = "最高分",
            value = stats.highestRating?.let { "%.1f".format(it) } ?: NO_DATA,
        )

        FlatDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("常见番剧标签", style = MaterialTheme.typography.titleSmall)
        if (stats.commonTags.isEmpty()) {
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stats.commonTags.forEach { tag ->
                    FlatAssistChip(
                        onClick = {},
                        label = { Text("${tag.name} · ${tag.count}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 入口：口味画像、设置、隐私与数据管理、关于——成组的行，行首圆形图标底，行尾箭头。 */
@Composable
private fun ActionsCard(
    onOpenTasteProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("更多")
        RowGroup {
            NavRow(
                title = "口味画像",
                supporting = "你常看什么、和大众哪里不一样",
                onClick = onOpenTasteProfile,
                leading = { IconWell(Icons.Outlined.Insights) },
            )
            RowGroupDivider()
            NavRow(title = "设置", onClick = onNavigateToSettings, leading = { IconWell(Icons.Outlined.Settings) })
            RowGroupDivider()
            // 隐私控制、导入导出和缓存管理集中在设置页；此入口会自动展开对应区域。
            NavRow(
                title = "隐私与数据",
                supporting = "清除 / 导出 / 导入 / 清理缓存",
                onClick = onOpenPrivacySettings,
                leading = { IconWell(Icons.Outlined.Shield) },
            )
            RowGroupDivider()
            NavRow(title = "关于", onClick = onOpenAbout, leading = { IconWell(Icons.Outlined.Info) })
        }
    }
}

/** 统一区块卡片：标题 + 内容槽。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MineScreenPreview() {
    AcgCompassTheme {
        MineScreen(
            state = UiState.Success(
                MineUiState(
                    accounts = listOf(
                        AccountStatusRow(SourceId.BANGUMI, "Bangumi", true, "连接正常", 1_700_000_000_000L),
                        AccountStatusRow(SourceId.ANILIST, "AniList", false, "未配置", null),
                        AccountStatusRow(SourceId.MAL, "MyAnimeList", false, "未配置", null),
                        AccountStatusRow(SourceId.VNDB, "VNDB", false, "未配置", null),
                        AccountStatusRow(SourceId.AI_PROVIDER, "AI 服务", true, "已配置", null),
                    ),
                    stats = MineStats(
                        wantToWatch = 12,
                        commonTags = listOf(TagCount("治愈", 5), TagCount("致郁", 3)),
                    ),
                ),
            ),
            onNavigateToSettings = {},
            onOpenPrivacySettings = {},
            onOpenTasteProfile = {},
            onOpenAbout = {},
        )
    }
}

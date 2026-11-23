package com.acgcompass.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.designsystem.IconWell
import com.acgcompass.core.designsystem.NavRow
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.entryIconFor
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarScrim
import com.acgcompass.core.ui.transparentTopBarColors
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.domain.model.ThemePreset
import com.acgcompass.domain.taste.TasteMatchBranch
import kotlinx.coroutines.launch

/**
 * 设置页（Settings_Screen）的回调集合。
 *
 * 凭据/开关相关回调路由到 [SettingsViewModel]；数据清除 / 缓存清除 / 导入 / 导出为**导航级**入口，
 * 由宿主（NavGraph）提供具体实现，本页只暴露入口。
 */
data class SettingsActions(
    val onNavigateBack: () -> Unit = {},
    val onOpenDoc: (String) -> Unit = {},
    // 数据源卡片
    val onToggleExpand: (SourceId) -> Unit = {},
    val onFieldChange: (SourceId, CredentialFieldKey, String) -> Unit = { _, _, _ -> },
    val onToggleReveal: (SourceId, CredentialFieldKey) -> Unit = { _, _ -> },
    val onSave: (SourceId) -> Unit = {},
    val onClear: (SourceId) -> Unit = {},
    val onTestConnection: (SourceId) -> Unit = {},
    val onToggleChange: (String, Boolean) -> Unit = { _, _ -> },
    // AI Provider 卡片
    val onToggleAiExpand: () -> Unit = {},
    val onAiProviderSelect: (AiProviderOption) -> Unit = {},
    val onAiFieldChange: (CredentialFieldKey, String) -> Unit = { _, _ -> },
    val onToggleAiReveal: (CredentialFieldKey) -> Unit = {},
    val onAiSave: () -> Unit = {},
    val onAiClear: () -> Unit = {},
    val onAiTestConnection: () -> Unit = {},
    val onSetAiTagClassificationMax: (Int) -> Unit = {},
    // 隐私卡片
    val onTogglePrivacyExpand: () -> Unit = {},
    val onClearLocalData: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onExportCsv: (com.acgcompass.domain.repository.CsvKind) -> Unit = {},
    val onDismissSaveNotice: () -> Unit = {},
    // Bangumi API 地址
    val onUseOfficialBangumiApi: () -> Unit = {},
    val onSelectBangumiCandidate: (String) -> Unit = {},
    val onBangumiCustomUrlChange: (String) -> Unit = {},
    val onSaveBangumiCustomUrl: () -> Unit = {},
    val onBangumiConsentChange: (Boolean) -> Unit = {},
    // 用 Bangumi OAuth 登录（导航到 WebView 授权页）
    val onBangumiLogin: () -> Unit = {},
    // 自动同步间隔（分钟，0=关闭）
    val onSetAutoSyncInterval: (Int) -> Unit = {},
    // 推荐社区均分下限与口味匹配度阈值
    val onSetRecommendMinScore: (Float) -> Unit = {},
    val onSetTasteMatchThreshold: (Float) -> Unit = {},
    val onSetTasteMatchBranch: (String) -> Unit = {},
    val onSetThemeMode: (ThemeMode) -> Unit = {},
    val onSetDynamicColor: (Boolean) -> Unit = {},
    val onSetLightThemePreset: (ThemePreset) -> Unit = {},
    val onSetDarkThemePreset: (ThemePreset) -> Unit = {},
    val onSetPredictiveBackEnabled: (Boolean) -> Unit = {},
    val onOpenCacheManagement: () -> Unit = {},
    val onOpenDataSafety: () -> Unit = {},
    val onOpenBackgroundTasks: () -> Unit = {},
    val onOpenRecommendPreferences: () -> Unit = {},
)

/**
 * 设置页有状态入口：注入 [SettingsViewModel]，收集 [SettingsUiState] 并装配回调。
 *
 * 导航级入口（返回 / 打开文档 / 清除数据 / 缓存 / 导入 / 导出）由调用方传入。
 *
 */
@Suppress("LongParameterList") // 导航级回调都由导航层传入
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
    onClearLocalData: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onOpenCacheManagement: () -> Unit = {},
    onOpenSource: (String) -> Unit = {},
    expandPrivacy: Boolean = false,
    onOpenBackgroundTasks: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 从「隐私与数据管理」深链进入时自动展开隐私区（兼容旧深链；新结构隐私为独立二级页）。
    androidx.compose.runtime.LaunchedEffect(expandPrivacy) {
        if (expandPrivacy) viewModel.expandPrivacy()
    }
    val actions = rememberSettingsActions(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onOpenDoc = onOpenDoc,
        onClearLocalData = onClearLocalData,
        onClearCache = onClearCache,
        onOpenCacheManagement = onOpenCacheManagement,
        onOpenBackgroundTasks = onOpenBackgroundTasks,
    )
    SettingsScreenContent(
        state = state,
        actions = actions,
        onOpenSource = onOpenSource,
        modifier = modifier,
    )
}

/**
 * 设置二级页（Settings_Source_Screen）。
 *
 * 复用主设置页同一套 [SettingsViewModel] 行为与既有卡片 Composable，仅渲染所选数据源的完整编辑器
 * （强制展开），返回后主页面摘要自动刷新（StateFlow 实时驱动）。Bangumi 二级页额外渲染既有的
 * [BangumiApiConfigCard]（官方/社区候选/自定义 + 非官方 Token 风险确认），底层 Token 保护 /
 * 连接测试逻辑保持不变。
 *
 * @param sourceKey 数据源键：[SourceId] 枚举名（含 AI_PROVIDER）或 [PRIVACY_SOURCE_KEY]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // 二级页的跳转回调都由导航层传入
@Composable
fun SettingsSourceScreen(
    sourceKey: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
    onClearLocalData: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onBangumiLogin: () -> Unit = {},
    onOpenCacheManagement: () -> Unit = {},
    onOpenDataSafety: () -> Unit = {},
    onOpenRecommendPreferences: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberSettingsActions(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onOpenDoc = onOpenDoc,
        onClearLocalData = onClearLocalData,
        onClearCache = onClearCache,
        onOpenCacheManagement = onOpenCacheManagement,
        onOpenDataSafety = onOpenDataSafety,
        onBangumiLogin = onBangumiLogin,
        onOpenRecommendPreferences = onOpenRecommendPreferences,
    )

    // 「凭据仅保存在本机」提示：与主页面一致。
    val snackbarHostState = remember { SnackbarHostState() }
    val saveNoticeVisible = (state as? UiState.Success)?.data?.saveNoticeVisible == true
    LaunchedEffect(saveNoticeVisible) {
        if (saveNoticeVisible) {
            snackbarHostState.showSnackbar(SAVE_NOTICE_TEXT)
            actions.onDismissSaveNotice()
        }
    }

    com.acgcompass.core.ui.AcgScreenScaffold(
        title = settingsSourceTitle(sourceKey),
        modifier = modifier,
        largeTitle = false,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        snackbarHost = { com.acgcompass.core.designsystem.AcgSnackbarHost(snackbarHostState) },
    ) { _ ->
        StateScaffold(
            state = state,
            onOpenDoc = actions.onOpenDoc,
        ) { data ->
            SettingsSourceDetail(sourceKey = sourceKey, data = data, actions = actions)
        }
    }
}

/**
 * 装配 [SettingsActions]：备份 / 导入 / CSV 的 SAF 启动器、官方文档打开兜底、数据操作结果提示。
 * 主设置页与二级页共用同一实现和凭据处理逻辑。
 */
// 把 ViewModel 方法、文件选择器和导航回调组装成一个 SettingsActions，逐项对应，没有分支逻辑可拆。
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun rememberSettingsActions(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onClearLocalData: () -> Unit,
    onClearCache: () -> Unit,
    onOpenCacheManagement: () -> Unit = {},
    onOpenDataSafety: () -> Unit = {},
    onBangumiLogin: () -> Unit = {},
    onOpenBackgroundTasks: () -> Unit = {},
    onOpenRecommendPreferences: () -> Unit = {},
): SettingsActions {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 导出备份 — 系统「新建文档」保存器；写入 ViewModel 生成的零凭据 JSON。
    val exportBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 直接写进系统给的文件，不在内存里拼出整段 JSON。导出失败的提示由 ViewModel 给出。
                val written = runCatchingCancellable {
                    context.contentResolver.openOutputStream(uri)?.buffered()?.use { viewModel.writeBackup(it) }
                }.getOrNull()
                when (written) {
                    true -> viewModel.notifyExported("备份")
                    null -> viewModel.notifyExportFailed("备份")
                    false -> Unit
                }
            }
        }
    }
    // 导入备份 — 系统「打开文档」选择器；读取后合并不覆盖。
    val importBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.importBackup { context.contentResolver.openInputStream(uri) }
            }
        }
    }
    // CSV 导出：先生成并确认有记录，再让用户选择保存位置。
    var pendingCsvContent by remember { mutableStateOf<String?>(null) }
    val exportCsvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val csv = pendingCsvContent
        pendingCsvContent = null
        if (uri != null && csv != null) {
            scope.launch {
                val written = runCatchingCancellable {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } != null
                }.getOrDefault(false)
                if (written) {
                    viewModel.notifyExported("CSV")
                } else {
                    viewModel.notifyExportFailed("CSV")
                }
            }
        }
    }

    // 数据操作结果用底部提示，不打断操作。
    val dataOpMessage by viewModel.dataOpMessage.collectAsStateWithLifecycle()
    val showMessage = com.acgcompass.core.ui.LocalAppMessenger.current
    androidx.compose.runtime.LaunchedEffect(dataOpMessage) {
        dataOpMessage?.let {
            showMessage(it)
            viewModel.clearDataOpMessage()
        }
    }

    // 「查看官方文档」默认使用系统浏览器或 Custom Tab 打开。
    // 绝不携带任何 key/token。打不开时给出明确提示并把链接复制到剪贴板，不再无反应。
    val effectiveOpenDoc: (String) -> Unit = remember(onOpenDoc, uriHandler, context) {
        { url ->
            if (url.isNotBlank()) {
                val opened = runCatchingCancellable {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.isSuccess
                if (!opened) {
                    val fallback = runCatchingCancellable { uriHandler.openUri(url) }.isSuccess
                    if (!fallback) {
                        runCatchingCancellable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("doc-url", url),
                            )
                        }
                        showMessage("未找到可用浏览器，链接已复制到剪贴板：$url")
                    }
                }
            }
            onOpenDoc(url)
        }
    }
    val actions =
        remember(
            viewModel,
            effectiveOpenDoc,
            onOpenCacheManagement,
            onOpenDataSafety,
            onOpenBackgroundTasks,
            onOpenRecommendPreferences,
        ) {
            SettingsActions(
                onNavigateBack = onNavigateBack,
                onOpenDoc = effectiveOpenDoc,
                onToggleExpand = viewModel::onToggleExpand,
                onFieldChange = viewModel::onFieldChange,
                onToggleReveal = viewModel::onToggleReveal,
                onSave = viewModel::onSave,
                onClear = viewModel::onClear,
                onTestConnection = viewModel::onTestConnection,
                onToggleChange = viewModel::onToggleChange,
                onToggleAiExpand = viewModel::onToggleAiExpand,
                onAiProviderSelect = viewModel::onAiProviderSelect,
                onAiFieldChange = viewModel::onAiFieldChange,
                onToggleAiReveal = viewModel::onToggleAiReveal,
                onAiSave = viewModel::onAiSave,
                onAiClear = viewModel::onAiClear,
                onAiTestConnection = viewModel::onAiTestConnection,
                onSetAiTagClassificationMax = viewModel::onSetAiTagClassificationMax,
                onTogglePrivacyExpand = viewModel::onTogglePrivacyExpand,
                onClearLocalData = onClearLocalData,
                onClearCache = onClearCache,
                onImport = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onExport = { exportBackupLauncher.launch("hoshimi-backup.json") },
                onExportCsv = { kind ->
                    scope.launch {
                        val csv = viewModel.buildCsv(kind) ?: return@launch
                        if (csv.lineSequence().count() <= 1) {
                            viewModel.notifyNoExportData("CSV")
                            return@launch
                        }
                        pendingCsvContent = csv
                        val name = when (kind) {
                            com.acgcompass.domain.repository.CsvKind.BACKLOG -> "hoshimi-backlog.csv"
                            com.acgcompass.domain.repository.CsvKind.RATINGS -> "hoshimi-ratings.csv"
                            com.acgcompass.domain.repository.CsvKind.RATED_WORKS -> "hoshimi-rated-works.csv"
                            com.acgcompass.domain.repository.CsvKind.TIME_MACHINE -> "hoshimi-timemachine.csv"
                            com.acgcompass.domain.repository.CsvKind.TASTE_PROFILE -> "hoshimi-taste-profile.csv"
                        }
                        exportCsvLauncher.launch(name)
                    }
                },
                onDismissSaveNotice = viewModel::onDismissSaveNotice,
                onUseOfficialBangumiApi = viewModel::onUseOfficialBangumiApi,
                onSelectBangumiCandidate = viewModel::onSelectBangumiCandidate,
                onBangumiCustomUrlChange = viewModel::onBangumiCustomUrlChange,
                onSaveBangumiCustomUrl = viewModel::onSaveBangumiCustomUrl,
                onBangumiConsentChange = viewModel::onBangumiConsentChange,
                onBangumiLogin = onBangumiLogin,
                onSetAutoSyncInterval = viewModel::onSetAutoSyncInterval,
                onSetRecommendMinScore = viewModel::onSetRecommendMinScore,
                onSetTasteMatchThreshold = viewModel::onSetTasteMatchThreshold,
                onSetTasteMatchBranch = viewModel::onSetTasteMatchBranch,
                onSetThemeMode = viewModel::onSetThemeMode,
                onSetDynamicColor = viewModel::onSetDynamicColor,
                onSetLightThemePreset = viewModel::onSetLightThemePreset,
                onSetDarkThemePreset = viewModel::onSetDarkThemePreset,
                onSetPredictiveBackEnabled = viewModel::onSetPredictiveBackEnabled,
                onOpenCacheManagement = onOpenCacheManagement,
                onOpenDataSafety = onOpenDataSafety,
                onOpenBackgroundTasks = onOpenBackgroundTasks,
                onOpenRecommendPreferences = onOpenRecommendPreferences,
            )
        }
    return actions
}

/** 「凭据仅保存在本机」提示文案。 */
internal const val SAVE_NOTICE_TEXT = "凭据仅保存在本机，用于直接向第三方服务请求数据"

/** 推荐社区均分下限选项（标签到分值）；0 表示不限。 */
internal val RecommendMinScoreOptions: List<Pair<String, Float>> = listOf(
    "不限" to 0f,
    "5.0" to 5.0f,
    "6.0" to 6.0f,
    "6.5" to 6.5f,
    "7.0" to 7.0f,
    "7.5" to 7.5f,
    "8.0" to 8.0f,
)

/** 口味匹配度阈值选项（标签到阈值）；0 表示关闭过滤。 */
internal val TasteMatchThresholdOptions: List<Pair<String, Float>> = listOf(
    "关闭" to 0f,
    "40%" to 0.4f,
    "55%" to 0.55f,
    "65%" to 0.65f,
    "75%" to 0.75f,
)

/** 自动同步间隔选项（标签 to 分钟）。0=关闭；WorkManager 周期下限为 15 分钟。 */
internal val AutoSyncIntervalOptions: List<Pair<String, Int>> = listOf(
    "关闭" to 0,
    "6 小时" to 360,
    "12 小时" to 720,
    "24 小时" to 1440,
)

/**
 * 设置页无状态内容：顶部栏 + 各源/AI/隐私可折叠卡片 + 保存提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    state: UiState<SettingsUiState>,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    onOpenSource: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val saveNoticeVisible = (state as? UiState.Success)?.data?.saveNoticeVisible == true
    LaunchedEffect(saveNoticeVisible) {
        if (saveNoticeVisible) {
            snackbarHostState.showSnackbar(SAVE_NOTICE_TEXT)
            actions.onDismissSaveNotice()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("设置") },
                colors = transparentTopBarColors(),
                modifier = Modifier.topBarScrim(),
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        snackbarHost = { com.acgcompass.core.designsystem.AcgSnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            StateScaffold(
                state = state,
                onOpenDoc = actions.onOpenDoc,
            ) { data ->
                SettingsList(data = data, actions = actions, onOpenSource = onOpenSource)
            }
        }
    }
}

/**
 * 主设置页改为「摘要行」列表。每行展示源名 + 状态（公共搜索可用 / 个人同步是否配置）+
 * 最后测试时间 + 进入按钮，点击进入对应二级页编辑完整凭据；不再在主页内联展开所有编辑器。
 */
@Composable
internal fun SettingsList(
    data: SettingsUiState,
    actions: SettingsActions,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // 列表铺到顶栏下面：顶部让出顶栏，底部留出手势条空间。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.Top,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "appearance") {
            AppearanceCard(data = data, actions = actions)
        }
        item(key = "sources_header") { SettingsSectionHeader("数据源") }
        items(items = data.cards, key = { it.sourceId.name }) { card ->
            SettingsSummaryRow(
                title = card.title,
                statusText = card.statusText,
                lastTestedAtText = card.lastTestedAtText,
                onClick = { onOpenSource(card.sourceId.name) },
            )
        }
        item(key = "ai_provider") {
            SettingsSummaryRow(
                title = "AI 提供商",
                statusText = data.aiProvider.statusText,
                lastTestedAtText = data.aiProvider.lastTestedAtText,
                onClick = { onOpenSource(SourceId.AI_PROVIDER.name) },
            )
        }
        preferenceAndDataItems(data = data, actions = actions, onOpenSource = onOpenSource)
    }
}

/** 设置首页的「偏好」「数据」两组入口。 */
internal fun LazyListScope.preferenceAndDataItems(
    data: SettingsUiState,
    actions: SettingsActions,
    onOpenSource: (String) -> Unit,
) {
    item(key = "preferences_header") { SettingsSectionHeader("偏好") }
    item(key = "recommend") {
        SettingsSummaryRow(
            title = "推荐与口味",
            statusText = "口味匹配度算法（${TasteMatchBranch.fromKey(data.privacy.tasteMatchBranch).label}）、" +
                "推荐门槛、补充评分",
            lastTestedAtText = null,
            onClick = { onOpenSource(RECOMMEND_SOURCE_KEY) },
        )
    }
    item(key = "sync") {
        SettingsSummaryRow(
            title = "同步",
            statusText = "自动同步 Bangumi、加入待补池时同步「想看」",
            lastTestedAtText = null,
            onClick = { onOpenSource(SYNC_SOURCE_KEY) },
        )
    }
    item(key = "privacy") {
        SettingsSummaryRow(
            title = "隐私",
            statusText = "短评是否发给 AI、是否记录时光机快照",
            lastTestedAtText = null,
            onClick = { onOpenSource(PRIVACY_SOURCE_KEY) },
        )
    }
    item(key = "data_header") { SettingsSectionHeader("数据") }
    item(key = "data") {
        SettingsSummaryRow(
            title = "备份与导出",
            statusText = "导出 / 导入备份、导出 CSV、自动备份与数据安全",
            lastTestedAtText = null,
            onClick = { onOpenSource(DATA_SOURCE_KEY) },
        )
    }
    item(key = "cache_management") {
        SettingsSummaryRow(
            title = "缓存管理",
            statusText = "设置数量或空间上限，按来源、分类或作品清理",
            lastTestedAtText = null,
            onClick = actions.onOpenCacheManagement,
        )
    }
    item(key = "background_tasks") {
        SettingsSummaryRow(
            title = "后台任务",
            statusText = "同步、推送、补资料、补评分、自动备份的运行情况和失败原因",
            lastTestedAtText = null,
            onClick = actions.onOpenBackgroundTasks,
        )
    }
}

@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** 设置二级页的标识键：[SourceId] 枚举名复用为源键；隐私页用独立常量。 */
internal const val PRIVACY_SOURCE_KEY = "PRIVACY"
internal const val RECOMMEND_SOURCE_KEY = "RECOMMEND"
internal const val SYNC_SOURCE_KEY = "SYNC"
internal const val DATA_SOURCE_KEY = "DATA"

/** 二级页标题映射（静态，避免依赖异步状态；与各源卡片标题保持一致）。 */
internal fun settingsSourceTitle(sourceKey: String): String = when (sourceKey) {
    SourceId.BANGUMI.name -> "Bangumi"
    SourceId.ANILIST.name -> "AniList"
    SourceId.JIKAN.name -> "Tenrai"
    SourceId.MAL.name -> "MyAnimeList（官方）"
    SourceId.VNDB.name -> "VNDB"
    SourceId.AI_PROVIDER.name -> "AI 提供商"
    PRIVACY_SOURCE_KEY -> "隐私"
    RECOMMEND_SOURCE_KEY -> "推荐与口味"
    SYNC_SOURCE_KEY -> "同步"
    DATA_SOURCE_KEY -> "备份与导出"
    else -> "设置"
}

/**
 * 主设置页摘要行包含来源名、状态、最后测试时间和进入按钮，整行可点击。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSummaryRow(
    title: String,
    statusText: String?,
    lastTestedAtText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行首是入口的圆形图标底（按标题挑图标），行尾箭头；状态与测试时间合成一行说明。
    val supporting = listOfNotNull(
        statusText?.takeIf { it.isNotBlank() },
        lastTestedAtText?.takeIf { it.isNotBlank() }?.let { "最后测试：$it" },
    ).joinToString(" · ").ifBlank { null }
    PrismCard(modifier = modifier.fillMaxWidth()) {
        NavRow(
            title = title,
            supporting = supporting,
            onClick = onClick,
            leading = {
                IconWell(entryIconFor(title))
            },
        )
    }
}

/** 备份与导出二级页：导出 / 导入、数据安全、缓存管理。 */
internal fun LazyListScope.dataSettingsItems(actions: SettingsActions) {
    item(key = "data") { SettingsGroupCard("备份与导出") { DataManagementActions(actions) } }
    item(key = "data_safety") {
        SettingsSummaryRow(
            title = "数据安全",
            statusText = "每周自动备份、从自动备份恢复、数据库升级前备份",
            lastTestedAtText = null,
            onClick = actions.onOpenDataSafety,
        )
    }
    item(key = "data_cache") {
        SettingsSummaryRow(
            title = "缓存管理",
            statusText = "设置数量或空间上限，按来源、分类或作品清理",
            lastTestedAtText = null,
            onClick = actions.onOpenCacheManagement,
        )
    }
}

/**
 * 二级页内容：按 [sourceKey] 渲染对应的既有详细卡片，并强制展开。
 * Bangumi 额外渲染 [BangumiApiConfigCard]，保留官方/社区/自定义下拉与非官方 Token 风险确认。
 */
@Composable
internal fun SettingsSourceDetail(
    sourceKey: String,
    data: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.Top,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (sourceKey) {
            SourceId.AI_PROVIDER.name -> item(key = "ai_provider") {
                AiProviderSettingsCard(card = data.aiProvider.copy(expanded = true), actions = actions)
            }
            RECOMMEND_SOURCE_KEY -> item(key = "recommend") {
                SettingsGroupCard("推荐与口味") { RecommendationSettings(card = data.privacy, actions = actions) }
            }
            SYNC_SOURCE_KEY -> item(key = "sync") {
                SettingsGroupCard("同步") { AutoSyncSettings(card = data.privacy, actions = actions) }
            }
            PRIVACY_SOURCE_KEY -> item(key = "privacy") {
                SettingsGroupCard("隐私") { PrivacyToggles(card = data.privacy, actions = actions) }
            }
            DATA_SOURCE_KEY -> dataSettingsItems(actions)
            else -> {
                val card = data.cards.firstOrNull { it.sourceId.name == sourceKey }
                if (card != null) {
                    item(key = "source") {
                        SourceSettingsCard(card = card.copy(expanded = true), actions = actions)
                    }
                    if (card.sourceId == SourceId.BANGUMI) {
                        item(key = "bangumi_oauth_login") {
                            BangumiOAuthLoginCard(onLogin = actions.onBangumiLogin)
                        }
                        item(key = "bangumi_api") {
                            BangumiApiConfigCard(state = data.bangumiApi, actions = actions)
                        }
                    }
                }
            }
        }
    }
}

// endregion

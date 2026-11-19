package com.acgcompass.feature.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.IconWell
import com.acgcompass.core.designsystem.NavRow
import com.acgcompass.core.designsystem.OptionList
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.entryIconFor
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.blurredTopBarColors
import com.acgcompass.core.ui.topBarBlur
import com.acgcompass.core.ui.topBarBlurSource
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.domain.model.ThemePreset
import com.acgcompass.domain.taste.TasteMatchBranch
import dev.chrisbanes.haze.HazeState
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
)

/**
 * 设置页有状态入口：注入 [SettingsViewModel]，收集 [SettingsUiState] 并装配回调。
 *
 * 导航级入口（返回 / 打开文档 / 清除数据 / 缓存 / 导入 / 导出）由调用方传入。
 *
 */
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(innerPadding),
            onOpenDoc = actions.onOpenDoc,
        ) { data ->
            SettingsSourceDetail(sourceKey = sourceKey, data = data, actions = actions)
        }
    }
}

/**
 * 装配 [SettingsActions]：备份 / 导入 / CSV 的 SAF 启动器、官方文档打开兜底、数据操作结果 Toast。
 * 主设置页与二级页共用同一实现和凭据处理逻辑。
 */
// 把 ViewModel 方法、文件选择器和导航回调组装成一个 SettingsActions，逐项对应，没有分支逻辑可拆。
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun rememberSettingsActions(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onClearLocalData: () -> Unit,
    onClearCache: () -> Unit,
    onOpenCacheManagement: () -> Unit = {},
    onOpenDataSafety: () -> Unit = {},
    onBangumiLogin: () -> Unit = {},
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
                val json = viewModel.buildBackupJson()
                if (json != null) {
                    val written = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                    }.getOrDefault(false)
                    if (written) viewModel.notifyExported("备份") else viewModel.notifyExportFailed("备份")
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
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                if (json != null) viewModel.importBackupJson(json)
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
                val written = runCatching {
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

    // 数据操作结果提示（Toast 形式，避免打断）。
    val dataOpMessage by viewModel.dataOpMessage.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(dataOpMessage) {
        dataOpMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearDataOpMessage()
        }
    }

    // 「查看官方文档」默认使用系统浏览器或 Custom Tab 打开。
    // 绝不携带任何 key/token。打不开时给出明确提示并把链接复制到剪贴板，不再无反应。
    val effectiveOpenDoc: (String) -> Unit = remember(onOpenDoc, uriHandler, context) {
        { url ->
            if (url.isNotBlank()) {
                val opened = runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.isSuccess
                if (!opened) {
                    val fallback = runCatching { uriHandler.openUri(url) }.isSuccess
                    if (!fallback) {
                        runCatching {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("doc-url", url),
                            )
                        }
                        android.widget.Toast.makeText(
                            context,
                            "未找到可用浏览器，链接已复制到剪贴板：$url",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            onOpenDoc(url)
        }
    }
    val actions = remember(viewModel, effectiveOpenDoc, onOpenCacheManagement, onOpenDataSafety) {
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
        )
    }
    return actions
}

/** 「凭据仅保存在本机」提示文案。 */
private const val SAVE_NOTICE_TEXT = "凭据仅保存在本机，用于直接向第三方服务请求数据"

/** 推荐社区均分下限选项（标签到分值）；0 表示不限。 */
private val RecommendMinScoreOptions: List<Pair<String, Float>> = listOf(
    "不限" to 0f,
    "5.0" to 5.0f,
    "6.0" to 6.0f,
    "6.5" to 6.5f,
    "7.0" to 7.0f,
    "7.5" to 7.5f,
    "8.0" to 8.0f,
)

/** 口味匹配度阈值选项（标签到阈值）；0 表示关闭过滤。 */
private val TasteMatchThresholdOptions: List<Pair<String, Float>> = listOf(
    "关闭" to 0f,
    "40%" to 0.4f,
    "55%" to 0.55f,
    "65%" to 0.65f,
    "75%" to 0.75f,
)

/** 自动同步间隔选项（标签 to 分钟）。0=关闭；WorkManager 周期下限为 15 分钟。 */
private val AutoSyncIntervalOptions: List<Pair<String, Int>> = listOf(
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

    val hazeState = remember { HazeState() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("设置") },
                colors = blurredTopBarColors(),
                modifier = Modifier.topBarBlur(hazeState),
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().topBarBlurSource(hazeState)) {
            StateScaffold(
                state = state,
                modifier = Modifier.padding(padding),
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
private fun SettingsList(
    data: SettingsUiState,
    actions: SettingsActions,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // 父级已消费标题栏 innerPadding；统一留白（顶部 16dp 呼吸 + 底部 96dp 不被底栏/手势条遮挡）。
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
private fun LazyListScope.preferenceAndDataItems(
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
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

private val ThemeModeOptions: List<Pair<String, ThemeMode>> = listOf(
    "跟随系统" to ThemeMode.SYSTEM,
    "浅色" to ThemeMode.LIGHT,
    "深色" to ThemeMode.DARK,
)

@Composable
private fun AppearanceCard(data: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val showThemePresets = !supportsDynamicColor || !data.dynamicColor
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "外观", style = MaterialTheme.typography.titleMedium)
            Text(text = "主题模式", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                ThemeModeOptions.forEach { (label, mode) ->
                    FlatFilterChip(
                        selected = data.themeMode == mode,
                        onClick = { actions.onSetThemeMode(mode) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            if (supportsDynamicColor) {
                ToggleRow(
                    label = "动态取色（Material You）",
                    description = "使用系统壁纸配色；关闭后使用应用内置配色。",
                    checked = data.dynamicColor,
                    enabled = true,
                    onCheckedChange = actions.onSetDynamicColor,
                )
            }
            if (showThemePresets) {
                ThemeColorPicker(data = data, actions = actions)
            }
            ToggleRow(
                label = "预测性返回手势",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "返回时预览上一页；关闭后使用普通返回。"
                } else {
                    "需要 Android 13 或更高版本；当前自动使用普通返回。"
                },
                checked = data.predictiveBackEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                onCheckedChange = actions.onSetPredictiveBackEnabled,
            )
        }
    }
}

/** 照 FlClash：只选一个主题色，浅色 / 深色各自用它生成对应的配色（同一套搭配）。 */
@Composable
private fun ThemeColorPicker(data: SettingsUiState, actions: SettingsActions) {
    Text("主题色", style = MaterialTheme.typography.bodyMedium)
    ThemePresetRow(
        selected = data.lightThemePreset,
        onSelect = { preset ->
            actions.onSetLightThemePreset(preset)
            actions.onSetDarkThemePreset(preset)
        },
    )
    Text(
        "浅色和深色会各自用这个颜色生成对应的配色。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 主题色：一排色块（照 FlClash），选中的一块里打勾；颜色就是选项本身。 */
@Composable
private fun ThemePresetRow(selected: ThemePreset, onSelect: (ThemePreset) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
    ) {
        ThemePreset.entries.forEach { preset ->
            val color = Color(preset.seed)
            val isSelected = preset == selected
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(color)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(preset) })
                    .semantics { contentDescription = preset.label },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (color.luminance() > SWATCH_DARK_CHECK) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private val SWATCH_SIZE = 40.dp
private const val SWATCH_DARK_CHECK = 0.5f

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
private fun SettingsSummaryRow(
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
private fun LazyListScope.dataSettingsItems(actions: SettingsActions) {
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
private fun SettingsSourceDetail(
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

/** Bangumi OAuth 登录入口卡片（设置 → Bangumi）。构建凭据完整时才开放登录。 */
@Composable
private fun BangumiOAuthLoginCard(onLogin: () -> Unit) {
    val hasBuildCredentials =
        com.acgcompass.BuildConfig.BANGUMI_CLIENT_ID.isNotBlank() &&
            (
                com.acgcompass.BuildConfig.BANGUMI_CLIENT_SECRET.isNotBlank() ||
                    com.acgcompass.BuildConfig.BANGUMI_OAUTH_PROXY_BASE_URL.isNotBlank()
                )
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "用 Bangumi 登录（OAuth）", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (hasBuildCredentials) {
                    "此构建已启用 Bangumi OAuth。登录后，应用会在令牌到期前尝试自动续期。"
                } else {
                    "当前构建未配置 Bangumi OAuth，一键登录不可用；" +
                        "仍可在上方保存个人 Access Token。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasBuildCredentials,
            ) {
                Text("用 Bangumi 登录")
            }
        }
    }
}

// region 可复用基础块

/** 可折叠卡片骨架：标题行、独立状态行和展开内容。 */
@Composable
internal fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    content: @Composable () -> Unit,
) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // 标题占满剩余宽度并单行省略，状态文字单独成行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = if (expanded) "$title 已展开" else "$title 已折叠" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开",
                    )
                }
            }
            // 行2：状态文案（独立成行，可换行，不再用会挤压标题的等宽 Chip）。
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 凭据输入框：敏感字段默认掩码（[PasswordVisualTransformation]），尾部提供临时显示开关。
 * 非敏感字段（Base URL / 模型名）始终明文。
 */
@Composable
internal fun CredentialField(
    field: CredentialFieldState,
    onValueChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlatTextField(
        value = field.value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(field.label + if (field.optional) "（可选）" else "") },
        placeholder = {
            // 已保存的敏感字段只显示掩码；重新输入即可更换。
            val ph = if (field.savedMasked) "已保存 ••••••（重新输入以更换）" else field.placeholder
            if (ph.isNotEmpty()) Text(ph)
        },
        singleLine = true,
        visualTransformation = if (field.sensitive && !field.revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (field.sensitive && !field.savedMasked) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (field.revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (field.revealed) "隐藏 ${field.label}" else "临时显示 ${field.label}",
                    )
                }
            }
        } else {
            null
        },
    )
}

/** 单个开关项（数据源启用 / 同步 / 列表读取 / 成人内容 / 隐私开关等）。 */
@Composable
internal fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlatSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 连接测试三态结果：
 * - TESTING → 进度指示 + 「测试中」。
 * - SUCCESS → 成功文案。
 * - FAILED → 失败原因 + 「查看文档」入口。
 * - IDLE → 不渲染。
 */
@Composable
internal fun ConnectionTestResultRow(
    test: ConnectionTestState,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (test.status) {
        ConnectionTestStatus.IDLE -> Unit
        ConnectionTestStatus.TESTING -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("连接测试中…", style = MaterialTheme.typography.bodyMedium)
        }

        ConnectionTestStatus.SUCCESS -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = test.message ?: "连接成功",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ConnectionTestStatus.FAILED -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = test.message ?: "连接失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val docUrl = test.docUrl
            if (!docUrl.isNullOrEmpty()) {
                DocLinkButton(url = docUrl, onOpenDoc = onOpenDoc)
            }
        }
    }
}

/** 「查看官方文档」入口按钮。绝不携带任何 key/token。 */
@Composable
internal fun DocLinkButton(
    url: String,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "查看官方文档",
) {
    TextButton(onClick = { onOpenDoc(url) }, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region 数据源卡片

/**
 * 单个数据源的可折叠设置卡片：用户名（Bangumi）、凭据字段（掩码 + 临时显示）、开关、提示信息、
 * 连接测试三态、保存 / 清除按钮和官方文档链接。
 */
@Composable
internal fun SourceSettingsCard(card: SourceCardState, actions: SettingsActions, modifier: Modifier = Modifier) {
    CollapsibleCard(
        title = card.title,
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = { actions.onToggleExpand(card.sourceId) },
        modifier = modifier,
    ) {
        SourceSettingsFields(card = card, actions = actions)
        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)
        FlatDivider()
        SourceSettingsActions(card = card, actions = actions)
        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

@Composable
private fun SourceSettingsFields(card: SourceCardState, actions: SettingsActions) {
    if (card.showUsername) {
        Text(
            text = "当前用户名：" + (card.username ?: "暂无数据"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    card.lastTestedAtText?.let {
        Text(
            text = "最后测试：$it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    card.fields.forEach { field ->
        CredentialField(
            field = field,
            onValueChange = { actions.onFieldChange(card.sourceId, field.key, it) },
            onToggleReveal = { actions.onToggleReveal(card.sourceId, field.key) },
        )
    }
    card.toggles.forEach { toggle ->
        ToggleRow(
            label = toggle.label,
            description = toggle.description,
            checked = toggle.checked,
            enabled = toggle.enabled,
            onCheckedChange = { actions.onToggleChange(toggle.key, it) },
        )
    }
    card.infoNotes.forEach { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceSettingsActions(card: SourceCardState, actions: SettingsActions) {
    if (card.fields.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onSave(card.sourceId) },
                enabled = card.hasDraft,
            ) {
                Text("保存")
            }
            FilledTonalButton(
                onClick = { actions.onTestConnection(card.sourceId) },
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("连接测试")
            }
        }
        if (card.configured) {
            TextButton(onClick = { actions.onClear(card.sourceId) }) {
                Text(text = if (card.sourceId == SourceId.BANGUMI) "清除授权" else "清除")
            }
        }
    } else {
        FilledTonalButton(
            onClick = { actions.onTestConnection(card.sourceId) },
            enabled = card.test.status != ConnectionTestStatus.TESTING,
        ) {
            Text("连接测试")
        }
    }
}

// endregion

// region AI 提供商卡片

/**
 * AI Provider 设置卡片：Provider 选择、API key（掩码）、Base URL、模型名、连接测试、预算提醒、
 * 保存 / 清除 / 文档链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiProviderSettingsCard(
    card: AiProviderCardState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        title = "AI 提供商",
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = actions.onToggleAiExpand,
        modifier = modifier,
    ) {
        OptionList(
            title = "AI 提供商",
            options = card.options,
            selected = card.selectedProvider,
            label = { it.displayName },
            onSelect = actions.onAiProviderSelect,
        )

        card.lastTestedAtText?.let {
            Text(
                text = "最后测试：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // API key（掩码）+ Base URL + 模型名。
        card.fields.forEach { field ->
            CredentialField(
                field = field,
                onValueChange = { actions.onAiFieldChange(field.key, it) },
                onToggleReveal = { actions.onToggleAiReveal(field.key) },
            )
        }

        ToggleRow(
            label = "启用 Thinking 模式",
            description = "按当前 AI 提供商分别记忆；默认关闭。开启会增加响应时间和用量。",
            checked = card.thinkingEnabled,
            enabled = true,
            onCheckedChange = { actions.onToggleChange(ToggleKeys.AI_THINKING_ENABLED, it) },
        )

        var maxTagsText by remember(card.maxClassificationTags) {
            mutableStateOf(card.maxClassificationTags.toString())
        }
        FlatTextField(
            value = maxTagsText,
            onValueChange = { value ->
                maxTagsText = value.filter(Char::isDigit).take(MAX_TAG_INPUT_DIGITS)
            },
            label = { Text("每次最多细分的标签数（100–1000）") },
            supportingText = {
                Text(
                    if (
                        (maxTagsText.toIntOrNull() ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX) >
                        SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
                    ) {
                        "超过 300 会增加请求成本、处理时间和画像计算时间"
                    } else {
                        "默认 300；每批最多处理 40 个标签"
                    },
                )
            },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = {
                        val value = maxTagsText.toIntOrNull()
                            ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
                        actions.onSetAiTagClassificationMax(
                            value.coerceIn(
                                SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
                                SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
                            ),
                        )
                    },
                ) { Text("应用") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 预算/用量提醒。
        Text(
            text = card.budgetReminder,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)

        FlatDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onAiSave, enabled = card.hasDraft) {
                Text("保存")
            }
            FilledTonalButton(
                onClick = actions.onAiTestConnection,
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("测试")
            }
        }
        if (card.configured) {
            TextButton(onClick = actions.onAiClear) { Text("清除") }
        }

        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

// endregion

private const val MAX_TAG_INPUT_DIGITS = 4

// region 隐私卡片

/** 设置二级页里的一组设置：标题 + 内容，直接展开。 */
@Composable
private fun SettingsGroupCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PrivacyToggles(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "允许把短评发给 AI",
        description = "开启后，详情页的「AI 匹配分析」和「评论摘要」会把你在 Bangumi 写的短评、" +
            "以及 Bangumi 社区的公开短评发给 AI，用来判断口味、总结口碑。关闭（默认）时只发标题、标签和评分；" +
            "本机计算的口味画像不受影响。",
        checked = card.allowAiAnalyzeReviews,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.ALLOW_AI_ANALYZE_REVIEWS, it) },
    )
    ToggleRow(
        label = "记录本地时光机快照",
        description = "在本机记录评分、短评和观看状态的变化，用于时光机和年度报告",
        checked = card.recordTimeMachineSnapshots,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.RECORD_TIME_MACHINE_SNAPSHOTS, it) },
    )
}

@Composable
private fun AutoSyncSettings(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "加入待补池时同步为 Bangumi「想看」",
        description = "已登录 Bangumi 时，从任何地方加入待补池的作品都会在后台标成「想看」；" +
            "Bangumi 上已经有收藏状态（看过、在看等）的不会改动，没网时会稍后重试",
        checked = card.syncBacklogToBangumi,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.SYNC_BACKLOG_TO_BANGUMI, it) },
    )
    Text(text = "自动同步 Bangumi", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "按所选间隔在后台自动拉取最新收藏（需已配置 Bangumi 账号、联网）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        AutoSyncIntervalOptions.forEach { (label, minutes) ->
            FlatFilterChip(
                selected = card.autoSyncIntervalMinutes == minutes,
                onClick = { actions.onSetAutoSyncInterval(minutes) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun RecommendationSettings(card: PrivacyCardState, actions: SettingsActions) {
    val branch = TasteMatchBranch.fromKey(card.tasteMatchBranch)
    OptionList(
        title = "口味匹配度算法",
        options = TasteMatchBranch.entries,
        selected = branch,
        label = { it.label },
        onSelect = { actions.onSetTasteMatchBranch(it.key) },
    )
    Text(
        text = branch.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlatDivider()
    Text(
        text = "「今晚看什么」只推荐社区均分 ≥ 下限、且口味匹配度 ≥ 阈值的作品；阈值越高越严格（结果可能更少）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = "推荐最低社区分", style = MaterialTheme.typography.bodyMedium)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        RecommendMinScoreOptions.forEach { (label, score) ->
            FlatFilterChip(
                selected = kotlin.math.abs(card.recommendMinCommunityScore - score) < 0.01f,
                onClick = { actions.onSetRecommendMinScore(score) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    Text(text = "口味匹配度阈值（低于则不推）", style = MaterialTheme.typography.bodyMedium)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        TasteMatchThresholdOptions.forEach { (label, threshold) ->
            FlatFilterChip(
                selected = kotlin.math.abs(card.tasteMatchThreshold - threshold) < 0.01f,
                onClick = { actions.onSetTasteMatchThreshold(threshold) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    ToggleRow(
        label = "自动补充其它平台评分",
        description = "打开后马上补一批，之后联网且电量不低时约每 6 小时一次：为只有一个平台评分的动画去 AniList、" +
            "Tenrai 等补查评分，每次最多 20 部，收藏和待补池里的作品优先；请求都让你正在看的页面先走",
        checked = card.scoreDiffAutoBackfill,
        enabled = true,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.SCORE_DIFF_AUTO_BACKFILL, it) },
    )
    AiDailyDrawToggle(card, actions)
}

/** 首页「今日补番签」由 AI 生成：配置了 AI 提供商之后才能打开。 */
@Composable
private fun AiDailyDrawToggle(card: PrivacyCardState, actions: SettingsActions) {
    ToggleRow(
        label = "今日补番签由 AI 生成",
        description = if (card.aiConfigured) {
            "每天一次，把待补池里的作品名和集数发给你配置的 AI 提供商，生成针对你待补池的「宜 / 忌」；" +
                "失败时用内置的签"
        } else {
            "需要先在「数据源 → AI 提供商」里配置后才能打开"
        },
        checked = card.aiDailyDraw && card.aiConfigured,
        enabled = card.aiConfigured,
        onCheckedChange = { actions.onToggleChange(ToggleKeys.AI_DAILY_DRAW, it) },
    )
}

@Composable
private fun DataManagementActions(actions: SettingsActions) {
    FilledTonalButton(onClick = actions.onExport, modifier = Modifier.fillMaxWidth()) {
        Text("导出备份（默认不含 key）")
    }
    FilledTonalButton(onClick = actions.onImport, modifier = Modifier.fillMaxWidth()) {
        Text("导入备份")
    }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.BACKLOG) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 待补池", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.RATINGS) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 评分", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.RATED_WORKS) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 已评分作品", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.TIME_MACHINE) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 时光机", maxLines = 1) }
    FilledTonalButton(
        onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.TASTE_PROFILE) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("导出 CSV · 口味画像标签", maxLines = 1) }
}

// endregion

// region Bangumi API 地址

/**
 * Bangumi API 地址配置卡，使用单个下拉框选择官方、社区或自定义地址。
 * 下拉选项：官方 API（默认）/ bgmapi.anibt.net / api.bangumi.one / 自定义 API 地址。
 * 选「自定义」才显示输入框；非官方地址显示风险提示与 Token 同意开关（底层保护逻辑不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BangumiApiConfigCard(state: BangumiApiUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val officialOption = "官方 API（默认）"
    val customOption = "自定义 API 地址"
    // 选项：官方 + 候选 + 自定义。
    val options = buildList {
        add(officialOption)
        state.candidates.forEach { (label, _) -> add("$label（社区反代）") }
        add(customOption)
    }
    val currentLabel = when (state.mode) {
        BangumiApiMode.OFFICIAL -> officialOption
        BangumiApiMode.CUSTOM -> customOption
        BangumiApiMode.COMMUNITY ->
            state.candidates
                .firstOrNull { it.second.trimEnd('/') == state.baseUrl.trimEnd('/') }
                ?.let { "${it.first}（社区反代）" } ?: customOption
    }
    var customMode by remember(state.mode) { mutableStateOf(state.mode == BangumiApiMode.CUSTOM) }

    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BangumiApiHeader(state.baseUrl)
            OptionList(
                title = "API 地址",
                options = options,
                selected = if (customMode) customOption else currentLabel,
                label = { it },
                onSelect = { option ->
                    when (option) {
                        officialOption -> {
                            customMode = false
                            actions.onUseOfficialBangumiApi()
                        }
                        customOption -> customMode = true
                        else -> {
                            customMode = false
                            val index = options.indexOf(option) - 1
                            state.candidates.getOrNull(index)?.let {
                                actions.onSelectBangumiCandidate(it.second)
                            }
                        }
                    }
                },
            )
            if (customMode) {
                BangumiCustomUrlEditor(state.customDraft, actions)
            }
            if (!state.isOfficial) {
                BangumiProxyWarning(state.consent, actions.onBangumiConsentChange)
            }
        }
    }
}

@Composable
private fun BangumiApiHeader(baseUrl: String) {
    Text("Bangumi API 地址", style = MaterialTheme.typography.titleMedium)
    Text(
        "当前：$baseUrl",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "官方地址在部分网络环境可能无法直连，可改用社区反代或自建反代。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BangumiCustomUrlEditor(customDraft: String, actions: SettingsActions) {
    FlatTextField(
        value = customDraft,
        onValueChange = actions.onBangumiCustomUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("自定义 API 地址") },
        placeholder = { Text("https://your-proxy.example.com/") },
    )
    Button(onClick = actions.onSaveBangumiCustomUrl, modifier = Modifier.fillMaxWidth()) {
        Text("保存自定义地址", maxLines = 1)
    }
}

@Composable
private fun BangumiProxyWarning(consent: Boolean, onConsentChange: (Boolean) -> Unit) {
    FlatDivider()
    Text(
        "你正在使用非官方 Bangumi API 地址。个人 Token 会发送到该地址，请确认你信任该服务。" +
            "建议优先使用官方 API、自己的代理或自建反代。公共搜索可用非官方地址；个人同步需在下方确认后才生效。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    ToggleRow(
        label = "我已知晓并同意个人 Token 发往该非官方地址",
        description = "用于个人同步（getMe / 收藏）。不勾选则仅公共搜索使用该地址。",
        checked = consent,
        enabled = true,
        onCheckedChange = onConsentChange,
    )
}

// endregion

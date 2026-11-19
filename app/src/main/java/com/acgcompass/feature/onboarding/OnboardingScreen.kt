package com.acgcompass.feature.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces

/**
 * 首启引导路由入口。
 *
 * 连接 [OnboardingViewModel]：用户点击「开始使用」时持久化 `onboardingShown = true`
 * 并回调 [onFinished]，由导航层切换到主界面。
 *
 * @param onFinished 引导完成后的回调（导航离开引导页）。
 */
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    OnboardingScreen(
        state = OnboardingUiState.DEFAULT,
        onConfirm = { setup ->
            // 把导航离开作为「持久化完成」回调传入——只有 onboardingShown 等写入全部落盘后才离开引导页，
            // 避免引导 VM 被清导致写入被取消（部分机型 I/O 慢时会「切主题/重启后又触发引导」）。
            viewModel.onOnboardingComplete(setup, onPersisted = onFinished)
        },
        modifier = modifier,
    )
}

/**
 * 无状态的 Material 3 多步引导界面。
 *
 * 将原单页长表单拆为 4 步，降低首启认知负担：
 * 1. 介绍：合规 / 隐私 / 多源等要点（[OnboardingUiState.highlights]）。
 * 2. 代理 API 同意：社区反代说明 + 个人 Token 中转同意（可不勾选，仅公共搜索经反代）。
 * 3. Bangumi 个人同步（可选）：粘贴 Access Token。
 * 4. AI 增强（可选）：OpenAI 兼容 API Key / Base URL / 模型名。
 *
 * 顶部进度点指示当前步；底部「上一步 / 下一步」，末步为主操作（[OnboardingUiState.confirmLabel]）；
 * 后两步为可选项，顶部「跳过」直接以当前草稿完成。草稿仅在内存，完成时一次性上抛加密保存（空值不写）。
 * 设计为无状态以便预览与 UI 测试：内容来自 [state]，完成通过 [onConfirm] 上抛 [OnboardingSetup]。
 */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun OnboardingScreen(state: OnboardingUiState, onConfirm: (OnboardingSetup) -> Unit, modifier: Modifier = Modifier) {
    // 可选快速配置草稿仅保存在内存中，完成引导时一次性提交；空值不写入。
    var consented by remember { mutableStateOf(false) }
    var communityProxyEnabled by remember { mutableStateOf(state.communityProxyEnabledByDefault) }
    var showProxyWarning by remember { mutableStateOf(false) }
    var showTokenProxyWarning by remember { mutableStateOf(false) }
    var bangumiToken by remember { mutableStateOf("") }
    var aiApiKey by remember { mutableStateOf("") }
    var aiBaseUrl by remember { mutableStateOf(OnboardingDefaults.AI_BASE_URL) }
    var aiModel by remember { mutableStateOf(OnboardingDefaults.AI_MODEL) }
    var revealSecrets by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }

    fun complete() = onConfirm(
        OnboardingSetup(
            useCommunityProxy = communityProxyEnabled,
            consentToProxyToken = consented,
            bangumiToken = bangumiToken,
            aiApiKey = aiApiKey,
            aiBaseUrl = aiBaseUrl,
            aiModel = aiModel,
        ),
    )

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(16.dp))
            // 顶部：进度点 +（可选步）跳过。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepDots(
                    current = step,
                    total = ONBOARDING_PAGE_COUNT,
                    modifier = Modifier.weight(1f),
                )
                if (step >= FIRST_OPTIONAL_PAGE) {
                    TextButton(onClick = { complete() }) { Text("跳过") }
                }
            }

            Crossfade(
                targetState = step,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (page) {
                        0 -> IntroPage(state)
                        1 -> ConsentPage(
                            consentPrompt = state.consentPrompt,
                            communityProxyEnabled = communityProxyEnabled,
                            onCommunityProxyChange = { communityProxyEnabled = it },
                            consented = consented,
                            onConsentedChange = { consented = it },
                        )
                        2 -> BangumiPage(
                            bangumiToken = bangumiToken,
                            onBangumiTokenChange = { bangumiToken = it },
                            revealSecrets = revealSecrets,
                            onToggleReveal = { revealSecrets = !revealSecrets },
                        )
                        else -> AiPage(
                            aiApiKey = aiApiKey,
                            onAiApiKeyChange = { aiApiKey = it },
                            aiBaseUrl = aiBaseUrl,
                            onAiBaseUrlChange = { aiBaseUrl = it },
                            aiModel = aiModel,
                            onAiModelChange = { aiModel = it },
                            revealSecrets = revealSecrets,
                            onToggleReveal = { revealSecrets = !revealSecrets },
                        )
                    }
                }
            }

            // 底部导航：上一步 | 下一步 / 开始使用。
            val isLast = step == ONBOARDING_PAGE_COUNT - 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    FilledTonalButton(
                        onClick = { if (step > 0) step-- },
                        modifier = Modifier.weight(1f),
                    ) { Text("上一步") }
                }
                Button(
                    onClick = {
                        if (step == 1 && !communityProxyEnabled) {
                            showProxyWarning = true
                        } else if (step == 1 && !consented) {
                            showTokenProxyWarning = true
                        } else if (isLast) {
                            complete()
                        } else {
                            step++
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isLast) state.confirmLabel else "下一步")
                }
            }
        }
    }

    if (showProxyWarning) {
        AlertDialog(
            onDismissRequest = { showProxyWarning = false },
            title = { Text("未启用社区反代") },
            text = {
                Text(
                    "关闭社区反代后，当前网络环境可能无法连接 Bangumi 服务。" +
                        "如果你拥有可以直连官方 API 的网络环境，可以忽略此提示。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showProxyWarning = false
                    step++
                }) {
                    Text("继续使用官方地址")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProxyWarning = false }) {
                    Text("返回开启反代")
                }
            },
        )
    }

    if (showTokenProxyWarning) {
        AlertDialog(
            onDismissRequest = { showTokenProxyWarning = false },
            title = { Text("个人同步仍将使用官方地址") },
            text = {
                Text(
                    "你尚未允许个人 Access Token 经社区反代中转。公共 Bangumi 内容仍可使用反代，" +
                        "但登录后的个人同步会直连官方 API；在部分网络环境下可能连接失败。" +
                        "如果你的网络可以直连官方服务，可以忽略此提示。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTokenProxyWarning = false
                    step++
                }) {
                    Text("继续使用官方地址")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenProxyWarning = false }) {
                    Text("返回勾选")
                }
            },
        )
    }
}

@Composable
private fun HighlightCard(index: Int, highlight: OnboardingHighlight) {
    PrismCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Surfaces.raisedColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "第 $index 条说明" },
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = highlight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = highlight.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 顶部步骤进度指示：当前步用主色加长条，其余为浅色圆点。 */
@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "第 ${current + 1} / $total 步" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (active) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

/** 第 1 步：介绍——欢迎标题 + 合规 / 隐私 / 多源等要点卡片。 */
@Composable
private fun IntroPage(state: OnboardingUiState) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = state.title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    state.highlights.forEachIndexed { index, highlight ->
        HighlightCard(index = index + 1, highlight = highlight)
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** 第 2 步：代理 API 同意——社区反代说明 + 个人 Token 中转同意（可不勾选）。 */
@Composable
private fun ConsentPage(
    consentPrompt: String,
    communityProxyEnabled: Boolean,
    onCommunityProxyChange: (Boolean) -> Unit,
    consented: Boolean,
    onConsentedChange: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = "数据源与网络",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "官方 Bangumi API 在部分网络环境可能无法直连。社区反代只用于公共数据请求，" +
            "可随时在「设置 → 数据源」切换地址。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    OnboardingToggleRow(
        label = "启用社区反代",
        description = "关闭后使用官方 API；如果当前网络无法直连，Bangumi 内容可能无法加载。",
        checked = communityProxyEnabled,
        onCheckedChange = onCommunityProxyChange,
    )
    Spacer(Modifier.height(12.dp))
    PrismCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surfaces.raisedColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onConsentedChange(!consented) }
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = consented, onCheckedChange = onConsentedChange)
            Spacer(Modifier.width(8.dp))
            Text(
                text = consentPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "个人 Token 只有在同时开启反代并勾选同意后，才会发送到非官方地址。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 第 3 步（可选）：Bangumi 个人同步——粘贴 Access Token。 */
@Composable
private fun BangumiPage(
    bangumiToken: String,
    onBangumiTokenChange: (String) -> Unit,
    revealSecrets: Boolean,
    onToggleReveal: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Bangumi 个人同步（可选）",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "你可以切换到合适的网络环境，打开 Bangumi Token 页面并登录，" +
            "获取个人令牌后粘贴到这里；也可以稍后在设置中使用 OAuth 登录。可留空跳过。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { uriHandler.openUri(OnboardingDefaults.BANGUMI_TOKEN_HELP_URL) }) {
        Text("打开 Bangumi Token 页面")
    }
    Text(
        text = "OAuth Access Token 有效期约 7 天，应用会在到期前尝试自动续期；手动粘贴的令牌无法自动续期。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    FlatTextField(
        value = bangumiToken,
        onValueChange = onBangumiTokenChange,
        label = { Text("Access Token（可选）") },
        singleLine = true,
        visualTransformation = if (revealSecrets) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    RevealSecretsToggle(revealSecrets = revealSecrets, onToggleReveal = onToggleReveal)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "凭据保存在设备加密存储中，仅在请求对应服务时使用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 第 4 步（可选）：AI 增强——OpenAI 兼容 API Key / Base URL / 模型名。 */
@Composable
private fun AiPage(
    aiApiKey: String,
    onAiApiKeyChange: (String) -> Unit,
    aiBaseUrl: String,
    onAiBaseUrlChange: (String) -> Unit,
    aiModel: String,
    onAiModelChange: (String) -> Unit,
    revealSecrets: Boolean,
    onToggleReveal: () -> Unit,
) {
    val mask = if (revealSecrets) VisualTransformation.None else PasswordVisualTransformation()
    Spacer(Modifier.height(16.dp))
    Text(
        text = "AI 增强（可选）",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "填入 OpenAI 兼容服务的 API Key 可启用口味匹配 / 无剧透雷达（推荐 DeepSeek）；" +
            "未填则自动回退本地规则。AI 调用会消耗你所配置服务的额度。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    FlatTextField(
        value = aiApiKey,
        onValueChange = onAiApiKeyChange,
        label = { Text("API Key（可选）") },
        singleLine = true,
        visualTransformation = mask,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    FlatTextField(
        value = aiBaseUrl,
        onValueChange = onAiBaseUrlChange,
        label = { Text("Base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    FlatTextField(
        value = aiModel,
        onValueChange = onAiModelChange,
        label = { Text("模型名（例如 gpt-5.2）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    RevealSecretsToggle(revealSecrets = revealSecrets, onToggleReveal = onToggleReveal)
}

/** 显示 / 隐藏已输入的 Token / API Key（Bangumi / AI 两步共用）。 */
@Composable
private fun RevealSecretsToggle(revealSecrets: Boolean, onToggleReveal: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PrismCorner.Badge)
            .clickable { onToggleReveal() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = revealSecrets, onCheckedChange = { onToggleReveal() })
        Spacer(Modifier.width(8.dp))
        Text(
            text = "显示我输入的 Token / API Key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 引导向导总步数：介绍 / 同意 / Bangumi / AI。 */
private const val ONBOARDING_PAGE_COUNT = 4

/** 第 3 步（索引 2）起为可选配置项，顶部提供「跳过」直接完成。 */
private const val FIRST_OPTIONAL_PAGE = 2

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    AcgCompassTheme {
        OnboardingScreen(
            state = OnboardingUiState.DEFAULT,
            onConfirm = {},
        )
    }
}

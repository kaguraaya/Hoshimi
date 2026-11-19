package com.acgcompass.feature.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.core.ui.topBarScrim
import com.acgcompass.core.ui.transparentTopBarColors

/**
 * Bangumi OAuth2 授权登录页。
 *
 * 用内嵌 [WebView] 加载 Bangumi 授权 URL，用户登录授权后 Bangumi 302 跳转到自定义 scheme 回调
 * （[com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI]）。本页在
 * [WebViewClient.shouldOverrideUrlLoading] 拦截该回调（不放行加载未知 scheme），交
 * [BangumiOAuthViewModel.onRedirect] 解析授权码并换取 token。成功后自动返回上一页。
 *
 * 选用 WebView 内拦截而非系统 deep-link：自包含、无需 Manifest 注册，且规避浏览器无法回跳应用的故障。
 */
@Composable
fun BangumiOAuthRoute(onBack: () -> Unit, viewModel: BangumiOAuthViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        // 登录成功：凭据已落盘，自动返回设置页。
        if (state is BangumiOAuthUiState.Success) onBack()
    }

    BangumiOAuthScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::start,
        isRedirect = viewModel::isRedirect,
        isAllowedNavigation = viewModel::isAllowedNavigation,
        onBlockedNavigation = viewModel::onBlockedNavigation,
        onRedirect = viewModel::onRedirect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BangumiOAuthScreen(
    state: BangumiOAuthUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    isRedirect: (String) -> Boolean,
    isAllowedNavigation: (String) -> Boolean,
    onBlockedNavigation: () -> Unit,
    onRedirect: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.topBarScrim(),
                colors = transparentTopBarColors(),
                expandedHeight = AcgTopBarHeight,
                title = { Text("用 Bangumi 登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is BangumiOAuthUiState.Loading ->
                    StatusColumn(text = "正在准备授权…", showProgress = true)

                is BangumiOAuthUiState.Exchanging ->
                    StatusColumn(text = "正在登录并保存凭据…", showProgress = true)

                is BangumiOAuthUiState.Success ->
                    StatusColumn(text = "登录成功", showProgress = false)

                is BangumiOAuthUiState.Error ->
                    ErrorColumn(
                        message = state.message,
                        retryable = state.retryable,
                        officialUrl = state.officialUrl,
                        onRetry = onRetry,
                        onBack = onBack,
                    )

                is BangumiOAuthUiState.Authorizing ->
                    OAuthWebView(
                        authorizeUrl = state.authorizeUrl,
                        isRedirect = isRedirect,
                        isAllowedNavigation = isAllowedNavigation,
                        onBlockedNavigation = onBlockedNavigation,
                        onRedirect = onRedirect,
                        onSwitchAccount = onRetry,
                    )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthWebView(
    authorizeUrl: String,
    isRedirect: (String) -> Boolean,
    isAllowedNavigation: (String) -> Boolean,
    onBlockedNavigation: () -> Unit,
    onRedirect: (String) -> Unit,
    onSwitchAccount: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            onClick = {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    onSwitchAccount()
                }
            },
        ) {
            Text("切换账号")
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    val currentWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(currentWebView, false)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString()
                            return when {
                                request?.isForMainFrame == false -> false
                                url == null -> true
                                isRedirect(url) -> {
                                    onRedirect(url)
                                    true
                                }
                                !isAllowedNavigation(url) -> {
                                    onBlockedNavigation()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                    loadUrl(authorizeUrl)
                }
            },
            update = { current ->
                if (current.url.isNullOrBlank() || current.url == "about:blank") current.loadUrl(authorizeUrl)
            },
        )
    }
}

@Composable
private fun StatusColumn(text: String, showProgress: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showProgress) CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun ErrorColumn(
    message: String,
    retryable: Boolean,
    officialUrl: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val openOfficialUrl: () -> Unit = {
        officialUrl?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = message, textAlign = TextAlign.Center)
        if (!officialUrl.isNullOrBlank()) {
            FilledTonalButton(onClick = {
                clipboard.setText(AnnotatedString(officialUrl))
            }) { Text("复制官方链接") }
            FilledTonalButton(onClick = openOfficialUrl) { Text("外部浏览器打开") }
            FilledTonalButton(onClick = onBack) { Text("改用手动 Token") }
        }
        if (retryable) {
            Button(onClick = onRetry) { Text("重试") }
        }
        FilledTonalButton(onClick = onBack) { Text("返回设置") }
    }
}

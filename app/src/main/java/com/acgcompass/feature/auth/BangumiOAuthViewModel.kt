package com.acgcompass.feature.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.security.BangumiOAuthSecurity
import com.acgcompass.domain.auth.BangumiOAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bangumi OAuth2 授权流程 UI 状态。
 */
sealed interface BangumiOAuthUiState {
    /** 正在读取用户配置的 App ID 并拼接授权 URL。 */
    data object Loading : BangumiOAuthUiState

    /** 授权 URL 就绪，WebView 引导用户在 Bangumi 登录授权。 */
    data class Authorizing(val authorizeUrl: String) : BangumiOAuthUiState

    /** 已拿到授权码，正在换取 token。 */
    data object Exchanging : BangumiOAuthUiState

    /** 换取成功并已落盘加密存储。 */
    data object Success : BangumiOAuthUiState

    /** 失败（未配置 App ID/Secret、授权被拒、换取失败等）。[retryable] 决定是否提供「重试」。 */
    data class Error(
        val message: String,
        val retryable: Boolean = true,
        val officialUrl: String? = null,
    ) : BangumiOAuthUiState
}

/**
 * Bangumi OAuth2 **授权码流程**的 ViewModel。
 *
 * 职责：
 * 1. [start]：通过 [BangumiOAuthRepository.buildAuthorization] 拼接授权 URL（带随机 `state` 防 CSRF），交 WebView 打开。
 * 2. [onRedirect]：WebView 拦截到自定义 scheme 回调后，解析 / 校验 `state` 与 `code`，
 *    通过领域仓库换取并加密保存 token，同时启用 Bangumi 源。
 *
 * **安全**：token / code / secret 仅在内存流转并写入加密存储，绝不记录日志。
 */
@HiltViewModel
class BangumiOAuthViewModel @Inject constructor(
    private val oauthRepository: BangumiOAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BangumiOAuthUiState>(BangumiOAuthUiState.Loading)
    val uiState: StateFlow<BangumiOAuthUiState> = _uiState.asStateFlow()

    /** 本次授权请求的 `state`，用于回调校验（防 CSRF）。 */
    private var expectedState: String? = null

    /** 防止 WebView 重复回调导致多次换取。 */
    private var handlingCode = false
    private var lastAuthorizeUrl: String? = null

    init {
        start()
    }

    /** 拼接授权 URL 并进入 WebView 授权态；未配置 App ID/Secret 时直接进入错误态。 */
    fun start() {
        handlingCode = false
        _uiState.value = BangumiOAuthUiState.Loading
        viewModelScope.launch {
            when (val result = oauthRepository.buildAuthorization(BangumiOAuthSecurity.newState())) {
                is AppResult.Success -> {
                    expectedState = result.data.state
                    lastAuthorizeUrl = result.data.authorizeUrl
                    _uiState.value = BangumiOAuthUiState.Authorizing(result.data.authorizeUrl)
                }

                is AppResult.Failure -> {
                    _uiState.value = BangumiOAuthUiState.Error(
                        message = result.error.cause + "（" + result.error.nextStep + "）",
                        retryable = false,
                        officialUrl = lastAuthorizeUrl,
                    )
                }
            }
        }
    }

    /** 判断 URL 是否为应用回调地址；命中时拦截且不继续加载。 */
    fun isRedirect(url: String): Boolean = BangumiOAuthSecurity.isExactRedirect(url)

    /** 只允许 Bangumi 官方域名和本次授权请求的精确 HTTPS Worker 主机。 */
    fun isAllowedNavigation(url: String): Boolean =
        BangumiOAuthSecurity.isAllowedAuthorizationNavigation(url, lastAuthorizeUrl)

    fun onBlockedNavigation() {
        _uiState.value = BangumiOAuthUiState.Error(
            message = "登录页面尝试离开受信任的授权域名，已停止加载",
            retryable = true,
            officialUrl = lastAuthorizeUrl,
        )
    }

    /** WebView 拦截到回调 URL：解析 `code`/`state`/`error`，校验后换取并落盘 token。 */
    fun onRedirect(url: String) {
        if (!isRedirect(url)) return
        if (handlingCode) return
        handlingCode = true

        val uri = runCatchingCancellable { Uri.parse(url) }.getOrNull()
        val error = uri?.getQueryParameter("error")
        val code = uri?.getQueryParameter("code")
        val returnedState = uri?.getQueryParameter("state")

        if (!error.isNullOrBlank()) {
            _uiState.value = BangumiOAuthUiState.Error(
                message = "授权被拒绝或已取消（$error）",
                officialUrl = lastAuthorizeUrl,
            )
            return
        }
        if (code.isNullOrBlank()) {
            _uiState.value = BangumiOAuthUiState.Error(
                message = "回调未包含授权码，请重试",
                officialUrl = lastAuthorizeUrl,
            )
            return
        }
        val verifiedState = expectedState
        if (!BangumiOAuthSecurity.stateMatches(verifiedState, returnedState)) {
            _uiState.value = BangumiOAuthUiState.Error(
                message = "state 校验失败（可能的 CSRF 风险），已中止登录",
                officialUrl = lastAuthorizeUrl,
            )
            return
        }
        expectedState = null

        _uiState.value = BangumiOAuthUiState.Exchanging
        viewModelScope.launch {
            when (val result = oauthRepository.exchangeCodeAndPersist(code, requireNotNull(verifiedState))) {
                is AppResult.Success -> {
                    _uiState.value = BangumiOAuthUiState.Success
                }

                is AppResult.Failure -> {
                    _uiState.value = BangumiOAuthUiState.Error(
                        message = result.error.cause + "（" + result.error.nextStep + "）",
                        officialUrl = lastAuthorizeUrl,
                    )
                }
            }
        }
    }
}

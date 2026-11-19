package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.acgcompass.core.designsystem.AcgUndoBar

/**
 * 首启引导门控：DataStore 表明尚未展示引导时导航到引导页。
 * 初始值为 false，老用户不会闪现引导页（见 OnboardingViewModel 注释）。
 */
@Composable
internal fun OnboardingGate(shouldShow: Boolean, currentRoute: String?, navController: NavHostController) {
    LaunchedEffect(shouldShow) {
        if (shouldShow && currentRoute != AppDestination.Onboarding.route) {
            navController.navigate(AppDestination.Onboarding.route) {
                launchSingleTop = true
            }
        }
    }
}

/** 根部的提示条宿主；Bangumi 登录失效等一次性提示到达时显示。 */
@Composable
internal fun rememberAuthNoticeSnackbar(notice: String?, onShown: () -> Unit): SnackbarHostState {
    val state = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            state.showSnackbar(it)
            onShown()
        }
    }
    return state
}

/** 撤销提示条（浮在底栏上方）和换账号对话框。 */
@Composable
internal fun ShellOverlays(
    shell: AppShellViewModel,
    snackbarHostState: SnackbarHostState,
    bottomSpace: Dp,
    modifier: Modifier = Modifier,
) {
    val undo by shell.undo.collectAsStateWithLifecycle()
    val notice by shell.notice.collectAsStateWithLifecycle()
    val accountSwitch by shell.accountSwitch.collectAsStateWithLifecycle()
    AcgUndoBar(
        message = undo?.message,
        key = undo,
        onUndo = shell::onUndo,
        onTimeout = { undo?.let(shell::onUndoTimeout) },
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = bottomSpace + 12.dp),
    )
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            shell.consumeNotice()
        }
    }
    accountSwitch?.let { (from, to) ->
        AccountSwitchDialog(from = from, to = to, onChoose = shell::onResolveAccountSwitch)
    }
}

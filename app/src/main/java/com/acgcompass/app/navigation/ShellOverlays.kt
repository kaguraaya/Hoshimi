package com.acgcompass.app.navigation

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.acgcompass.core.designsystem.AcgUndoBar
import com.acgcompass.data.backup.toInfo
import com.acgcompass.data.local.migration.DatabaseHealth
import com.acgcompass.feature.safety.SafeModeCallbacks
import com.acgcompass.feature.safety.SafeModeScreen
import kotlinx.coroutines.launch

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

/**
 * 数据库还在打开 / 自检时不放页面（页面一进来就读数据库），只画页面底色；打不开时整个界面换成安全模式。
 * 返回 true 表示可以显示正常页面。
 */
@Composable
internal fun databaseReady(shell: AppShellViewModel, modifier: Modifier): Boolean {
    val health by shell.health.collectAsStateWithLifecycle()
    return when (val state = health) {
        DatabaseHealth.Checking -> {
            Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            false
        }
        is DatabaseHealth.Failed -> {
            SafeModeHost(shell, state, modifier)
            false
        }
        else -> true
    }
}

/** 页面里发一次性底部提示用的回调（见 [com.acgcompass.core.ui.LocalAppMessenger]）。 */
@Composable
internal fun rememberMessenger(hostState: SnackbarHostState): (String) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(hostState) { { message -> scope.launch { hostState.showSnackbar(message) } } }
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
    val health by shell.health.collectAsStateWithLifecycle()
    val beforeUpgrade by shell.autoBackupBeforeUpgrade.collectAsStateWithLifecycle()
    (health as? DatabaseHealth.DataMayBeMissing)?.let { state ->
        DataMayBeMissingDialog(
            details = state.details,
            hasAutoBackup = beforeUpgrade != null,
            onRestore = shell::onRestoreMissingData,
            onDismiss = shell::onDismissDataWarning,
        )
    }
}

@Composable
internal fun SafeModeHost(shell: AppShellViewModel, state: DatabaseHealth.Failed, modifier: Modifier) {
    val activity = LocalContext.current as? Activity
    val latest by shell.autoBackupBeforeUpgrade.collectAsStateWithLifecycle()
    SafeModeScreen(
        errorMessage = state.message,
        upgradeBackup = state.backup?.toInfo(),
        latestAutoBackup = latest,
        actions = SafeModeCallbacks(
            onExportUpgradeBackup = shell::onExportUpgradeBackup,
            onFreshStart = shell::onFreshStart,
            onExit = { activity?.finishAndRemoveTask() },
        ),
        modifier = modifier,
    )
}

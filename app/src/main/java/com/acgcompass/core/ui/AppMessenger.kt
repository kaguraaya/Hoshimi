package com.acgcompass.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 显示一条底部提示（应用根部的 [com.acgcompass.core.designsystem.AcgSnackbarHost]）。
 * 页面里需要「已复制」「同步完成」这类一次性提示时用它，外观和撤销提示条一致，不用系统 Toast。
 */
val LocalAppMessenger = staticCompositionLocalOf<(String) -> Unit> { {} }

package com.acgcompass.feature.safety

import android.net.Uri

/** 安全模式页的操作。 */
data class SafeModeCallbacks(
    val onExportUpgradeBackup: (Uri) -> Unit,
    val onFreshStart: (restoreAutoBackup: Boolean) -> Unit,
    val onExit: () -> Unit,
)

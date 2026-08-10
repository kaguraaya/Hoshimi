package com.acgcompass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.acgcompass.app.navigation.AcgApp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The single host Activity for the whole app .
 *
 * Hosts the Material 3 theme and the Compose navigation graph ([AcgApp]): a 5-tab bottom
 * navigation (首页 / 发现 / 待补池 / 时光机 / 我的) plus nested routes (Detail / Settings /
 * Import / Recommender) and first-launch onboarding gating .
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装系统开屏（androidx core-splashscreen）——必须在 super.onCreate 之前。
        // 开屏动效（八芒星旋转 + 淡入放大）由 Theme.Hoshimi.Splash 的 windowSplashScreenAnimatedIcon
        // （@drawable/avd_splash）在**系统冷启动窗口**内直接播放，不再用 Compose 手写覆盖层——从根上
        // 消除「首帧与 Compose 争抢主线程掉帧」与「方形静态图标闪现」。setContent 直接托管 AcgApp。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsDataStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by settingsDataStore.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val lightPreset by settingsDataStore.lightThemePreset
                .collectAsStateWithLifecycle(initialValue = com.acgcompass.domain.model.ThemePreset.OCEAN)
            val darkPreset by settingsDataStore.darkThemePreset
                .collectAsStateWithLifecycle(initialValue = com.acgcompass.domain.model.ThemePreset.OCEAN)
            val predictiveBackEnabled by settingsDataStore.predictiveBackEnabled
                .collectAsStateWithLifecycle(initialValue = true)
            val bangumiAuthNotice by settingsDataStore.bangumiAuthNotice
                .collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AcgCompassTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
                preset = if (darkTheme) darkPreset else lightPreset,
            ) {
                val supportsPredictiveBack =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                AcgApp(
                    predictiveBackEnabled = predictiveBackEnabled && supportsPredictiveBack,
                    bangumiAuthNotice = bangumiAuthNotice,
                    onBangumiAuthNoticeShown = {
                        lifecycleScope.launch { settingsDataStore.clearBangumiAuthNotice() }
                    },
                )
            }
        }
    }
}

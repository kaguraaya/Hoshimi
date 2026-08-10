package com.acgcompass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acgcompass.feature.backlog.BacklogRoute
import com.acgcompass.feature.cache.CacheItemCleanupScreen
import com.acgcompass.feature.cache.CacheManagementScreen
import com.acgcompass.feature.detail.DETAIL_ARG_WORK_ID
import com.acgcompass.feature.detail.DetailRoute
import com.acgcompass.feature.detail.detailRoute
import com.acgcompass.feature.discover.DiscoverRoute
import com.acgcompass.feature.home.HomeRoute
import com.acgcompass.feature.imports.ImportRoute
import com.acgcompass.feature.library.LIBRARY_ARG_TAB
import com.acgcompass.feature.library.MyLibraryRoute
import com.acgcompass.feature.library.libraryRoute
import com.acgcompass.feature.mine.AboutScreen
import com.acgcompass.feature.mine.MineRoute
import com.acgcompass.feature.onboarding.OnboardingRoute
import com.acgcompass.feature.onboarding.OnboardingViewModel
import com.acgcompass.feature.recommender.RECOMMENDER_ARG_TAGS
import com.acgcompass.feature.recommender.RecommenderRoute
import com.acgcompass.feature.recommender.recommenderRoute
import com.acgcompass.feature.settings.SettingsScreen
import com.acgcompass.feature.settings.SettingsSourceScreen
import com.acgcompass.feature.taste.TasteProfileRoute
import com.acgcompass.feature.timemachine.TimeMachineRoute

/**
 * 应用导航根。
 *
 * 由单 Activity 调用：装配 [Scaffold]（底部五栏导航）+ [AppNavHost]，并根据
 * [OnboardingViewModel.shouldShowOnboarding] 在首次启动时把用户引导到引导页。
 *
 * - 底栏仅在顶层目的地（五栏）显示；进入详情 / 设置 / 导入 / 推荐器 / 引导等嵌套路由时隐藏。
 * - 五栏之间切换维持自然返回栈：切换时 `popUpTo` 起始页并 `saveState`，避免栈无限增长。
 */
@Composable
@Suppress("LongParameterList")
fun AcgApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    predictiveBackEnabled: Boolean = true,
    bangumiAuthNotice: String? = null,
    onBangumiAuthNoticeShown: () -> Unit = {},
) {
    val shouldShowOnboarding by onboardingViewModel.shouldShowOnboarding.collectAsStateWithLifecycle()

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentTopLevel = TopLevelDestination.fromRoute(currentRoute)

    // 首启引导门控：当 DataStore 表明尚未展示引导时，导航到引导页。
    // 初始值为 false，老用户不会闪现引导页（见 OnboardingViewModel 注释）。
    LaunchedEffect(shouldShowOnboarding) {
        if (shouldShowOnboarding && currentRoute != AppDestination.Onboarding.route) {
            navController.navigate(AppDestination.Onboarding.route) {
                launchSingleTop = true
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bangumiAuthNotice) {
        bangumiAuthNotice?.let {
            snackbarHostState.showSnackbar(it)
            onBangumiAuthNoticeShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 统一 inset 单一来源模型。
        // 外层 Scaffold 用**默认** contentWindowInsets（消费状态栏 + 导航栏 + 底栏高度），
        // 把消费后的 inset 通过 innerPadding 下发，并用 consumeWindowInsets 标记已消费，
        // 使各页 TopAppBar / 内容统一用零 inset，不再各自消费状态栏（之前各页独立消费状态栏
        // 时机不一致，导致首张卡片/封面贴顶或被裁切）。
        bottomBar = {
            if (TopLevelDestination.isTopLevelRoute(currentRoute)) {
                AcgBottomNavigation(
                    currentDestination = currentTopLevel,
                    onSelect = { tab -> navController.navigateToTopLevel(tab) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            predictiveBackEnabled = predictiveBackEnabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

/**
 * 应用 [NavHost]：声明五栏占位屏与嵌套路由（详情 / 设置 / 导入 / 推荐器 / 引导）。
 *
 * 起始目的地固定为首页；首启引导通过 [AcgApp] 的门控按需进入，完成后回到首页。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    predictiveBackEnabled: Boolean = true,
) {
    val activeEntry by navController.currentBackStackEntryAsState()
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier,
        // 顶层栏目之间无方向语义，直接切换；只要一端是嵌套页，就使用对称的整屏 push/pop 位移。
        // 四个 transition 必须使用相同的双端判断，确保进入和返回动画成对且页面不会重叠。
        enterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                EnterTransition.None
            } else {
                slideInHorizontally(tween(NAV_SLIDE_MS)) { it }
            }
        },
        exitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                ExitTransition.None
            } else {
                slideOutHorizontally(tween(NAV_SLIDE_MS)) { -it }
            }
        },
        popEnterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                EnterTransition.None
            } else {
                slideInHorizontally(tween(NAV_SLIDE_MS)) { -it }
            }
        },
        popExitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                ExitTransition.None
            } else {
                slideOutHorizontally(tween(NAV_SLIDE_MS)) { it }
            }
        },
    ) {
        // region 底部五栏
        composable(AppDestination.Home.route) {
            HomeRoute(
                onOpenRecommender = { presetTags ->
                    navController.navigate(recommenderRoute(presetTags))
                },
                // 搜索专页尚未接入，暂时复用发现页。
                onOpenSearch = {
                    navController.navigate(AppDestination.Discover.route)
                },
                onOpenImport = {
                    navController.navigate(AppDestination.Import.route)
                },
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onOpenSettings = {
                    navController.navigate(AppDestination.Settings.route)
                },
                onOpenHomeModules = {
                    navController.navigate(AppDestination.HomeModules.route)
                },
                onOpenSchedule = {
                    navController.navigate(AppDestination.Schedule.route)
                },
            )
        }
        composable(AppDestination.Discover.route) {
            DiscoverRoute(
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onOpenExploreQueue = {
                    navController.navigate(AppDestination.ExploreQueue.route)
                },
                onOpenVisualNovels = {
                    navController.navigate(AppDestination.VisualNovels.route)
                },
            )
        }
        composable(AppDestination.VisualNovels.route) {
            com.acgcompass.feature.visualnovel.VisualNovelRoute(
                onBack = { navController.popBackStack() },
                onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
            )
        }
        composable(AppDestination.ExploreQueue.route) {
            com.acgcompass.feature.explore.ExploreQueueRoute(
                onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Backlog.route) {
            BacklogRoute(
                onOpenDetail = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onGoImport = {
                    navController.navigate(AppDestination.Import.route)
                },
                onGoSearch = {
                    navController.navigate(AppDestination.Discover.route)
                },
                onOpenDustMuseum = {
                    navController.navigate(AppDestination.DustMuseum.route)
                },
            )
        }
        composable(AppDestination.TimeMachine.route) {
            TimeMachineRoute(
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
            )
        }
        composable(AppDestination.Mine.route) {
            MineRoute(
                onNavigateToSettings = {
                    navController.navigate(AppDestination.Settings.route)
                },
                onOpenPrivacySettings = {
                    navController.navigate(settingsSourceRoute(com.acgcompass.feature.settings.PRIVACY_SOURCE_KEY))
                },
                onOpenTasteProfile = {
                    navController.navigate(AppDestination.TasteProfile.route)
                },
                onOpenAbout = {
                    navController.navigate(AppDestination.About.route)
                },
                onOpenLibrary = { tab ->
                    navController.navigate(libraryRoute(tab))
                },
            )
        }
        // endregion

        // region 嵌套路由
        nestedRoutes(navController)
        // endregion
    }
    // 在 NavHost 之后注册，使关闭预测性返回时由普通回调接管嵌套页面。
    BackHandler(
        enabled = !predictiveBackEnabled && !TopLevelDestination.isTopLevelRoute(activeEntry?.destination?.route),
    ) {
        navController.popBackStack()
    }
}

/**
 * 注册非底栏的嵌套路由；尚未实现的目的地使用明确的占位屏。
 */
private fun NavGraphBuilder.nestedRoutes(navController: NavHostController) {
    composable(
        route = "vndb_producer/{producerId}?producerName={producerName}",
        arguments = listOf(
            navArgument("producerId") { type = NavType.StringType },
            navArgument("producerName") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        com.acgcompass.feature.visualnovel.VndbProducerRoute(
            onBack = { navController.popBackStack() },
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
        )
    }
    composable(
        route = AppDestination.Detail.route,
        arguments = listOf(
            navArgument(DETAIL_ARG_WORK_ID) { type = NavType.StringType },
        ),
    ) {
        // workId 由路由参数经 SavedStateHandle 注入 DetailViewModel（见 DetailViewModel）。
        DetailRoute(
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
            onOpenVndbProducer = { producerId, producerName ->
                val encodedName = android.net.Uri.encode(producerName)
                navController.navigate("vndb_producer/$producerId?producerName=$encodedName")
            },
        )
    }
    composable(AppDestination.Import.route) {
        ImportRoute(onBack = { navController.popBackStack() })
    }
    composable(
        route = AppDestination.Recommender.route,
        arguments = listOf(
            navArgument(RECOMMENDER_ARG_TAGS) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // presetTags 由路由参数经 SavedStateHandle 注入 RecommenderViewModel（首页今日状态预填）。
        RecommenderRoute(
            onOpenWork = { workId ->
                navController.navigate(detailRoute(workId))
            },
        )
    }
    composable(
        route = "${AppDestination.Settings.route}?section={section}",
        arguments = listOf(
            navArgument("section") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        SettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            expandPrivacy = entry.arguments?.getString("section") == "privacy",
            onClearCache = { navController.navigate(AppDestination.CacheManagement.route) },
            onOpenCacheManagement = { navController.navigate(AppDestination.CacheManagement.route) },
            onOpenSource = { sourceKey ->
                navController.navigate(settingsSourceRoute(sourceKey))
            },
        )
    }
    composable(
        route = AppDestination.SettingsSource.route,
        arguments = listOf(
            navArgument("source") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        SettingsSourceScreen(
            sourceKey = entry.arguments?.getString("source").orEmpty(),
            onNavigateBack = { navController.popBackStack() },
            onBangumiLogin = { navController.navigate(AppDestination.BangumiOAuth.route) },
            onClearCache = { navController.navigate(AppDestination.CacheManagement.route) },
        )
    }
    composable(AppDestination.CacheManagement.route) {
        CacheManagementScreen(
            onBack = { navController.popBackStack() },
            onOpenItemCleanup = { navController.navigate(AppDestination.CacheItems.route) },
            onResetComplete = {
                navController.navigate(AppDestination.Onboarding.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
        )
    }
    composable(AppDestination.CacheItems.route) {
        CacheItemCleanupScreen(onBack = { navController.popBackStack() })
    }
    composable(AppDestination.BangumiOAuth.route) {
        com.acgcompass.feature.auth.BangumiOAuthRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.Onboarding.route) {
        OnboardingRoute(
            onFinished = {
                // 引导完成：返回首页并清除引导页，避免回退又回到引导。
                navController.navigate(AppDestination.Home.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(AppDestination.About.route) {
        AboutScreen(
            onBack = { navController.popBackStack() },
            onOpenDataSources = { navController.navigate(AppDestination.AboutDataSources.route) },
            onOpenLicenses = { navController.navigate(AppDestination.AboutLicenses.route) },
            onOpenDisclaimer = { navController.navigate(AppDestination.AboutDisclaimer.route) },
        )
    }
    composable(AppDestination.AboutDataSources.route) {
        com.acgcompass.feature.mine.AboutDocumentScreen(
            title = "数据来源",
            paragraphs = com.acgcompass.feature.mine.ABOUT_DATA_SOURCE_PARAGRAPHS,
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.AboutLicenses.route) {
        com.acgcompass.feature.mine.AboutLicenseScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.AboutDisclaimer.route) {
        com.acgcompass.feature.mine.AboutDocumentScreen(
            title = "免责声明",
            paragraphs = com.acgcompass.feature.mine.ABOUT_DISCLAIMER_PARAGRAPHS,
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.HomeModules.route) {
        com.acgcompass.feature.home.HomeModulesRoute(onBack = { navController.popBackStack() })
    }
    composable(AppDestination.Schedule.route) {
        com.acgcompass.feature.home.BroadcastScheduleRoute(
            onBack = { navController.popBackStack() },
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
        )
    }
    composable(AppDestination.DustMuseum.route) {
        com.acgcompass.feature.backlog.DustMuseumRoute(
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.TasteProfile.route) {
        TasteProfileRoute(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(AppDestination.Settings.route) },
            onOpenManualReview = { navController.navigate(AppDestination.TagManualReview.route) },
        )
    }
    composable(AppDestination.TagManualReview.route) {
        com.acgcompass.feature.taste.TagManualReviewRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = AppDestination.Library.route,
        arguments = listOf(
            navArgument(LIBRARY_ARG_TAB) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // 初始 Tab 由路由参数经 SavedStateHandle 注入 MyLibraryViewModel。
        MyLibraryRoute(
            onBack = { navController.popBackStack() },
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
        )
    }
}

/**
 * 切换底部五栏的标准导航行为：
 * - `popUpTo` 图的起始目的地并 `saveState`，避免在栈中堆叠多个顶层页面。
 * - `launchSingleTop` 防止重复入栈同一页面。
 * - `restoreState` 恢复已保存的滚动与选择状态。
 */
private fun NavHostController.navigateToTopLevel(tab: TopLevelDestination) {
    navigate(tab.route) {
        // 始终回到图的起始页（首页）之上重建目标栏，确保每个 Tab 进入正确页面。
        // 刻意不使用 saveState/restoreState：避免「点首页却回到发现页」的状态错配；
        // 各页内部状态（如搜索结果）由各自 ViewModel / 仓库缓存负责保留，不依赖导航返回栈。
        popUpTo(graph.startDestinationId) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

/** 下钻页（详情等）滑入 / 滑出时长（ms）：快速利落承载整屏位移，不拖沓（纯滑动、无 fade）。 */
private const val NAV_SLIDE_MS = 220

/** 该目的地是否为底部五栏之一（顶层）。用于区分「同级切换（瞬切）」与「下钻（滑入）」动画。 */
private fun NavBackStackEntry.isTopLevel(): Boolean = TopLevelDestination.isTopLevelRoute(destination.route)

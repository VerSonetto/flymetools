package com.karen.flymetool.ui.screen

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.ScopedApp

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object About : Screen("about")
    data object AppDetail : Screen("app_detail/{packageName}") {
        fun createRoute(packageName: String) = "app_detail/$packageName"
    }
    data object AppSelect : Screen("app_select/{packageName}/{featureKey}") {
        fun createRoute(packageName: String, featureKey: String) = "app_select/$packageName/$featureKey"
    }
}

private const val TRANSITION_DURATION = 420
private const val BACKGROUND_PARALLAX_DIVISOR = 4

// 接近 iOS 页面切换的缓出曲线：快速响应手势，末段平滑停靠。
private val IosTransitionEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(TRANSITION_DURATION, easing = IosTransitionEasing)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / BACKGROUND_PARALLAX_DIVISOR },
                animationSpec = tween(TRANSITION_DURATION, easing = IosTransitionEasing)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth / BACKGROUND_PARALLAX_DIVISOR },
                animationSpec = tween(TRANSITION_DURATION, easing = IosTransitionEasing)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(TRANSITION_DURATION, easing = IosTransitionEasing)
            )
        }
    ) {
        composable(route = Screen.Home.route) {
            HomeScreen(
                onAppClick = { app ->
                    navController.navigate(Screen.AppDetail.createRoute(app.packageName))
                },
                onAboutClick = { navController.navigate(Screen.About.route) }
            )
        }
        composable(route = Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(route = Screen.AppDetail.route) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            val app = remember(packageName) {
                AppData.getScopedApps(context).find { it.packageName == packageName } ?: ScopedApp(
                    packageName = packageName,
                    name = packageName
                )
            }
            AppDetailScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }
        composable(route = Screen.AppSelect.route) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            val featureKey = backStackEntry.arguments?.getString("featureKey") ?: return@composable
            AppSelectScreen(
                packageName = packageName,
                featureKey = featureKey,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

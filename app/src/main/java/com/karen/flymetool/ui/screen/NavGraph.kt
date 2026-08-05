package com.karen.flymetool.ui.screen

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.ScopedApp

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object AppDetail : Screen("app_detail/{packageName}") {
        fun createRoute(packageName: String) = "app_detail/$packageName"
    }
    data object FeatureGroup : Screen("app_feature_group/{packageName}/{groupName}") {
        fun createRoute(packageName: String, groupName: String): String {
            return "app_feature_group/$packageName/${Uri.encode(groupName)}"
        }
    }
    data object AppSelect : Screen("app_select/{packageName}/{featureKey}") {
        fun createRoute(packageName: String, featureKey: String) = "app_select/$packageName/$featureKey"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { ScreenTransitions.enterFromEnd() },
        exitTransition = { ScreenTransitions.exitToStart() },
        popEnterTransition = { ScreenTransitions.enterFromStart() },
        popExitTransition = { ScreenTransitions.exitToEnd() }
    ) {
        composable(route = Screen.Home.route) {
            MainScreen(
                onAppClick = { app ->
                    navController.navigate(Screen.AppDetail.createRoute(app.packageName))
                }
            )
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
        composable(
            route = Screen.FeatureGroup.route,
            arguments = listOf(
                navArgument("packageName") { type = NavType.StringType },
                navArgument("groupName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            val groupNameEncoded = backStackEntry.arguments?.getString("groupName") ?: return@composable
            val groupName = Uri.decode(groupNameEncoded)
            val app = remember(packageName) {
                AppData.getScopedApps(context).find { it.packageName == packageName } ?: ScopedApp(
                    packageName = packageName,
                    name = packageName
                )
            }
            FeatureGroupScreen(
                app = app,
                groupName = groupName,
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

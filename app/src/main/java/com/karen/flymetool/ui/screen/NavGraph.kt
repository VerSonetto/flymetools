package com.karen.flymetool.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.ScopedApp

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object AppDetail : Screen("app_detail/{packageName}") {
        fun createRoute(packageName: String) = "app_detail/$packageName"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onAppClick = { app ->
                    navController.navigate(Screen.AppDetail.createRoute(app.packageName))
                }
            )
        }
        composable(Screen.AppDetail.route) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
            val apps = AppData.getScopedApps(context)
            val app = apps.find { it.packageName == packageName } ?: ScopedApp(
                packageName = packageName,
                name = packageName
            )
            AppDetailScreen(
                app = app,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

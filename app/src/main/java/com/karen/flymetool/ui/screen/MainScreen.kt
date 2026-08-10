package com.karen.flymetool.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.karen.flymetool.data.ScopedApp
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(
    onAppClick: (ScopedApp) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val contentInsets = WindowInsets.systemBars.exclude(WindowInsets.navigationBars)

    BackHandler(enabled = selectedTab != MainTab.HOME) {
        selectedTab = MainTab.HOME
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = contentInsets,
        floatingToolbar = {
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = selectedTab == MainTab.HOME,
                    onClick = { selectedTab = MainTab.HOME },
                    icon = MiuixIcons.Home,
                    label = "首页"
                )
                FloatingNavigationBarItem(
                    selected = selectedTab == MainTab.ABOUT,
                    onClick = { selectedTab = MainTab.ABOUT },
                    icon = MiuixIcons.Info,
                    label = "关于"
                )
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter
    ) { paddingValues ->
        MainContent(
            selectedTab = selectedTab,
            onAppClick = onAppClick,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
private fun MainContent(
    selectedTab: MainTab,
    onAppClick: (ScopedApp) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = selectedTab,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            ScreenTransitions.tabContentTransform(
                forward = targetState.ordinal > initialState.ordinal
            )
        },
        label = "main_tab"
    ) { tab ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {
            when (tab) {
                MainTab.HOME -> HomeScreen(
                    onAppClick = onAppClick,
                    modifier = Modifier.fillMaxSize()
                )
                MainTab.ABOUT -> AboutScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private enum class MainTab {
    HOME, ABOUT
}

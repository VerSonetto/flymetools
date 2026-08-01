package com.karen.flymetool

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.screen.AppNavHost
import com.karen.flymetool.ui.theme.FlymeToolTheme
import com.karen.flymetool.util.GithubAvatarLoader

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false

        // 创建/fix复 prefs 可读性，避免 Hook 侧 XSharedPreferences 读不到配置。
        PrefsHelper.warmup(applicationContext)

        // 在首屏显示期间提前获取头像，进入关于页时直接使用缓存。
        GithubAvatarLoader.preload()

        setContent {
            FlymeToolTheme {
                AppNavHost()
            }
        }
    }
}

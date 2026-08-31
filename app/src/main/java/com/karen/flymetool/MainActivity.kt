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

        // 建本地配置镜像并绑定 libxposed service（绑定后切换远程偏好，Hook 侧实时读取）。
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

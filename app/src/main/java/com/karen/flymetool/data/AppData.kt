package com.karen.flymetool.data

import android.content.Context
import android.content.pm.PackageManager

object AppData {

    private val rawApps = listOf(
        ScopedApp(packageName = "com.android.systemui", name = "系统界面"),
        ScopedApp(packageName = "com.android.settings", name = "设置"),
        ScopedApp(packageName = "com.android.packageinstaller", name = "软件包安装程序"),
        ScopedApp(packageName = "android", name = "系统服务"),
        ScopedApp(packageName = "com.meizu.customizecenter", name = "主题美化"),
        ScopedApp(packageName = "com.meizu.flyme.launcher", name = "桌面"),
        ScopedApp(packageName = "com.android.mms", name = "信息"),
        ScopedApp(packageName = "com.meizu.share", name = "互传"),
        ScopedApp(packageName = "com.meizu.suggestion", name = "Aicy 建议")
    )

    fun getScopedApps(context: Context): List<ScopedApp> {
        val pm = context.packageManager
        return rawApps.map { app ->
            try {
                val info = pm.getApplicationInfo(app.packageName, 0)
                val label = pm.getApplicationLabel(info).toString()
                app.copy(name = label)
            } catch (e: PackageManager.NameNotFoundException) {
                app
            }
        }
    }

    val features = mapOf(
        "com.android.systemui" to listOf(
            HookFeature(
                key = "statusbar_weekday",
                label = "状态栏显示星期几",
                description = "在状态栏时间左侧显示当前星期"
            ),
            HookFeature(
                key = "power_display",
                label = "状态栏显示功率",
                description = "在状态栏显示当前设备功率（充电/放电）"
            ),
            HookFeature(
                key = "hide_gesture_bar",
                label = "隐藏底部手势条",
                description = "隐藏底部导航栏的手势指示条"
            ),
            HookFeature(
                key = "notification_card_radius",
                label = "通知卡片圆角",
                description = "自定义通知中心通知卡片的圆角大小"
            ),
            HookFeature(
                key = "ticker_click",
                label = "点击滚动消息跳转",
                description = "点击状态栏滚动通知消息跳转到对应应用（开启后滚动消息区域无法下拉）"
            ),
            HookFeature(
                key = "connection_rate_low_speed_hide",
                label = "低速隐藏网速",
                description = "当网速低于设定值时自动隐藏网速指示器"
            ),
            HookFeature(
                key = "hide_keyguard_flashlight",
                label = "隐藏锁屏手电筒",
                description = "隐藏锁屏界面左下角的手电筒快捷方式"
            ),
            HookFeature(
                key = "hide_keyguard_camera",
                label = "隐藏锁屏相机",
                description = "隐藏锁屏界面右下角的相机快捷方式"
            ),
            HookFeature(
                key = "hide_charging_animation",
                label = "隐藏充电动画",
                description = "插入充电器时不显示充电动画"
            ),
            HookFeature(
                key = "pulldown_area_ratio",
                label = "下拉区域比例",
                description = "自定义状态栏下拉触发通知面板和控制中心的区域比例"
            ),
            HookFeature(
                key = "notification_icon_limit",
                label = "通知图标数量限制",
                description = "限制状态栏显示的通知图标数量"
            ),
            HookFeature(
                key = "show_data_sim_only",
                label = "仅显示上网卡信号",
                description = "双卡时仅显示当前用于数据上网的 SIM 卡信号栏"
            ),
            HookFeature(
                key = "aod_lyric",
                label = "AOD 显示歌词",
                description = "在熄屏 AOD 界面显示当前播放的歌词"
            ),
            HookFeature(
                key = "notification_manage",
                label = "解除通知管理限制",
                description = "解除系统通知不支持管理的限制，允许管理所有系统通知"
            ),
            HookFeature(
                key = "hide_status_bar_icon",
                label = "隐藏状态栏图标",
                description = "选择要隐藏的状态栏图标，隐藏后不会显示在状态栏上"
            ),
            HookFeature(
                key = "app_icon_notification",
                label = "通知图标使用应用图标",
                description = "将状态栏通知小图标替换为应用桌面图标"
            )
        ),
        "com.android.settings" to listOf(
            HookFeature(
                key = "never_lock_screen",
                label = "永不锁屏",
                description = "在自动锁屏选项中添加「永不锁屏」选项"
            ),
            HookFeature(
                key = "force_notification_enable",
                label = "强制开启通知开关",
                description = "强制开启所有应用的通知开关权限，解除系统对通知开关的限制"
            )
        ),
        "com.android.packageinstaller" to listOf(
            HookFeature(
                key = "skip_install_scan",
                label = "跳过安装扫描",
                description = "跳过安装应用时的病毒扫描和应用中心查询流程"
            ),
            HookFeature(
                key = "auto_install",
                label = "自动安装",
                description = "跳过扫描后自动进入安装，无需手动点击安装按钮",
                dependsOn = "skip_install_scan"
            )
        ),
        "android" to listOf(
            HookFeature(
                key = "force_super_stereo",
                label = "强制立体音效",
                description = "让扬声器立体音效在所有场景下生效，不限于游戏和横屏视频"
            )
        ),
        "com.meizu.customizecenter" to listOf(
            HookFeature(
                key = "force_free_theme",
                label = "主题免费下载",
                description = "绕过付费检查，所有主题均可直接免费下载"
            ),
            HookFeature(
                key = "force_free_font",
                label = "字体免费下载",
                description = "绕过付费检查，所有字体均可直接免费下载"
            )
        ),
        "com.meizu.flyme.launcher" to listOf(
            HookFeature(
                key = "task_card_radius",
                label = "任务卡片圆角",
                description = "自定义最近任务卡片的圆角大小"
            ),
            HookFeature(
                key = "task_blur_intensity",
                label = "最近任务模糊强度",
                description = "自定义进入最近任务时背景模糊的强度"
            ),
            HookFeature(
                key = "memory_display",
                label = "最近任务显示内存",
                description = "在最近任务界面右上角显示内存占用信息"
            )
        ),
        "com.android.mms" to listOf(
            HookFeature(
                key = "auto_copy_verify_code",
                label = "自动复制验证码",
                description = "收到验证码短信时自动复制验证码到剪贴板"
            )
        ),
        "com.meizu.share" to listOf(
            HookFeature(
                key = "auto_accept_share",
                label = "自动接收互传文件",
                description = "收到互传或蓝牙传文件请求时自动确认接收，无需手动点击"
            )
        ),
        "com.meizu.suggestion" to listOf(
            HookFeature(
                key = "custom_browser",
                label = "自定义浏览器",
                description = "自定义 Aicy 建议打开链接时使用的浏览器"
            )
        )
    )

    fun getFeatures(packageName: String): List<HookFeature> {
        return features[packageName] ?: emptyList()
    }
}

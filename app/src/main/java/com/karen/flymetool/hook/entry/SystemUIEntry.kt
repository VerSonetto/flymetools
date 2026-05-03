package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.systemui.AODLyricHook
import com.karen.flymetool.hook.feature.systemui.ConnectionRateLowSpeedHideHook
import com.karen.flymetool.hook.feature.systemui.HideChargingAnimationHook
import com.karen.flymetool.hook.feature.systemui.HideGestureBarHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardShortcutsHook
import com.karen.flymetool.hook.feature.systemui.NotificationCardRadiusHook
import com.karen.flymetool.hook.feature.systemui.NotificationIconLimitHook
import com.karen.flymetool.hook.feature.systemui.NotificationManageHook
import com.karen.flymetool.hook.feature.systemui.PowerDisplayHook
import com.karen.flymetool.hook.feature.systemui.PulldownAreaRatioHook
import com.karen.flymetool.hook.feature.systemui.ShowDataSimOnlyHook
import com.karen.flymetool.hook.feature.systemui.StatusBarClockHook
import com.karen.flymetool.hook.feature.systemui.TickerClickHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 系统界面作用域 Hook 入口
 */
object SystemUIEntry : HookEntry {
    override val targetPackage = "com.android.systemui"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 状态栏星期
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "statusbar_weekday")) {
            val formatValue = XposedPrefs.getFeatureValue(lpparam, targetPackage, "statusbar_weekday", 0)
            StatusBarClockHook.handleLoadPackage(lpparam, formatValue)
        }

        // 功率显示
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "power_display")) {
            PowerDisplayHook.handleLoadPackage(lpparam)
        }

        // 隐藏手势条
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "hide_gesture_bar")) {
            HideGestureBarHook.handleLoadPackage(lpparam)
        }

        // 通知卡片圆角
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "notification_card_radius")) {
            val radiusValue = XposedPrefs.getFeatureValue(lpparam, targetPackage, "notification_card_radius", 24)
            NotificationCardRadiusHook.handleLoadPackage(lpparam, radiusValue)
        }

        // 通知点击
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "ticker_click")) {
            TickerClickHook.handleLoadPackage(lpparam)
        }

        // 低速隐藏
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "connection_rate_low_speed_hide")) {
            val thresholdValue = XposedPrefs.getFeatureValue(lpparam, targetPackage, "connection_rate_low_speed_hide", 10)
            ConnectionRateLowSpeedHideHook.handleLoadPackage(lpparam, thresholdValue)
        }

        // 隐藏锁屏快捷方式
        val hideFlashlight = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "hide_keyguard_flashlight")
        val hideCamera = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "hide_keyguard_camera")
        if (hideFlashlight || hideCamera) {
            HideKeyguardShortcutsHook.handleLoadPackage(lpparam, hideFlashlight, hideCamera)
        }

        // 隐藏充电动画
        val hideChargingAnimation = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "hide_charging_animation")
        HideChargingAnimationHook.handleLoadPackage(lpparam, hideChargingAnimation)

        // 下拉区域比例
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "pulldown_area_ratio")) {
            val ratio = XposedPrefs.getFeatureValue(lpparam, targetPackage, "pulldown_area_ratio", 50)
            PulldownAreaRatioHook.handleLoadPackage(lpparam, ratio)
        }

        // 通知图标限制
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "notification_icon_limit")) {
            val maxIcons = XposedPrefs.getFeatureValue(lpparam, targetPackage, "notification_icon_limit", 4)
            NotificationIconLimitHook.handleLoadPackage(lpparam, maxIcons)
        }

        // 仅显示上网卡
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "show_data_sim_only")) {
            ShowDataSimOnlyHook.handleLoadPackage(lpparam)
        }

        // AOD 歌词
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "aod_lyric")) {
            AODLyricHook.handleLoadPackage(lpparam)
        }

        // 解除通知管理限制
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "notification_manage")) {
            NotificationManageHook.handleLoadPackage(lpparam)
        }
    }
}

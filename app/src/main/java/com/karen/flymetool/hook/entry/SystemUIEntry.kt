package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemUIEntry : HookEntry {
    override val targetPackage = "com.android.systemui"

    // 使用 supplier，避免 Entry <clinit> 时立刻初始化全部 Hook object
    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "StatusBarClockHook" to { com.karen.flymetool.hook.feature.systemui.StatusBarClockHook },
        "StatusBarClockSecondsHook" to { com.karen.flymetool.hook.feature.systemui.StatusBarClockSecondsHook },
        "PowerDisplayHook" to { com.karen.flymetool.hook.feature.systemui.PowerDisplayHook },
        "ConnectionRateLowSpeedHideHook" to { com.karen.flymetool.hook.feature.systemui.ConnectionRateLowSpeedHideHook },
        "PulldownAreaRatioHook" to { com.karen.flymetool.hook.feature.systemui.PulldownAreaRatioHook },
        "ControlCenterBlurHook" to { com.karen.flymetool.hook.feature.systemui.ControlCenterBlurHook },
        "ControlCenterRadiusHook" to { com.karen.flymetool.hook.feature.systemui.ControlCenterRadiusHook },
        "NotificationIconLimitHook" to { com.karen.flymetool.hook.feature.systemui.NotificationIconLimitHook },
        "ShowDataSimOnlyHook" to { com.karen.flymetool.hook.feature.systemui.ShowDataSimOnlyHook },
        "HideStatusBarIconHook" to { com.karen.flymetool.hook.feature.systemui.HideStatusBarIconHook },
        "AppIconNotificationHook" to { com.karen.flymetool.hook.feature.systemui.AppIconNotificationHook },
        "ForceCircleBatteryHook" to { com.karen.flymetool.hook.feature.systemui.ForceCircleBatteryHook },
        "HideKeyguardShortcutsHook" to { com.karen.flymetool.hook.feature.systemui.HideKeyguardShortcutsHook },
        "HideKeyguardStatusBarHook" to { com.karen.flymetool.hook.feature.systemui.HideKeyguardStatusBarHook },
        "HideKeyguardFingerprintIconHook" to { com.karen.flymetool.hook.feature.systemui.HideKeyguardFingerprintIconHook },
        "AODLyricHook" to { com.karen.flymetool.hook.feature.systemui.AODLyricHook },
        "AODNotificationHook" to { com.karen.flymetool.hook.feature.systemui.AODNotificationHook },
        "HideChargingAnimationHook" to { com.karen.flymetool.hook.feature.systemui.HideChargingAnimationHook },
        "NotificationCardRadiusHook" to { com.karen.flymetool.hook.feature.systemui.NotificationCardRadiusHook },
        "NotificationCardMaskHook" to { com.karen.flymetool.hook.feature.systemui.NotificationCardMaskHook },
        "TickerClickHook" to { com.karen.flymetool.hook.feature.systemui.TickerClickHook },
        "NotificationManageHook" to { com.karen.flymetool.hook.feature.systemui.NotificationManageHook },
        "HideMediaAppIconBgHook" to { com.karen.flymetool.hook.feature.systemui.HideMediaAppIconBgHook },
        "MediaCardRadiusHook" to { com.karen.flymetool.hook.feature.systemui.MediaCardRadiusHook },
        "HideGestureBarHook" to { com.karen.flymetool.hook.feature.systemui.HideGestureBarHook },
        "CustomCarrierNameHook" to { com.karen.flymetool.hook.feature.systemui.CustomCarrierNameHook },
        "ForceLiveNotificationHook" to { com.karen.flymetool.hook.feature.systemui.ForceLiveNotificationHook },
        "IosNotificationStackHook" to { com.karen.flymetool.hook.feature.systemui.IosNotificationStackHook },
        "EdgeBackVibrateHook" to { com.karen.flymetool.hook.feature.systemui.EdgeBackVibrateHook },
        "EdgeBackHoldPreviousAppHook" to { com.karen.flymetool.hook.feature.systemui.EdgeBackHoldPreviousAppHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}

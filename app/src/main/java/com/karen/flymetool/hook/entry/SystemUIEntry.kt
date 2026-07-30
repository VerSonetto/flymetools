package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.systemui.AODLyricHook
import com.karen.flymetool.hook.feature.systemui.AODNotificationHook
import com.karen.flymetool.hook.feature.systemui.AppIconNotificationHook
import com.karen.flymetool.hook.feature.systemui.ConnectionRateLowSpeedHideHook
import com.karen.flymetool.hook.feature.systemui.ControlCenterBlurHook
import com.karen.flymetool.hook.feature.systemui.HideChargingAnimationHook
import com.karen.flymetool.hook.feature.systemui.HideGestureBarHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardFingerprintIconHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardShortcutsHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardStatusBarHook
import com.karen.flymetool.hook.feature.systemui.HideMediaAppIconBgHook
import com.karen.flymetool.hook.feature.systemui.HideStatusBarIconHook
import com.karen.flymetool.hook.feature.systemui.NotificationCardMaskHook
import com.karen.flymetool.hook.feature.systemui.NotificationCardRadiusHook
import com.karen.flymetool.hook.feature.systemui.NotificationIconLimitHook
import com.karen.flymetool.hook.feature.systemui.NotificationManageHook
import com.karen.flymetool.hook.feature.systemui.PowerDisplayHook
import com.karen.flymetool.hook.feature.systemui.PulldownAreaRatioHook
import com.karen.flymetool.hook.feature.systemui.ShowDataSimOnlyHook
import com.karen.flymetool.hook.feature.systemui.StatusBarClockHook
import com.karen.flymetool.hook.feature.systemui.StatusBarClockSecondsHook
import com.karen.flymetool.hook.feature.systemui.TickerClickHook
import com.karen.flymetool.hook.feature.systemui.CustomCarrierNameHook
import com.karen.flymetool.hook.feature.systemui.EdgeBackHoldPreviousAppHook
import com.karen.flymetool.hook.feature.systemui.EdgeBackVibrateHook
import com.karen.flymetool.hook.feature.systemui.MediaCardRadiusHook
import com.karen.flymetool.hook.feature.systemui.ForceCircleBatteryHook
import com.karen.flymetool.hook.feature.systemui.ForceLiveNotificationHook
import com.karen.flymetool.hook.feature.systemui.IosNotificationStackHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemUIEntry : HookEntry {
    override val targetPackage = "com.android.systemui"

    private val hooks: List<FeatureHook> = listOf(
        StatusBarClockHook,
        StatusBarClockSecondsHook,
        PowerDisplayHook,
        ConnectionRateLowSpeedHideHook,
        PulldownAreaRatioHook,
        ControlCenterBlurHook,
        NotificationIconLimitHook,
        ShowDataSimOnlyHook,
        HideStatusBarIconHook,
        AppIconNotificationHook,
        ForceCircleBatteryHook,
        HideKeyguardShortcutsHook,
        HideKeyguardStatusBarHook,
        HideKeyguardFingerprintIconHook,
        AODLyricHook,
        AODNotificationHook,
        HideChargingAnimationHook,
        NotificationCardRadiusHook,
        NotificationCardMaskHook,
        TickerClickHook,
        NotificationManageHook,
        HideMediaAppIconBgHook,
        MediaCardRadiusHook,
        HideGestureBarHook,
        CustomCarrierNameHook,
        ForceLiveNotificationHook,
        IosNotificationStackHook,
        EdgeBackVibrateHook,
        EdgeBackHoldPreviousAppHook,
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (hook in hooks) {
            try {
                hook.handle(lpparam, targetPackage)
            } catch (e: Throwable) {
                Logger.e(targetPackage, "Hook ${hook::class.simpleName} failed", e)
            }
        }
    }
}

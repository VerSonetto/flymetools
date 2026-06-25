package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.systemui.AODLyricHook
import com.karen.flymetool.hook.feature.systemui.AODNotificationHook
import com.karen.flymetool.hook.feature.systemui.AppIconNotificationHook
import com.karen.flymetool.hook.feature.systemui.ConnectionRateLowSpeedHideHook
import com.karen.flymetool.hook.feature.systemui.HideChargingAnimationHook
import com.karen.flymetool.hook.feature.systemui.HideGestureBarHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardShortcutsHook
import com.karen.flymetool.hook.feature.systemui.HideKeyguardStatusBarHook
import com.karen.flymetool.hook.feature.systemui.HideMediaAppIconBgHook
import com.karen.flymetool.hook.feature.systemui.HideStatusBarIconHook
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
import com.karen.flymetool.hook.feature.systemui.MediaCardRadiusHook
import com.karen.flymetool.hook.feature.systemui.BackgroundBlurStrengthHook
import com.karen.flymetool.hook.feature.systemui.ForceLiveNotificationHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemUIEntry : HookEntry {
    override val targetPackage = "com.android.systemui"

    private val hooks: List<FeatureHook> = listOf(
        StatusBarClockHook,
        StatusBarClockSecondsHook,
        PowerDisplayHook,
        ConnectionRateLowSpeedHideHook,
        PulldownAreaRatioHook,
        NotificationIconLimitHook,
        ShowDataSimOnlyHook,
        HideStatusBarIconHook,
        AppIconNotificationHook,
        HideKeyguardShortcutsHook,
        HideKeyguardStatusBarHook,
        AODLyricHook,
        AODNotificationHook,
        HideChargingAnimationHook,
        NotificationCardRadiusHook,
        TickerClickHook,
        NotificationManageHook,
        HideMediaAppIconBgHook,
        MediaCardRadiusHook,
        HideGestureBarHook,
        CustomCarrierNameHook,
        BackgroundBlurStrengthHook,
        ForceLiveNotificationHook,
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

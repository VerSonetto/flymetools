package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 去除通知卡片 blur 前景遮罩色（日间 0xB2FFFFFF / 夜间 0xB21A1A1A）。
 * 覆盖下拉通知栏与锁屏通知卡片（含分组折叠背景）。
 */
object NotificationCardMaskHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_no_mask"
    private const val HOOK_NAME = "NotificationCardMask"
    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        try {
            XposedHelpers.findAndHookMethod(
                BACKGROUND_VIEW,
                lpparam.classLoader,
                "setBlurBackground",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[1] = Color.TRANSPARENT
                        Logger.once(HOOK_NAME, "cleared notification blur mask color")
                    }
                }
            )
            Logger.i(HOOK_NAME, "已启用：去除通知卡片遮罩")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook 失败", e)
        }
    }
}

package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideGestureBarHook : FeatureHook {

    private const val NAVIGATION_BAR_VIEW = "com.android.systemui.navigationbar.NavigationBarView"
    private const val HOOK_NAME = "HideGestureBar"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_gesture_bar")) return
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val clazz = XposedHelpers.findClass(NAVIGATION_BAR_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateCurrentView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hideGestureBar(param.thisObject)
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked NavigationBarView")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }

    private fun hideGestureBar(navBarView: Any) {
        try {
            val mBackButton = try {
                XposedHelpers.callMethod(navBarView, "getMBackButton")
            } catch (_: Throwable) {
                null
            }

            if (mBackButton != null) {
                XposedHelpers.callMethod(mBackButton, "setAlpha", 0.0f)
                Logger.d(HOOK_NAME, "MBack button hidden")
                return
            }

            val homeHandle = try {
                XposedHelpers.callMethod(navBarView, "getHomeHandle")
            } catch (_: Throwable) {
                null
            }

            if (homeHandle != null) {
                XposedHelpers.callMethod(homeHandle, "setAlpha", 0.0f)
                Logger.d(HOOK_NAME, "Home handle hidden")
            }
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hide gesture bar failed", e)
        }
    }
}

package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object HideGestureBarHook : FeatureHook {

    private const val TAG = "HideGestureBar"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_gesture_bar")) return
        if (lpparam.packageName != "com.android.systemui") return

        val className = if (FlymeVersionUtils.isFlyme12()) {
            "com.android.systemui.navigationbar.views.NavigationBarView"
        } else {
            "com.android.systemui.navigationbar.NavigationBarView"
        }

        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateCurrentView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hideGestureBar(param.thisObject)
                    }
                }
            )

            Logger.i(TAG, "已挂载 $className")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
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
                Logger.d(TAG) { "MBack 按钮已隐藏" }
                return
            }

            val homeHandle = try {
                XposedHelpers.callMethod(navBarView, "getHomeHandle")
            } catch (_: Throwable) {
                null
            }

            if (homeHandle != null) {
                XposedHelpers.callMethod(homeHandle, "setAlpha", 0.0f)
                Logger.d(TAG) { "Home 指示条已隐藏" }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "隐藏手势栏失败", e)
        }
    }
}

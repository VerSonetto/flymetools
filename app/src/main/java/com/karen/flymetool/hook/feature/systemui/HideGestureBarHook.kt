package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideGestureBarHook : FeatureHook {

    private const val TAG = "HideGestureBar"

    /**
     * NavigationBarView 类路径候选，按优先级依次尝试。
     * 不同 Flyme 12 版本的包名迁移不统一，不能用版本号武断二选一：
     * - 早期 Flyme 12（Android 14）：com.android.systemui.navigationbar.views
     * - Flyme 12（Android 15 daily）/ Flyme 10、11：com.android.systemui.navigationbar
     */
    private val NAV_BAR_VIEW_CANDIDATES = listOf(
        "com.android.systemui.navigationbar.views.NavigationBarView",
        "com.android.systemui.navigationbar.NavigationBarView",
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_gesture_bar")) return
        if (lpparam.packageName != "com.android.systemui") return

        // 特征定位：逐个尝试候选类路径，命中任一即挂载
        var clazz: Class<*>? = null
        var resolved: String? = null
        var lastError: Throwable? = null
        for (candidate in NAV_BAR_VIEW_CANDIDATES) {
            try {
                clazz = XposedHelpers.findClass(candidate, lpparam.classLoader)
                resolved = candidate
                break
            } catch (e: Throwable) {
                lastError = e
                Logger.w(TAG, "候选类不存在: $candidate")
            }
        }

        if (clazz == null) {
            Logger.e(TAG, "NavigationBarView 所有候选路径均不存在", lastError)
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "updateCurrentView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hideGestureBar(param.thisObject)
                    }
                }
            )

            Logger.i(TAG, "已挂载 $resolved")
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

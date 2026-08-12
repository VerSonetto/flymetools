package com.karen.flymetool.hook.feature.systemui

import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.ref.WeakReference


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

    private val NAV_BAR_CONTROLLER_CANDIDATES = listOf(
        "com.android.systemui.navigationbar.views.NavigationBar",
        "com.android.systemui.navigationbar.NavigationBar",
    )


    private var barTransitionsRef: WeakReference<Any>? = null

    private var navBarViewRef: WeakReference<Any>? = null

    /** 彻底隐藏底部导航栏区域（背景透明 + insets 压零），由子开关控制。 */
    private var hideAreaEnabled: Boolean = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_gesture_bar")) return
        if (lpparam.packageName != "com.android.systemui") return

        hideAreaEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_gesture_bar_area")
        Logger.i(TAG, "隐藏手势条已启用, 彻底隐藏区域=$hideAreaEnabled")

        val viewClazz = resolveClass(lpparam, NAV_BAR_VIEW_CANDIDATES, "NavigationBarView") ?: return
        val controllerClazz = resolveClass(lpparam, NAV_BAR_CONTROLLER_CANDIDATES, "NavigationBar")

        hookUpdateCurrentView(viewClazz)
        hookSetBarTransitions(viewClazz)
        if (hideAreaEnabled) {
            hookOverrideAlphaPin(viewClazz)
            if (controllerClazz != null) {
                hookInsetsFrameProvider(controllerClazz)
            } else {
                Logger.w(TAG, "NavigationBar 控制器类未找到，仅隐藏视觉层，无法压掉 app 底部 insets 白边")
            }
        }
    }

    private fun resolveClass(
        lpparam: XC_LoadPackage.LoadPackageParam,
        candidates: List<String>,
        label: String,
    ): Class<*>? {
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val clazz = XposedHelpers.findClass(candidate, lpparam.classLoader)
                Logger.i(TAG, "命中 $label: $candidate")
                return clazz
            } catch (e: Throwable) {
                lastError = e
                Logger.w(TAG, "候选类不存在: $candidate")
            }
        }
        Logger.e(TAG, "$label 所有候选路径均不存在", lastError)
        return null
    }

    private fun hookUpdateCurrentView(viewClazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                viewClazz,
                "updateCurrentView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hideGestureBar(param.thisObject)
                    }
                }
            )
            Logger.i(TAG, "已挂载 ${viewClazz.name}#updateCurrentView")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateCurrentView 失败", e)
        }
    }

    private fun hookSetBarTransitions(viewClazz: Class<*>) {
        try {
            XposedBridge.hookAllMethods(
                viewClazz,
                "setBarTransitions",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val transitions = param.args.getOrNull(0) ?: return
                        barTransitionsRef = WeakReference(transitions)
                        hideGestureBar(param.thisObject)
                    }
                }
            )
            Logger.i(TAG, "已挂载 ${viewClazz.name}#setBarTransitions")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setBarTransitions 失败", e)
        }
    }

    private fun hookOverrideAlphaPin(viewClazz: Class<*>) {
        try {

            val transitionsClassNames = listOf(
                viewClazz.name.replace("NavigationBarView", "NavigationBarTransitions"),
                "com.android.systemui.navigationbar.views.NavigationBarTransitions",
                "com.android.systemui.navigationbar.NavigationBarTransitions",
            )
            var hooked = false
            var lastError: Throwable? = null
            for (name in transitionsClassNames) {
                try {
                    val transitionsClazz = XposedHelpers.findClass(name, viewClazz.classLoader)
                    XposedBridge.hookAllMethods(
                        transitionsClazz,
                        "setBackgroundOverrideAlpha",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // 只在我们判定为手势模式时强制 0；三键模式放行，避免整条导航栏消失。
                                if (isGesturePillActive(navBarViewRef?.get())) {
                                    param.args[0] = 0.0f
                                }
                            }
                        }
                    )
                    Logger.i(TAG, "已挂载 $name#setBackgroundOverrideAlpha 钉死")
                    hooked = true
                    break
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            if (!hooked) {
                Logger.w(TAG, "未能挂载 setBackgroundOverrideAlpha 钉死: ${lastError?.message}")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "hookOverrideAlphaPin 失败", e)
        }
    }

    private fun hookInsetsFrameProvider(controllerClazz: Class<*>) {
        try {
            XposedBridge.hookAllMethods(
                controllerClazz,
                "getInsetsFrameProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 仅在手势/mback 生效时压 insets，三键模式保留完整导航栏占位。
                        if (!isGesturePillActive(navBarViewRef?.get())) return
                        val providers = param.result as? Array<*> ?: return
                        zeroNavigationBarInsets(providers)
                    }
                }
            )
            Logger.i(TAG, "已挂载 ${controllerClazz.name}#getInsetsFrameProvider")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 getInsetsFrameProvider 失败", e)
        }
    }

    private fun zeroNavigationBarInsets(providers: Array<*>) {
        if (providers.isEmpty()) return

        val navBarsProvider = providers[0] ?: return
        try {
            XposedHelpers.callMethod(navBarsProvider, "setInsetsSize", Insets.NONE)
            Logger.d(TAG) { "navigationBars insets 已压成 NONE" }
            return
        } catch (e: Throwable) {
            Logger.e(TAG, "setInsetsSize(Insets.NONE) 失败，尝试 Insets.of(0,0,0,0)", e)
        }
        try {
            val zero = Insets.of(0, 0, 0, 0)
            XposedHelpers.callMethod(navBarsProvider, "setInsetsSize", zero)
            Logger.d(TAG) { "navigationBars insets 已压成 of(0,0,0,0)" }
        } catch (e: Throwable) {
            Logger.e(TAG, "setInsetsSize 兜底仍失败", e)
        }
    }


    private fun hideGestureBar(navBarView: Any) {
        navBarViewRef = WeakReference(navBarView)

        val mBackButton = try {
            XposedHelpers.callMethod(navBarView, "getMBackButton")
        } catch (_: Throwable) {
            null
        }
        if (mBackButton != null) {
            try {
                XposedHelpers.callMethod(mBackButton, "setAlpha", 0.0f)
                Logger.d(TAG) { "MBack 按钮已隐藏" }
            } catch (e: Throwable) {
                Logger.e(TAG, "隐藏 MBack 失败", e)
            }
        }

        val homeHandle = try {
            XposedHelpers.callMethod(navBarView, "getHomeHandle")
        } catch (_: Throwable) {
            null
        }
        if (homeHandle != null) {
            try {
                XposedHelpers.callMethod(homeHandle, "setAlpha", 0.0f)
                Logger.d(TAG) { "Home 指示条已隐藏" }
            } catch (e: Throwable) {
                Logger.e(TAG, "隐藏 HomeHandle 失败", e)
            }
        }

        // 彻底隐藏区域：仅子开关开启且手势模式时处理背景/灰带。
        if (hideAreaEnabled && isGesturePillActive(navBarView)) {
            hideNavBarBackground(navBarView)
        }
    }

    private fun isGesturePillActive(navBarView: Any?): Boolean {
        if (navBarView == null) return false
        if (hasActivePill(safeCall(navBarView, "getMBackButton"))) return true
        if (hasActivePill(safeCall(navBarView, "getHomeHandle"))) return true
        return false
    }

    private fun safeCall(target: Any, method: String): Any? {
        return try {
            XposedHelpers.callMethod(target, method)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasActivePill(dispatcher: Any?): Boolean {
        if (dispatcher == null) return false
        return try {
            XposedHelpers.callMethod(dispatcher, "getCurrentView") != null
        } catch (_: Throwable) {
            // 拿不到判定时按存在处理，避免漏隐藏
            true
        }
    }

    private fun hideNavBarBackground(navBarView: Any) {
        var applied = false

        val transitions = barTransitionsRef?.get() ?: readBarTransitionsField(navBarView)
        if (transitions != null) {
            barTransitionsRef = WeakReference(transitions)
            try {
                XposedHelpers.callMethod(transitions, "setBackgroundOverrideAlpha", 0.0f)
                Logger.d(TAG) { "导航栏背景已透明 (overrideAlpha=0)" }
                applied = true
            } catch (e: Throwable) {
                Logger.e(TAG, "setBackgroundOverrideAlpha 调用失败", e)
            }
            // 手势模式下强制切到 TRANSPARENT mode（0），避免 SEMI_TRANSPARENT 仍画出灰带。
            try {
                XposedHelpers.callMethod(transitions, "transitionTo", 0, false)
                Logger.d(TAG) { "导航栏 transitionTo MODE_TRANSPARENT" }
            } catch (_: Throwable) {
                // 部分版本 transitionTo 签名不同，忽略
            }
        }

        // 兜底：背景只在 BarTransitions 构造时设置，包内无 getBackground 依赖，替换安全。
        try {
            val view = navBarView as? View
            if (view != null) {
                view.background = ColorDrawable(0)
                val parent = view.parent as? View
                parent?.background = ColorDrawable(0)
                Logger.d(TAG) { "导航栏背景已透明 (setBackground=透明, parent=${parent?.javaClass?.simpleName})" }
                applied = true
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "直接替换导航栏背景失败", e)
        }

        if (!applied) {
            Logger.w(TAG, "未能应用任何导航栏背景透明手段")
        }
    }

    /** 从 NavigationBarView.mBarTransitions 字段回读，弱引用丢失时的兜底。 */
    private fun readBarTransitionsField(navBarView: Any): Any? {
        return try {
            XposedHelpers.getObjectField(navBarView, "mBarTransitions")
        } catch (_: Throwable) {
            null
        }
    }
}

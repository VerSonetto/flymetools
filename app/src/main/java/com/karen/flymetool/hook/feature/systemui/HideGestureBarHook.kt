package com.karen.flymetool.hook.feature.systemui

import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface
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

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("hide_gesture_bar")) return
        if (ctx.packageName != "com.android.systemui") return

        hideAreaEnabled = ctx.featureEnabled("hide_gesture_bar_area")
        Logger.i(TAG, "隐藏手势条已启用, 彻底隐藏区域=$hideAreaEnabled")

        val viewClazz = resolveClass(ctx, NAV_BAR_VIEW_CANDIDATES, "NavigationBarView") ?: return
        val controllerClazz = resolveClass(ctx, NAV_BAR_CONTROLLER_CANDIDATES, "NavigationBar")

        hookUpdateCurrentView(ctx.api, viewClazz)
        hookSetBarTransitions(ctx.api, viewClazz)
        if (hideAreaEnabled) {
            hookOverrideAlphaPin(ctx.api, viewClazz)
            if (controllerClazz != null) {
                hookInsetsFrameProvider(ctx.api, controllerClazz)
            } else {
                Logger.w(TAG, "NavigationBar 控制器类未找到，仅隐藏视觉层，无法压掉 app 底部 insets 白边")
            }
        }
    }

    private fun resolveClass(
        ctx: HookContext,
        candidates: List<String>,
        label: String,
    ): Class<*>? {
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val clazz = Reflect.findClass(candidate, ctx.classLoader)
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

    private fun hookUpdateCurrentView(api: XposedInterface, viewClazz: Class<*>) {
        try {
            Reflect.hookMethodOn(api, viewClazz, "updateCurrentView") { chain ->
                val result = chain.proceed()
                hideGestureBar(api, chain.getThisObject())
                result
            }
            Logger.i(TAG, "已挂载 ${viewClazz.name}#updateCurrentView")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateCurrentView 失败", e)
        }
    }

    private fun hookSetBarTransitions(api: XposedInterface, viewClazz: Class<*>) {
        try {
            Reflect.hookAllMethods(api, viewClazz, "setBarTransitions", excluded = { false }) { chain ->
                val result = chain.proceed()
                val transitions = chain.getArgs().getOrNull(0) ?: return@hookAllMethods result
                barTransitionsRef = WeakReference(transitions)
                hideGestureBar(api, chain.getThisObject())
                result
            }
            Logger.i(TAG, "已挂载 ${viewClazz.name}#setBarTransitions")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setBarTransitions 失败", e)
        }
    }

    private fun hookOverrideAlphaPin(api: XposedInterface, viewClazz: Class<*>) {
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
                    val transitionsClazz = Reflect.findClass(name, viewClazz.classLoader)
                    Reflect.hookAllMethods(api, transitionsClazz, "setBackgroundOverrideAlpha", excluded = { false }) { chain ->
                        // 只在我们判定为手势模式时强制 0；三键模式放行，避免整条导航栏消失。
                        if (isGesturePillActive(api, navBarViewRef?.get())) {
                            val array = chain.getArgs().toMutableList()
                            array[0] = 0.0f
                            chain.proceed(array.toTypedArray())
                        } else {
                            chain.proceed()
                        }
                    }
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

    private fun hookInsetsFrameProvider(api: XposedInterface, controllerClazz: Class<*>) {
        try {
            Reflect.hookAllMethods(api, controllerClazz, "getInsetsFrameProvider", excluded = { false }) { chain ->
                val result = chain.proceed()
                // 仅在手势/mback 生效时压 insets，三键模式保留完整导航栏占位。
                if (!isGesturePillActive(api, navBarViewRef?.get())) return@hookAllMethods result
                val providers = result as? Array<*> ?: return@hookAllMethods result
                zeroNavigationBarInsets(api, providers)
                result
            }
            Logger.i(TAG, "已挂载 ${controllerClazz.name}#getInsetsFrameProvider")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 getInsetsFrameProvider 失败", e)
        }
    }

    private fun zeroNavigationBarInsets(api: XposedInterface, providers: Array<*>) {
        if (providers.isEmpty()) return

        val navBarsProvider = providers[0] ?: return
        try {
            Reflect.callMethod(api, navBarsProvider, "setInsetsSize", Insets.NONE)
            Logger.d(TAG) { "navigationBars insets 已压成 NONE" }
            return
        } catch (e: Throwable) {
            Logger.e(TAG, "setInsetsSize(Insets.NONE) 失败，尝试 Insets.of(0,0,0,0)", e)
        }
        try {
            val zero = Insets.of(0, 0, 0, 0)
            Reflect.callMethod(api, navBarsProvider, "setInsetsSize", zero)
            Logger.d(TAG) { "navigationBars insets 已压成 of(0,0,0,0)" }
        } catch (e: Throwable) {
            Logger.e(TAG, "setInsetsSize 兜底仍失败", e)
        }
    }


    private fun hideGestureBar(api: XposedInterface, navBarView: Any) {
        navBarViewRef = WeakReference(navBarView)

        val mBackButton = try {
            Reflect.callMethod(api, navBarView, "getMBackButton")
        } catch (_: Throwable) {
            null
        }
        if (mBackButton != null) {
            try {
                Reflect.callMethod(api, mBackButton, "setAlpha", 0.0f)
                Logger.d(TAG) { "MBack 按钮已隐藏" }
            } catch (e: Throwable) {
                Logger.e(TAG, "隐藏 MBack 失败", e)
            }
        }

        val homeHandle = try {
            Reflect.callMethod(api, navBarView, "getHomeHandle")
        } catch (_: Throwable) {
            null
        }
        if (homeHandle != null) {
            try {
                Reflect.callMethod(api, homeHandle, "setAlpha", 0.0f)
                Logger.d(TAG) { "Home 指示条已隐藏" }
            } catch (e: Throwable) {
                Logger.e(TAG, "隐藏 HomeHandle 失败", e)
            }
        }

        // 彻底隐藏区域：仅子开关开启且手势模式时处理背景/灰带。
        if (hideAreaEnabled && isGesturePillActive(api, navBarView)) {
            hideNavBarBackground(api, navBarView)
        }
    }

    private fun isGesturePillActive(api: XposedInterface, navBarView: Any?): Boolean {
        if (navBarView == null) return false
        if (hasActivePill(api, safeCall(api, navBarView, "getMBackButton"))) return true
        if (hasActivePill(api, safeCall(api, navBarView, "getHomeHandle"))) return true
        return false
    }

    private fun safeCall(api: XposedInterface, target: Any, method: String): Any? {
        return try {
            Reflect.callMethod(api, target, method)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasActivePill(api: XposedInterface, dispatcher: Any?): Boolean {
        if (dispatcher == null) return false
        return try {
            Reflect.callMethod(api, dispatcher, "getCurrentView") != null
        } catch (_: Throwable) {
            // 拿不到判定时按存在处理，避免漏隐藏
            true
        }
    }

    private fun hideNavBarBackground(api: XposedInterface, navBarView: Any) {
        var applied = false

        val transitions = barTransitionsRef?.get() ?: readBarTransitionsField(navBarView)
        if (transitions != null) {
            barTransitionsRef = WeakReference(transitions)
            try {
                Reflect.callMethod(api, transitions, "setBackgroundOverrideAlpha", 0.0f)
                Logger.d(TAG) { "导航栏背景已透明 (overrideAlpha=0)" }
                applied = true
            } catch (e: Throwable) {
                Logger.e(TAG, "setBackgroundOverrideAlpha 调用失败", e)
            }
            // 手势模式下强制切到 TRANSPARENT mode（0），避免 SEMI_TRANSPARENT 仍画出灰带。
            try {
                Reflect.callMethod(api, transitions, "transitionTo", 0, false)
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
            Reflect.getObjectField(navBarView, "mBarTransitions")
        } catch (_: Throwable) {
            null
        }
    }
}
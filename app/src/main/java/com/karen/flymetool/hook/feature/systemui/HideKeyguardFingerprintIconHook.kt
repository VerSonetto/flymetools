package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

/**
 * 隐藏锁屏指纹图标（UDFPS 图标 + DeviceEntry 指纹态）。
 * 仅挡图标显示，不主动关指纹监听；触摸/解锁仍走系统原逻辑。
 */
object HideKeyguardFingerprintIconHook : FeatureHook {

    private const val AUTH_CONTROLLER = "com.android.systemui.biometrics.AuthController"
    private const val UDFPS_CONTROLLER = "com.android.systemui.biometrics.UdfpsController"
    private const val DEVICE_ENTRY_ICON = "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"
    private const val HOOK_NAME = "HideKeyguardFpIcon"
    private const val FEATURE_KEY = "hide_keyguard_fingerprint_icon"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return

        blockShow(lpparam, AUTH_CONTROLLER)
        blockShow(lpparam, UDFPS_CONTROLLER)
        forceDismissOnShowBlocked(lpparam)
        hideDeviceEntryFingerprint(lpparam)

        Logger.i(HOOK_NAME, "Loaded")
    }

    /** 拦截 showFingerprintIcon，禁止把图标再亮出来 */
    private fun blockShow(lpparam: XC_LoadPackage.LoadPackageParam, className: String) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "showFingerprintIcon",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )
            Logger.i(HOOK_NAME, "Blocked $className.showFingerprintIcon")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $className.showFingerprintIcon failed", e)
        }
    }

    /** 构造后清一次图标；shouldDismiss 恒 true 让系统更积极收起 */
    private fun forceDismissOnShowBlocked(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(UDFPS_CONTROLLER, lpparam.classLoader)
            val dismiss = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.callMethod(param.thisObject, "dismissFpIconWithoutAnim")
                    } catch (_: Throwable) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "dismissFingerprintIcon")
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            for (ctor in clazz.declaredConstructors) {
                try {
                    XposedHelpers.findAndHookConstructor(clazz, *ctor.parameterTypes, dismiss)
                } catch (_: Throwable) {
                }
            }
            XposedHelpers.findAndHookMethod(
                clazz,
                "shouldDismissFingerprintIcon",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            Logger.i(HOOK_NAME, "UdfpsController dismiss + shouldDismiss hooked")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "forceDismiss setup failed", e)
        }
    }

    /**
     * DeviceEntry 入口图标：指纹态时强制 GONE。
     * LOCK/UNLOCK 仍可显示；仅当 contentDescription 为指纹标签时也可 GONE 整个 view。
     * 更稳：getIconState(FINGERPRINT, *) 返回 StateSet.NOTHING，不画指纹帧。
     */
    private fun hideDeviceEntryFingerprint(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(DEVICE_ENTRY_ICON, lpparam.classLoader)
            val iconTypeCl = XposedHelpers.findClass(
                "$DEVICE_ENTRY_ICON\$IconType",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "getIconState",
                iconTypeCl,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val type = param.args[0] ?: return
                            if (type.toString().contains("FINGERPRINT")) {
                                // 与 IconType.NONE 相同：不展示指纹帧
                                param.result = android.util.StateSet.NOTHING
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )

            // 整 view 被设为 VISIBLE 时，若当前是指纹图标描述，则压回 GONE（兜底）
            XposedHelpers.findAndHookMethod(
                clazz,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val vis = param.args[0] as Int
                            if (vis != View.VISIBLE && vis != View.INVISIBLE) return
                            val view = param.thisObject as View
                            val desc = view.contentDescription?.toString().orEmpty()
                            // 指纹相关无障碍文案时隐藏（LOCK/UNLOCK 一般不含 fingerprint）
                            if (desc.contains("fingerprint", ignoreCase = true) ||
                                desc.contains("指纹")
                            ) {
                                param.args[0] = View.GONE
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked DeviceEntryIconView fingerprint hide")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook DeviceEntryIconView failed", e)
        }
    }
}

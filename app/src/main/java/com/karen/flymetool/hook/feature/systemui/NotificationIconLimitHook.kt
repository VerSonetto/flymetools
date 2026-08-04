package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object NotificationIconLimitHook : FeatureHook {

    private const val TAG = "NotificationIconLimit"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "notification_icon_limit")) return
        if (lpparam.packageName != "com.android.systemui") return

        val maxIcons = XposedPrefs.getFeatureValue(lpparam, packageName, "notification_icon_limit", 4)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam, maxIcons)
            else -> hookLegacy(lpparam, maxIcons)
        }
    }

    private fun hookFlyme12(lpparam: XC_LoadPackage.LoadPackageParam, maxIcons: Int) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            // Flyme 12.6 把数量逻辑迁到 ViewModel -> ViewBinder -> setMaxIconsAmount
            // calculateIconXTranslations 实际用的是 mMaxIcons，不再是 mMaxStaticIcons
            XposedHelpers.findAndHookMethod(
                clazz,
                "setMaxIconsAmount",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = maxIcons
                    }
                }
            )

            // 兜底：initResources 里把两个字段都改掉，防止还有旧路径
            XposedHelpers.findAndHookMethod(
                clazz,
                "initResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        XposedHelpers.setObjectField(thisObject, "mMaxIcons", maxIcons)
                        XposedHelpers.setObjectField(thisObject, "mMaxStaticIcons", maxIcons)
                    }
                }
            )

            Logger.i(TAG, "通知图标数量限制(Flyme12): $maxIcons")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败（Flyme12）", e)
        }
    }

    private fun hookLegacy(lpparam: XC_LoadPackage.LoadPackageParam, maxIcons: Int) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "initResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        XposedHelpers.setObjectField(thisObject, "mMaxStaticIcons", maxIcons)
                        Logger.once(TAG, "max_static_icons", "mMaxStaticIcons = $maxIcons")
                    }
                }
            )

            Logger.i(TAG, "通知图标数量限制: $maxIcons")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}

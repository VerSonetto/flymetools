package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object NotificationCardRadiusHook {

    private const val HOOK_NAME = "NotificationCardRadius"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        if (lpparam.packageName != "com.android.systemui") return

        hookResourcesDimension(lpparam, radiusDp)
        hookRoundableState(lpparam, radiusDp)
    }

    private fun hookResourcesDimension(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            ).toInt()

            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getDimensionPixelSize",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resId = param.args[0] as Int
                        val resName = try {
                            (param.thisObject as Resources).getResourceEntryName(resId)
                        } catch (_: Exception) {
                            null
                        }

                        if (resName == "notification_corner_radius" ||
                            resName == "notification_background_radius") {
                            param.result = radiusPx
                            Logger.once(HOOK_NAME, "Hooked $resName = ${radiusPx}px")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getDimension",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resId = param.args[0] as Int
                        val resName = try {
                            (param.thisObject as Resources).getResourceEntryName(resId)
                        } catch (_: Exception) {
                            null
                        }

                        if (resName == "notification_corner_radius" ||
                            resName == "notification_background_radius") {
                            param.result = radiusPx.toFloat()
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked Resources.getDimension* with radius=${radiusDp}dp")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook Resources.getDimension* failed", e)
        }
    }

    private fun hookRoundableState(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            val roundableStateClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.RoundableState",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookConstructor(
                roundableStateClass,
                android.view.View::class.java,
                XposedHelpers.findClass("com.android.systemui.statusbar.notification.Roundable", lpparam.classLoader),
                Float::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[2] = radiusPx
                        Logger.once(HOOK_NAME, "Hooked RoundableState, maxRadius=${radiusPx}")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                roundableStateClass,
                "setMaxRadius",
                Float::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = radiusPx
                        Logger.once(HOOK_NAME, "setMaxRadius override = ${radiusPx}")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked RoundableState")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook RoundableState failed", e)
        }
    }
}

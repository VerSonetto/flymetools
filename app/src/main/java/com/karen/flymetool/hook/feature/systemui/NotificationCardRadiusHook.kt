package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object NotificationCardRadiusHook : FeatureHook {

    private const val TAG = "NotificationCardRadius"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "notification_card_radius")) return
        if (lpparam.packageName != "com.android.systemui") return

        val radiusDp = XposedPrefs.getFeatureValue(lpparam, packageName, "notification_card_radius", 24)
        mount(lpparam, radiusDp)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
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
                            Logger.once(TAG, "dim_$resName", "已挂载 $resName = ${radiusPx}px")
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

            Logger.i(TAG, "已挂载 Resources.getDimension*，圆角=${radiusDp}dp")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Resources.getDimension* 失败", e)
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
                        Logger.once(TAG, "roundable", "已挂载 RoundableState, maxRadius=${radiusPx}")
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
                        Logger.once(TAG, "set_max_radius", "setMaxRadius 覆盖 = ${radiusPx}")
                    }
                }
            )

            Logger.i(TAG, "已挂载 RoundableState")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 RoundableState 失败", e)
        }
    }
}

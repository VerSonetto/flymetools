package com.karen.flymetool.hook.feature.systemui

import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object CustomCarrierNameHook : FeatureHook {

    private const val TAG = "CustomCarrierName"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "custom_carrier_name")) return

        val name1 = XposedPrefs.getFeatureString(lpparam, packageName, "custom_carrier_name", "")
        val name2 = XposedPrefs.getFeatureString(lpparam, packageName, "custom_carrier_name_sim2", "")
        if (name1.isEmpty() && name2.isEmpty()) return

        val separator = when {
            FlymeVersionUtils.isFlyme10() -> "I"
            else -> "|"
        }

        hookTextView(lpparam, name1, name2, separator)
        hookShadeCarrier(lpparam, name1, name2)
    }

    private fun hookTextView(lpparam: XC_LoadPackage.LoadPackageParam, name1: String, name2: String, separator: String) {
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText", CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args[0] as? CharSequence ?: return
                        if (text.toString().isEmpty()) return
                        when (param.thisObject.javaClass.name) {
                            "com.android.keyguard.CarrierText" -> {
                                param.args[0] = if (name2.isNotEmpty()) "$name1$separator$name2" else name1
                            }
                            "com.android.systemui.statusbar.OperatorNameView" -> {
                                param.args[0] = name1
                            }
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 TextView.setText, 分隔符: $separator")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 TextView.setText 失败", e)
        }
    }

    private fun hookShadeCarrier(lpparam: XC_LoadPackage.LoadPackageParam, name1: String, name2: String) {
        try {
            val clazz = XposedHelpers.findClass("com.android.systemui.shade.carrier.ShadeCarrier", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz, "setCarrierText", CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isEmpty() || param.args[0] !is CharSequence) return
                        val view = param.thisObject as? View ?: return
                        val parent = view.parent as? ViewGroup
                        if (parent != null) {
                            var simIndex = 0
                            var count = 0
                            for (i in 0 until parent.childCount) {
                                if (parent.getChildAt(i) === view) {
                                    simIndex = count
                                    break
                                }
                                if (clazz.isInstance(parent.getChildAt(i))) {
                                    count++
                                }
                            }
                            param.args[0] = when (simIndex) {
                                0 -> if (name1.isNotEmpty()) name1 else name2
                                else -> if (name2.isNotEmpty()) name2 else name1
                            }
                        } else {
                            param.args[0] = if (name2.isNotEmpty()) "$name1|$name2" else name1
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 ShadeCarrier.setCarrierText")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 ShadeCarrier 失败", e)
        }
    }
}

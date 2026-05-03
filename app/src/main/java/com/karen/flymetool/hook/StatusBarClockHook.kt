package com.karen.flymetool.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object StatusBarClockHook {

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"
    private const val HOOK_NAME = "StatusBarClock"

    private val CHINESE_WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, format: Int = 0) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val clazz = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateClock",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clockView = param.thisObject as? android.widget.TextView ?: return
                        val originalText = clockView.text?.toString() ?: return

                        val weekday = getWeekday(format)
                        if (originalText.contains(weekday)) return

                        clockView.text = "$weekday $originalText"
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook Clock failed", e)
            tryAlternativeHook(lpparam, format)
        }
    }

    private fun tryAlternativeHook(lpparam: XC_LoadPackage.LoadPackageParam, format: Int) {
        try {
            val clazz = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "onTimeChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clockView = param.thisObject as? android.widget.TextView ?: return
                        val originalText = clockView.text?.toString() ?: return

                        val weekday = getWeekday(format)
                        if (originalText.contains(weekday)) return

                        clockView.text = "$weekday $originalText"
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Alternative hook also failed", e)
        }
    }

    private fun getWeekday(format: Int): String {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        return when (format) {
            0 -> CHINESE_WEEKDAYS[dayOfWeek - 1]
            1 -> {
                val sdf = SimpleDateFormat("EEE", Locale.ENGLISH)
                sdf.format(calendar.time)
            }
            2 -> {
                val sdf = SimpleDateFormat("EEEE", Locale.ENGLISH)
                sdf.format(calendar.time)
            }
            else -> CHINESE_WEEKDAYS[dayOfWeek - 1]
        }
    }
}

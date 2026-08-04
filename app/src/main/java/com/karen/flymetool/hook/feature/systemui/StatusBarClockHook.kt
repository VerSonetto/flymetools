package com.karen.flymetool.hook.feature.systemui

import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 状态栏时钟组合式自定义。
 *
 * - 星期（保留原有 statusbar_weekday，键与行为不变）
 * - 时间段修饰（statusbar_clock_period，方案 1=六时段 2=两时段 3=完全自定义时段）
 * - 自定义时间格式（statusbar_clock_custom_format，含秒级 token 时自动开启秒刷新）
 *
 * 完全自定义时段以 JSON 存储于 statusbar_clock_period_custom：
 * [{"name":"凌晨","start":0,"end":4}, ...]，按列表顺序匹配，第一个命中的生效。
 *
 * 主挂载点：Clock.getSmallTime()（一次覆盖 updateClock 与 demo 刷新）
 * 备用挂载点：Clock.updateClock() / onTimeChanged()（沿用历史形状，兼容旧版本）
 */
object StatusBarClockHook : FeatureHook {

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"
    private const val TAG = "StatusBarClock"

    private const val KEY_WEEKDAY = "statusbar_weekday"
    private const val KEY_WEEKDAY_POSITION = "statusbar_weekday_position"
    private const val KEY_PERIOD = "statusbar_clock_period"
    private const val KEY_PERIOD_POSITION = "statusbar_clock_period_position"
    private const val KEY_PERIOD_CUSTOM = "statusbar_clock_period_custom"
    private const val KEY_CUSTOM_FORMAT = "statusbar_clock_custom_format"

    private val CHINESE_WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    // 六时段预设：凌晨(0-4) 上午(5-10) 中午(11-12) 下午(13-16) 傍晚(17-18) 晚上(19-23)
    private val PERIOD_WORDS_6 = arrayOf("凌晨", "上午", "中午", "下午", "傍晚", "晚上")
    private val PERIOD_RANGES_6 = arrayOf(
        intArrayOf(0, 4), intArrayOf(5, 10), intArrayOf(11, 12),
        intArrayOf(13, 16), intArrayOf(17, 18), intArrayOf(19, 23)
    )

    // 两时段预设：上午(0-11) 下午(12-23)
    private val PERIOD_WORDS_2 = arrayOf("上午", "下午")
    private val PERIOD_RANGES_2 = arrayOf(intArrayOf(0, 11), intArrayOf(12, 23))

    // 完全自定义时段（方案 3）无配置时的默认值，与预设六时段一致
    private val DEFAULT_CUSTOM_SEGMENTS = listOf(
        PeriodSegment("凌晨", 0, 4),
        PeriodSegment("上午", 5, 10),
        PeriodSegment("中午", 11, 12),
        PeriodSegment("下午", 13, 16),
        PeriodSegment("傍晚", 17, 18),
        PeriodSegment("晚上", 19, 23)
    )

    /** 自定义时段：名称 + 起始小时（含）~ 结束小时（含）。 */
    private class PeriodSegment(val name: String, val startHour: Int, val endHour: Int)

    private class ClockConfig(
        val weekdayEnabled: Boolean,
        val weekdayFormat: Int,
        val weekdayPosition: Int,
        val periodEnabled: Boolean,
        val periodScheme: Int,
        val periodPosition: Int,
        val customSegments: List<PeriodSegment>,
        val customFormat: String
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return

        val weekdayEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, KEY_WEEKDAY)
        val periodEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, KEY_PERIOD)
        val customFormat = XposedPrefs.getFeatureString(lpparam, packageName, KEY_CUSTOM_FORMAT, "")
        if (!weekdayEnabled && !periodEnabled && customFormat.isBlank()) return

        val config = ClockConfig(
            weekdayEnabled = weekdayEnabled,
            weekdayFormat = XposedPrefs.getFeatureValue(lpparam, packageName, KEY_WEEKDAY, 0),
            weekdayPosition = XposedPrefs.getFeatureValue(lpparam, packageName, KEY_WEEKDAY_POSITION, 0),
            periodEnabled = periodEnabled,
            periodScheme = XposedPrefs.getFeatureValue(lpparam, packageName, KEY_PERIOD, 1),
            periodPosition = XposedPrefs.getFeatureValue(lpparam, packageName, KEY_PERIOD_POSITION, 0),
            customSegments = if (periodEnabled) readCustomSegments(lpparam, packageName) else emptyList(),
            customFormat = customFormat.trim()
        )

        mountCompose(lpparam, config)

        // 自定义格式含秒级 token 时自动强制秒刷新（复用系统秒刷新机制）
        if (config.customFormat.isNotEmpty() && containsSecondsToken(config.customFormat)) {
            mountAutoSeconds(lpparam)
        }
    }

    /** 读取完全自定义时段列表（JSON），解析失败或列表为空时回退六时段默认。 */
    private fun readCustomSegments(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ): List<PeriodSegment> {
        val raw = XposedPrefs.getFeatureString(lpparam, packageName, KEY_PERIOD_CUSTOM, "")
        if (raw.isBlank()) return DEFAULT_CUSTOM_SEGMENTS
        return try {
            val array = JSONArray(raw)
            val segments = mutableListOf<PeriodSegment>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val start = obj.optInt("start", 0).coerceIn(0, 23)
                val end = obj.optInt("end", 0).coerceIn(0, 23)
                segments.add(PeriodSegment(name, minOf(start, end), maxOf(start, end)))
            }
            if (segments.isEmpty()) DEFAULT_CUSTOM_SEGMENTS else segments
        } catch (e: Throwable) {
            Logger.w(TAG, "自定义时段 JSON 无效，已回退默认时段")
            DEFAULT_CUSTOM_SEGMENTS
        }
    }

    /** 主挂载：改写 getSmallTime 的返回值，一次覆盖 updateClock 与 demo 刷新。 */
    private fun mountCompose(lpparam: XC_LoadPackage.LoadPackageParam, config: ClockConfig) {
        try {
            val clazz = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "getSmallTime",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = param.result as? CharSequence ?: return
                        param.result = composeClockText(original.toString(), config)
                    }
                }
            )
            Logger.i(TAG, "已挂载 Clock.getSmallTime")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 getSmallTime 失败，尝试 updateClock 回退", e)
            mountUpdateClockFallback(lpparam, config)
        }
    }

    private fun mountUpdateClockFallback(lpparam: XC_LoadPackage.LoadPackageParam, config: ClockConfig) {
        try {
            val clazz = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "updateClock",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clockView = param.thisObject as? TextView ?: return
                        val originalText = clockView.text?.toString() ?: return
                        clockView.text = composeClockText(originalText, config)
                    }
                }
            )
            Logger.i(TAG, "已挂载 Clock.updateClock（回退）")
        } catch (e: Throwable) {
            Logger.e(TAG, "updateClock 回退也失败，尝试 onTimeChanged", e)
            mountOnTimeChangedFallback(lpparam, config)
        }
    }

    private fun mountOnTimeChangedFallback(lpparam: XC_LoadPackage.LoadPackageParam, config: ClockConfig) {
        try {
            val clazz = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onTimeChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clockView = param.thisObject as? TextView ?: return
                        val originalText = clockView.text?.toString() ?: return
                        clockView.text = composeClockText(originalText, config)
                    }
                }
            )
            Logger.i(TAG, "已挂载 Clock.onTimeChanged（回退）")
        } catch (e: Throwable) {
            Logger.e(TAG, "onTimeChanged 回退也失败", e)
        }
    }

    /** 自动秒：格式含秒级 token 时强制 mShowSeconds，保证每秒刷新。 */
    private fun mountAutoSeconds(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clockClass = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clockClass, "onTuningChanged", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ("clock_seconds" == param.args[0] as String) {
                            param.args[1] = "1"
                        }
                    }
                }
            )
            Logger.i(TAG, "自动秒: 已挂载 onTuningChanged")
        } catch (e: Throwable) {
            Logger.e(TAG, "自动秒 onTuningChanged 失败", e)
            try {
                val clockClass = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(clockClass, "updateShowSeconds", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedHelpers.setBooleanField(param.thisObject, "mShowSeconds", true)
                    }
                })
                Logger.i(TAG, "自动秒: 已挂载 updateShowSeconds")
            } catch (e2: Throwable) {
                Logger.e(TAG, "自动秒 updateShowSeconds 也失败", e2)
            }
        }
    }

    /**
     * 组合规则：最终文本 = [时段·左] [星期·左] 底座时间 [星期·右] [时段·右]，
     * 左则时段在前，右则星期在前，各部分以单个空格连接。
     */
    private fun composeClockText(original: String, config: ClockConfig): String {
        val now = Calendar.getInstance()
        var base = if (config.customFormat.isNotEmpty()) {
            formatCustom(now, config.customFormat) ?: original
        } else {
            original
        }

        // 12 小时制下剥离系统自带「上午/下午」，避免与自有时段重复（自定义格式由用户自行控制）
        if (config.periodEnabled && config.customFormat.isEmpty()) {
            base = stripSystemAmPm(base, now)
        }

        val leftParts = ArrayList<String>(2)
        val rightParts = ArrayList<String>(2)

        if (config.periodEnabled) {
            getPeriodWord(now, config)?.let { word ->
                if (config.periodPosition == 0) leftParts.add(word) else rightParts.add(word)
            }
        }
        if (config.weekdayEnabled) {
            val weekday = getWeekday(config.weekdayFormat, now)
            if (config.weekdayPosition == 0) leftParts.add(weekday) else rightParts.add(weekday)
        }

        return buildString {
            if (leftParts.isNotEmpty()) {
                append(leftParts.joinToString(" ")).append(' ')
            }
            append(base)
            if (rightParts.isNotEmpty()) {
                append(' ').append(rightParts.joinToString(" "))
            }
        }
    }

    private fun formatCustom(now: Calendar, pattern: String): String? {
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(now.time)
        } catch (e: Throwable) {
            Logger.w(TAG, "自定义时钟格式 \"$pattern\" 无效，已回退系统格式")
            null
        }
    }

    /** 按当前时刻渲染系统 a token（上午/下午/AM/PM）并从文本中剥离。 */
    private fun stripSystemAmPm(text: String, now: Calendar): String {
        val marker = try {
            SimpleDateFormat("a", Locale.getDefault()).format(now.time)
        } catch (_: Throwable) {
            return text
        }
        if (marker.isEmpty()) return text
        return text.replace(Regex("\\s*" + Regex.escape(marker) + "\\s*"), " ").trim()
    }

    private fun getPeriodWord(now: Calendar, config: ClockConfig): String? {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return when (config.periodScheme) {
            1 -> periodWordFor(PERIOD_RANGES_6, PERIOD_WORDS_6, hour)
            2 -> periodWordFor(PERIOD_RANGES_2, PERIOD_WORDS_2, hour)
            3 -> config.customSegments.firstOrNull { hour >= it.startHour && hour <= it.endHour }?.name
            else -> null
        }
    }

    private fun periodWordFor(ranges: Array<IntArray>, words: Array<String>, hour: Int): String? {
        ranges.forEachIndexed { index, range ->
            if (hour in range[0]..range[1]) return words[index]
        }
        return null
    }

    private fun getWeekday(format: Int, now: Calendar): String {
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        return when (format) {
            0 -> CHINESE_WEEKDAYS[dayOfWeek - 1]
            1 -> SimpleDateFormat("EEE", Locale.ENGLISH).format(now.time)
            2 -> SimpleDateFormat("EEEE", Locale.ENGLISH).format(now.time)
            else -> CHINESE_WEEKDAYS[dayOfWeek - 1]
        }
    }

    /** 检测格式中是否含未转义的秒级 token（s / SSS 等）。 */
    private fun containsSecondsToken(format: String): Boolean {
        var inQuote = false
        var index = 0
        while (index < format.length) {
            val c = format[index]
            if (c == '\'') {
                inQuote = !inQuote
            } else if (!inQuote && (c == 's' || c == 'S')) {
                return true
            }
            index++
        }
        return false
    }
}

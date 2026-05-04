package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideStatusBarIconHook {

    private const val TAG = "HideStatusBarIcon"
    private const val IMPL_CLASS = "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl"

    private val SLOT_LABELS = linkedMapOf(
        "alarm_clock" to "闹钟",
        "bluetooth" to "蓝牙",
        "cast" to "投屏",
        "hotspot" to "热点",
        "headset" to "耳机",
        "mute" to "静音",
        "vibrate" to "振动",
        "zen" to "勿扰",
        "rotate" to "旋转锁定",
        "location" to "定位",
        "microphone" to "麦克风",
        "camera" to "摄像头",
        "sensors_off" to "传感器关闭",
        "screen_record" to "录屏",
        "data_saver" to "流量节省",
        "managed_profile" to "工作资料",
        "tty" to "TTY",
        "wifi" to "WiFi",
        "mobile" to "移动信号",
        "airplane" to "飞行模式",
        "vpn" to "VPN",
        "ethernet" to "以太网",
        "no_sims" to "无SIM卡"
    )

    val iconOptions: List<Pair<String, String>>
        get() = SLOT_LABELS.entries.map { it.key to it.value }

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        val hiddenSlots = XposedPrefs.getFeatureStringSet(
            lpparam, "com.android.systemui", "hide_status_bar_icon", emptySet()
        )
        if (hiddenSlots.isEmpty()) return

        Logger.i(TAG, "Hidden slots: $hiddenSlots")

        try {
            val implClass = XposedHelpers.findClass(IMPL_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                implClass,
                "setIconVisibility",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val slot = param.args[0] as? String ?: return
                        if (slot in hiddenSlots) {
                            param.args[1] = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                implClass,
                "setIconVisibility",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val slot = param.args[0] as? String ?: return
                        if (slot in hiddenSlots) {
                            param.args[1] = false
                        }
                    }
                }
            )

            Logger.i(TAG, "Hooked StatusBarIconControllerImpl.setIconVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook failed", e)
        }
    }
}

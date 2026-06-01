package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object HideStatusBarIconHook : FeatureHook {

    private const val HOOK_NAME = "HideStatusBarIcon"

    private var hiddenSlots: Set<String> = emptySet()

    private val SLOT_FIELD_TO_KEY = mapOf(
        "mSlotAlarmClock" to "alarm_clock",
        "mSlotBluetooth" to "bluetooth",
        "mSlotCast" to "cast",
        "mSlotHotspot" to "hotspot",
        "mSlotHeadset" to "headset",
        "mSlotMute" to "mute",
        "mSlotVibrate" to "vibrate",
        "mSlotZen" to "zen",
        "mSlotRotate" to "rotate",
        "mSlotLocation" to "location",
        "mSlotMicrophone" to "microphone",
        "mSlotCamera" to "camera",
        "mSlotSensorsOff" to "sensors_off",
        "mSlotScreenRecord" to "screen_record",
        "mSlotDataSaver" to "data_saver",
        "mSlotManagedProfile" to "managed_profile",
        "mSlotTty" to "tty",
    )

    private var resolvedSlots: Set<String> = emptySet()

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

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_status_bar_icon")) return
        if (lpparam.packageName != "com.android.systemui") return

        hiddenSlots = XposedPrefs.getFeatureStringSet(
            lpparam, packageName, "hide_status_bar_icon", emptySet()
        )
        if (hiddenSlots.isEmpty()) return

        Logger.i(HOOK_NAME, "Hidden slots: $hiddenSlots")

        when {
            FlymeVersionUtils.isFlyme12() -> mountFlyme12(lpparam)
            FlymeVersionUtils.isFlyme11() -> mountFlyme11(lpparam)
            FlymeVersionUtils.isFlyme10() -> mountFlyme10(lpparam)
            else -> mountFlyme10(lpparam)
        }
    }

    private fun mountFlyme12(lpparam: XC_LoadPackage.LoadPackageParam) {
        val implClass = findClassByFeature(lpparam) ?: run {
            Logger.e(HOOK_NAME, "Flyme 12: Cannot find StatusBarIconControllerImpl")
            return
        }

        hookSetIconVisibility(implClass)

        try {
            val psbpClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy",
                lpparam.classLoader
            )
            for (ctor in psbpClass.declaredConstructors) {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mapping = mutableMapOf<String, String>()
                        for (field in psbpClass.declaredFields) {
                            if (field.type == String::class.java && field.name.startsWith("mSlot")) {
                                field.isAccessible = true
                                val value = field.get(param.thisObject) as? String ?: continue
                                val key = SLOT_FIELD_TO_KEY[field.name] ?: continue
                                mapping[key] = value
                            }
                        }
                        resolvedSlots = hiddenSlots.mapNotNull { mapping[it] }.toSet()
                        Logger.i(HOOK_NAME, "Resolved slots: $resolvedSlots")
                    }
                })
            }
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook PhoneStatusBarPolicy failed", e)
        }

        Logger.i(HOOK_NAME, "Hooked Flyme 12: ${implClass.name}")
    }

    private fun mountFlyme11(lpparam: XC_LoadPackage.LoadPackageParam) {
        val implClass = findClassByFeature(lpparam) ?: run {
            Logger.e(HOOK_NAME, "Flyme 11: Cannot find StatusBarIconControllerImpl")
            return
        }

        hookSetIconVisibility(implClass)
        Logger.i(HOOK_NAME, "Hooked Flyme 11: ${implClass.name}")
    }

    private fun mountFlyme10(lpparam: XC_LoadPackage.LoadPackageParam) {
        val knownPath = "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl"
        val implClass = try {
            XposedHelpers.findClass(knownPath, lpparam.classLoader)
        } catch (e: Throwable) {
            findClassByFeature(lpparam)
        }

        if (implClass == null) {
            Logger.e(HOOK_NAME, "Flyme 10: Cannot find StatusBarIconControllerImpl")
            return
        }

        hookSetIconVisibility(implClass)
        Logger.i(HOOK_NAME, "Hooked Flyme 10: ${implClass.name}")
    }

    private fun hookSetIconVisibility(implClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                implClass,
                "setIconVisibility",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val slot = param.args[0] as? String ?: return
                        if (slot in hiddenSlots || slot in resolvedSlots) {
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
                        if (slot in hiddenSlots || slot in resolvedSlots) {
                            param.args[1] = false
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook setIconVisibility failed", e)
        }
    }

    private fun findClassByFeature(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
        try {
            val dexFile = dalvik.system.DexFile(lpparam.appInfo.sourceDir)

            dexFile.entries().iterator().forEach { className ->
                if (className.endsWith("StatusBarIconControllerImpl")) {
                    try {
                        val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                        clazz.getDeclaredMethod("setIconVisibility", String::class.java, Boolean::class.java)
                        return clazz
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(HOOK_NAME, "findClassByFeature failed", e)
        }

        return null
    }
}

package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

/**
 * 隐藏状态栏系统图标。
 *
 * 两类：
 * 1) 经典 slot（闹钟/蓝牙/热点…）：setIconVisibility 压 false
 * 2) 新 pipeline（WiFi/移动信号）：View 层 GONE（setIconVisibility 管不了）
 */
object HideStatusBarIconHook : FeatureHook {

    private const val TAG = "HideStatusBarIcon"

    /** 用户可选：日常可见 / 条件触发。不含 TTY、以太网等冷门项。 */
    private val SLOT_LABELS = linkedMapOf(
        "bluetooth" to "蓝牙",
        "wifi" to "WiFi",
        "mobile" to "移动信号",
        "alarm_clock" to "闹钟",
        "hotspot" to "热点",
        "headset" to "耳机",
        "mute" to "静音",
        "vibrate" to "振动",
        "zen" to "勿扰",
        "rotate" to "旋转锁定",
        "location" to "定位",
        "microphone" to "麦克风",
        "camera" to "摄像头",
        "cast" to "投屏",
        "screen_record" to "录屏",
        "airplane" to "飞行模式",
        "vpn" to "VPN",
        "nfc" to "NFC",
        "data_saver" to "流量节省",
        "sensors_off" to "传感器关闭",
        "no_sims" to "无SIM卡",
    )

    /** UI key -> PhoneStatusBarPolicy 字段 */
    private val KEY_TO_FIELD = mapOf(
        "alarm_clock" to "mSlotAlarmClock",
        "bluetooth" to "mSlotBluetooth",
        "cast" to "mSlotCast",
        "hotspot" to "mSlotHotspot",
        "headset" to "mSlotHeadset",
        "mute" to "mSlotMute",
        "vibrate" to "mSlotVibrate",
        "zen" to "mSlotZen",
        "rotate" to "mSlotRotate",
        "location" to "mSlotLocation",
        "microphone" to "mSlotMicrophone",
        "camera" to "mSlotCamera",
        "sensors_off" to "mSlotSensorsOff",
        "screen_record" to "mSlotScreenRecord",
        "data_saver" to "mSlotDataSaver",
        "nfc" to "mSlotNFC",
    )

    @Volatile
    private var blockedSlots: Set<String> = emptySet()

    @Volatile
    private var hideWifi = false

    @Volatile
    private var hideMobile = false

    val iconOptions: List<Pair<String, String>>
        get() = SLOT_LABELS.entries.map { it.key to it.value }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_status_bar_icon")) return
        if (lpparam.packageName != "com.android.systemui") return

        val hiddenKeys = XposedPrefs.getFeatureStringSet(
            lpparam, packageName, "hide_status_bar_icon", emptySet()
        )
        if (hiddenKeys.isEmpty()) return

        hideWifi = "wifi" in hiddenKeys
        hideMobile = "mobile" in hiddenKeys
        blockedSlots = hiddenKeys.toSet()

        Logger.i(TAG, "已加载 keys=$hiddenKeys hideWifi=$hideWifi hideMobile=$hideMobile")

        resolveSlotsFromPolicy(lpparam, hiddenKeys)
        hookController(lpparam)
        if (hideWifi || hideMobile) hookPipelineViews(lpparam)
    }

    private fun resolveSlotsFromPolicy(
        lpparam: XC_LoadPackage.LoadPackageParam,
        hiddenKeys: Set<String>
    ) {
        val policyClass = findClass(
            lpparam,
            "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
        ) ?: return

        for (ctor in policyClass.declaredConstructors) {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resolved = hiddenKeys.toMutableSet()
                    for ((key, fieldName) in KEY_TO_FIELD) {
                        if (key !in hiddenKeys) continue
                        try {
                            val v = XposedHelpers.getObjectField(param.thisObject, fieldName) as? String
                            if (!v.isNullOrEmpty()) resolved.add(v)
                        } catch (_: Throwable) {
                        }
                    }
                    // StatusBarSignalPolicy 飞行模式 slot 字段
                    if ("airplane" in hiddenKeys) {
                        resolved.add("airplane")
                    }
                    if ("vpn" in hiddenKeys) resolved.add("vpn")
                    if ("no_sims" in hiddenKeys) resolved.add("no_sims")
                    if (hideWifi) {
                        resolved.add("wifi")
                        resolved.add("dual_wifi")
                    }
                    if (hideMobile) {
                        resolved.add("mobile")
                        resolved.add("phone_signal")
                    }
                    blockedSlots = resolved
                    Logger.d(TAG) { "已解析 slots=$resolved" }
                }
            })
        }

        // 飞行模式 / VPN 等来自 StatusBarSignalPolicy
        val signalPolicy = findClass(
            lpparam,
            "com.android.systemui.statusbar.phone.StatusBarSignalPolicy"
        )
        if (signalPolicy != null && ("airplane" in hiddenKeys || "vpn" in hiddenKeys || "ethernet" in hiddenKeys)) {
            for (ctor in signalPolicy.declaredConstructors) {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val extra = blockedSlots.toMutableSet()
                        for (name in listOf("mSlotAirplane", "mSlotVpn", "mSlotEthernet", "mSlotNoSims")) {
                            try {
                                val v = XposedHelpers.getObjectField(param.thisObject, name) as? String
                                if (!v.isNullOrEmpty()) {
                                    when {
                                        name.contains("Airplane") && "airplane" in hiddenKeys -> extra.add(v)
                                        name.contains("Vpn") && "vpn" in hiddenKeys -> extra.add(v)
                                        name.contains("Ethernet") && "ethernet" in hiddenKeys -> extra.add(v)
                                        name.contains("NoSims") && "no_sims" in hiddenKeys -> extra.add(v)
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        blockedSlots = extra
                    }
                })
            }
        }
    }

    private fun hookController(lpparam: XC_LoadPackage.LoadPackageParam) {
        val implClass = findControllerClass(lpparam) ?: run {
            Logger.w(TAG, "未找到 StatusBarIconControllerImpl")
            return
        }

        hookSetIconVisibility(implClass)

        try {
            XposedBridge.hookAllMethods(
                implClass,
                "setIcon",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val slot = param.args.getOrNull(0) as? String ?: return
                        if (shouldBlock(slot)) forceHide(param.thisObject, slot)
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "setIcon Hook 挂载失败", e)
        }

        // 新 pipeline 注册移动图标时直接跳过
        if (hideMobile) {
            try {
                XposedHelpers.findAndHookMethod(
                    implClass,
                    "setNewMobileIconSubIds",
                    List::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.w(TAG, "setNewMobileIconSubIds 失败（可忽略）")
            }
        }

        if (hideWifi) {
            try {
                XposedHelpers.findAndHookMethod(
                    implClass,
                    "setNewWifiIcon",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    }
                )
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(
                    implClass,
                    "setFlymeWifiIcon",
                    String::class.java,
                    "com.flyme.systemui.statusbar.net.wifi.WifiIconState",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        Logger.i(TAG, "controller=${implClass.name}")
    }

    /** 新 pipeline View 自己管可见性，强制 GONE */
    private fun hookPipelineViews(lpparam: XC_LoadPackage.LoadPackageParam) {
        val viewClasses = mutableListOf<String>()
        if (hideMobile) {
            viewClasses += listOf(
                "com.flyme.systemui.statusbar.net.mobile.ui.view.FlymeModernStatusBarMobileView",
                "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            )
        }
        if (hideWifi) {
            viewClasses += listOf(
                "com.flyme.systemui.statusbar.net.wifi.FlymeStatusBarWifiView",
                "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            )
        }

        for (name in viewClasses) {
            val clazz = findClass(lpparam, name) ?: continue
            try {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        v.visibility = View.GONE
                        v.post { v.visibility = View.GONE }
                    }
                })
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "setVisibility",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args[0] = View.GONE
                        }
                    }
                )
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "setVisibleState",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // StatusBarIconView.STATE_HIDDEN = 2
                            param.args[0] = 2
                        }
                    }
                )
            } catch (_: Throwable) {
            }
            Logger.i(TAG, "已挂载 pipeline view: $name")
        }
    }

    private fun hookSetIconVisibility(implClass: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val slot = param.args[0] as? String ?: return
                if (shouldBlock(slot)) param.args[1] = false
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                implClass, "setIconVisibility",
                String::class.java, Boolean::class.javaPrimitiveType, hook
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "setIconVisibility 挂载失败", e)
        }
        try {
            XposedHelpers.findAndHookMethod(
                implClass, "setIconVisibility",
                String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, hook
            )
        } catch (_: Throwable) {
        }
    }

    private fun forceHide(controller: Any, slot: String) {
        try {
            XposedHelpers.callMethod(controller, "setIconVisibility", slot, false)
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(controller, "setIconVisibility", slot, false, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun shouldBlock(slot: String): Boolean {
        val blocked = blockedSlots
        if (slot in blocked) return true
        if (hideWifi && (slot.contains("wifi", true))) return true
        if (hideMobile && (slot.contains("mobile", true) || slot.contains("phone", true))) return true
        return blocked.any { slot.startsWith("${it}_") }
    }

    private fun findControllerClass(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
        for (name in listOf(
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
            "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl",
        )) {
            findClass(lpparam, name)?.let { return it }
        }
        return try {
            val dex = dalvik.system.DexFile(lpparam.appInfo.sourceDir)
            dex.entries().asSequence()
                .filter { it.endsWith("StatusBarIconControllerImpl") }
                .mapNotNull { cn ->
                    try {
                        val c = XposedHelpers.findClass(cn, lpparam.classLoader)
                        c.getDeclaredMethod(
                            "setIconVisibility",
                            String::class.java,
                            Boolean::class.javaPrimitiveType
                        )
                        c
                    } catch (_: Throwable) {
                        null
                    }
                }
                .firstOrNull()
        } catch (e: Exception) {
            Logger.e(TAG, "dex 扫描失败", e)
            null
        }
    }

    private fun findClass(lpparam: XC_LoadPackage.LoadPackageParam, name: String): Class<*>? {
        return try {
            XposedHelpers.findClass(name, lpparam.classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}

package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

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

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("hide_status_bar_icon")) return
        if (ctx.packageName != "com.android.systemui") return

        val hiddenKeys = ctx.featureStringSet(
            "hide_status_bar_icon", emptySet()
        )
        if (hiddenKeys.isEmpty()) return

        hideWifi = "wifi" in hiddenKeys
        hideMobile = "mobile" in hiddenKeys
        blockedSlots = hiddenKeys.toSet()

        Logger.i(TAG, "已加载 keys=$hiddenKeys hideWifi=$hideWifi hideMobile=$hideMobile")

        resolveSlotsFromPolicy(ctx, hiddenKeys)
        hookController(ctx)
        if (hideWifi || hideMobile) hookPipelineViews(ctx)
    }

    private fun resolveSlotsFromPolicy(
        ctx: HookContext,
        hiddenKeys: Set<String>
    ) {
        val policyClass = findClass(
            ctx.classLoader,
            "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
        ) ?: return

        for (ctor in policyClass.declaredConstructors) {
            Reflect.hookConstructorOn(ctx.api, policyClass, *ctor.parameterTypes) { chain ->
                val result = chain.proceed()
                val resolved = hiddenKeys.toMutableSet()
                for ((key, fieldName) in KEY_TO_FIELD) {
                    if (key !in hiddenKeys) continue
                    try {
                        val v = Reflect.getObjectField(chain.getThisObject(), fieldName) as? String
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
                result
            }
        }

        // 飞行模式 / VPN 等来自 StatusBarSignalPolicy
        val signalPolicy = findClass(
            ctx.classLoader,
            "com.android.systemui.statusbar.phone.StatusBarSignalPolicy"
        )
        if (signalPolicy != null && ("airplane" in hiddenKeys || "vpn" in hiddenKeys || "ethernet" in hiddenKeys)) {
            for (ctor in signalPolicy.declaredConstructors) {
                Reflect.hookConstructorOn(ctx.api, signalPolicy, *ctor.parameterTypes) { chain ->
                    val result = chain.proceed()
                    val extra = blockedSlots.toMutableSet()
                    for (name in listOf("mSlotAirplane", "mSlotVpn", "mSlotEthernet", "mSlotNoSims")) {
                        try {
                            val v = Reflect.getObjectField(chain.getThisObject(), name) as? String
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
                    result
                }
            }
        }
    }

    private fun hookController(ctx: HookContext) {
        val implClass = findControllerClass(ctx) ?: run {
            Logger.w(TAG, "未找到 StatusBarIconControllerImpl")
            return
        }

        hookSetIconVisibility(ctx, implClass)

        try {
            Reflect.hookAllMethods(
                ctx.api,
                implClass,
                "setIcon",
                block = { chain ->
                    val result = chain.proceed()
                    val slot = chain.getArgs().getOrNull(0) as? String
                    if (slot != null && shouldBlock(slot)) forceHide(ctx, chain.getThisObject(), slot)
                    result
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "setIcon Hook 挂载失败", e)
        }

        // 新 pipeline 注册移动图标时直接跳过
        if (hideMobile) {
            try {
                Reflect.hookMethodOn(
                    ctx.api,
                    implClass,
                    "setNewMobileIconSubIds",
                    List::class.java,
                ) { null }
            } catch (e: Throwable) {
                Logger.w(TAG, "setNewMobileIconSubIds 失败（可忽略）")
            }
        }

        if (hideWifi) {
            try {
                Reflect.hookMethodOn(
                    ctx.api,
                    implClass,
                    "setNewWifiIcon",
                ) { null }
            } catch (_: Throwable) {
            }
            try {
                Reflect.hookMethodOn(
                    ctx.api,
                    implClass,
                    "setFlymeWifiIcon",
                    String::class.java,
                    Reflect.findClass("com.flyme.systemui.statusbar.net.wifi.WifiIconState", ctx.classLoader),
                ) { null }
            } catch (_: Throwable) {
            }
        }

        Logger.i(TAG, "controller=${implClass.name}")
    }

    /** 新 pipeline View 自己管可见性，强制 GONE */
    private fun hookPipelineViews(ctx: HookContext) {
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
            val clazz = findClass(ctx.classLoader, name) ?: continue
            try {
                Reflect.hookAllConstructors(ctx.api, clazz) { chain ->
                    val result = chain.proceed()
                    val v = chain.getThisObject() as? View
                    if (v != null) {
                        v.visibility = View.GONE
                        v.post { v.visibility = View.GONE }
                    }
                    result
                }
            } catch (_: Throwable) {
            }
            try {
                Reflect.hookMethodOn(
                    ctx.api,
                    clazz,
                    "setVisibility",
                    Int::class.javaPrimitiveType,
                ) { chain ->
                    val args = chain.getArgs().toTypedArray()
                    args[0] = View.GONE
                    chain.proceed(args)
                }
            } catch (_: Throwable) {
            }
            try {
                Reflect.hookMethodOn(
                    ctx.api,
                    clazz,
                    "setVisibleState",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ) { chain ->
                    // StatusBarIconView.STATE_HIDDEN = 2
                    val args = chain.getArgs().toTypedArray()
                    args[0] = 2
                    chain.proceed(args)
                }
            } catch (_: Throwable) {
            }
            Logger.i(TAG, "已挂载 pipeline view: $name")
        }
    }

    private fun hookSetIconVisibility(ctx: HookContext, implClass: Class<*>) {
        val hook: (io.github.libxposed.api.XposedInterface.Chain) -> Any? = { chain ->
            val slot = chain.getArg(0) as? String
            if (slot != null && shouldBlock(slot)) {
                val args = chain.getArgs().toTypedArray()
                args[1] = false
                chain.proceed(args)
            } else {
                chain.proceed()
            }
        }
        try {
            Reflect.hookMethodOn(
                ctx.api, implClass, "setIconVisibility",
                String::class.java, Boolean::class.javaPrimitiveType
            ) { chain -> hook(chain) }
        } catch (e: Throwable) {
            Logger.e(TAG, "setIconVisibility 挂载失败", e)
        }
        try {
            Reflect.hookMethodOn(
                ctx.api, implClass, "setIconVisibility",
                String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ) { chain -> hook(chain) }
        } catch (_: Throwable) {
        }
    }

    private fun forceHide(ctx: HookContext, controller: Any, slot: String) {
        try {
            Reflect.callMethod(ctx.api, controller, "setIconVisibility", slot, false)
        } catch (_: Throwable) {
            try {
                Reflect.callMethod(ctx.api, controller, "setIconVisibility", slot, false, 0)
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

    private fun findControllerClass(ctx: HookContext): Class<*>? {
        for (name in listOf(
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
            "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl",
        )) {
            findClass(ctx.classLoader, name)?.let { return it }
        }
        return try {
            val activityThread = Reflect.findClass("android.app.ActivityThread", null)
            val app = Reflect.callStaticMethod(ctx.api, activityThread, "currentApplication") as? android.app.Application
            val sourceDir = app?.applicationInfo?.sourceDir ?: return null
            val dex = dalvik.system.DexFile(sourceDir)
            dex.entries().asSequence()
                .filter { it.endsWith("StatusBarIconControllerImpl") }
                .mapNotNull { cn ->
                    try {
                        val c = Reflect.findClass(cn, ctx.classLoader)
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

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? {
        return try {
            Reflect.findClass(name, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}
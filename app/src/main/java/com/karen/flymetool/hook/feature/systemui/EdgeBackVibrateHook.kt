package com.karen.flymetool.hook.feature.systemui

import android.os.VibrationEffect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 侧滑返回震动强度。
 *
 * 系统：triggerBack → Vibrator.vibrate(VibrationEffect.get(31025))
 * 策略：
 * 1. updateIsEnabledMZ 后强制 mEdgeBackVibrateFeedBack=true
 * 2. Hook VibrationEffect.get(int)：id==31025 时换成 createOneShot(时长, 振幅)
 * 3. 强度 0 = createOneShot(1,1) 近似静音（仍走 vibrate 路径，避免异常）
 */
object EdgeBackVibrateHook : FeatureHook {

    private const val HOOK_NAME = "EdgeBackVibrate"
    private const val FEATURE_KEY = "edge_back_vibrate_intensity"
    private const val HANDLER = "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
    private const val EDGE_BACK_EFFECT_ID = 31025
    private const val DEFAULT_INTENSITY = 50
    private const val VIBRATE_DURATION_MS = 30L

    @Volatile
    private var intensity: Int = DEFAULT_INTENSITY

    private val triggerHooked = AtomicBoolean(false)

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return

        intensity = XposedPrefs.getFeatureValue(
            lpparam, packageName, FEATURE_KEY, DEFAULT_INTENSITY
        ).coerceIn(0, 100)

        hookForceEnableFlag(lpparam)
        hookVibrationEffectGet()
        Logger.i(HOOK_NAME, "Loaded, intensity=$intensity")
    }

    private fun hookForceEnableFlag(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(HANDLER, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "updateIsEnabledMZ",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (intensity > 0) {
                                XposedHelpers.setBooleanField(
                                    param.thisObject,
                                    "mEdgeBackVibrateFeedBack",
                                    true
                                )
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            // 构造后从 mBackCallback 拿具体类，再 hook triggerBack 强制 flag（只挂一次）
            for (ctor in clazz.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!triggerHooked.compareAndSet(false, true)) return
                            try {
                                val handler = param.thisObject
                                val cb = XposedHelpers.getObjectField(handler, "mBackCallback")
                                    ?: return
                                val m = cb.javaClass.getDeclaredMethod("triggerBack")
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        try {
                                            val outer = XposedHelpers.getSurroundingThis(p.thisObject)
                                                ?: handler
                                            XposedHelpers.setBooleanField(
                                                outer,
                                                "mEdgeBackVibrateFeedBack",
                                                intensity > 0
                                            )
                                        } catch (_: Throwable) {
                                        }
                                    }
                                })
                                Logger.i(HOOK_NAME, "Hooked ${cb.javaClass.name}.triggerBack flag")
                            } catch (t: Throwable) {
                                triggerHooked.set(false)
                                Logger.e(HOOK_NAME, "hook triggerBack flag failed", t)
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }
            Logger.i(HOOK_NAME, "Hooked updateIsEnabledMZ force flag")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook force flag failed", e)
        }
    }

    /** 直接改 get(31025) 的返回值，无需 hook 抽象 BackCallback */
    private fun hookVibrationEffectGet() {
        try {
            XposedHelpers.findAndHookMethod(
                VibrationEffect::class.java,
                "get",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val id = param.args[0] as? Int ?: return
                        if (id != EDGE_BACK_EFFECT_ID) return
                        try {
                            if (intensity <= 0) {
                                // 极弱脉冲，近似关闭
                                param.result = VibrationEffect.createOneShot(1L, 1)
                            } else {
                                val amp = ((intensity * 255) / 100).coerceIn(1, 255)
                                param.result =
                                    VibrationEffect.createOneShot(VIBRATE_DURATION_MS, amp)
                            }
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "replace effect failed", t)
                        }
                    }
                }
            )
            Logger.i(HOOK_NAME, "Hooked VibrationEffect.get(31025)")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook VibrationEffect.get failed", e)
        }
    }
}

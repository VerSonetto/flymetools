package com.karen.flymetool.hook.feature.systemui

import android.os.VibrationEffect
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
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

    private const val TAG = "EdgeBackVibrate"
    private const val FEATURE_KEY = "edge_back_vibrate_intensity"
    private const val HANDLER = "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
    private const val EDGE_BACK_EFFECT_ID = 31025
    private const val DEFAULT_INTENSITY = 50
    private const val VIBRATE_DURATION_MS = 30L

    @Volatile
    private var intensity: Int = DEFAULT_INTENSITY

    private val triggerHooked = AtomicBoolean(false)

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled(FEATURE_KEY)) return
        if (ctx.packageName != "com.android.systemui") return

        intensity = ctx.featureValue(
            FEATURE_KEY, DEFAULT_INTENSITY
        ).coerceIn(0, 100)

        hookForceEnableFlag(ctx)
        hookVibrationEffectGet(ctx)
        Logger.i(TAG, "已加载，强度=$intensity")
    }

    private fun hookForceEnableFlag(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(HANDLER, ctx.classLoader)
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "updateIsEnabledMZ",
            ) { chain ->
                val result = chain.proceed()
                try {
                    if (intensity > 0) {
                        Reflect.setBooleanField(
                            chain.getThisObject(),
                            "mEdgeBackVibrateFeedBack",
                            true
                        )
                    }
                } catch (_: Throwable) {
                }
                result
            }
            // 构造后从 mBackCallback 拿具体类，再 hook triggerBack 强制 flag（只挂一次）
            for (ctor in clazz.declaredConstructors) {
                try {
                    Reflect.hookConstructorOn(ctx.api, clazz, *ctor.parameterTypes) { chain ->
                        val result = chain.proceed()
                        if (!triggerHooked.compareAndSet(false, true)) return@hookConstructorOn result
                        try {
                            val handler = chain.getThisObject()
                            val cb = Reflect.getObjectField(handler, "mBackCallback")
                                ?: return@hookConstructorOn result
                            val m = cb.javaClass.getDeclaredMethod("triggerBack")
                            Reflect.hookMethod(ctx.api, m) { chain2 ->
                                try {
                                    val outer = Reflect.getSurroundingThis(chain2.getThisObject())
                                        ?: handler
                                    Reflect.setBooleanField(
                                        outer,
                                        "mEdgeBackVibrateFeedBack",
                                        intensity > 0
                                    )
                                } catch (_: Throwable) {
                                }
                                chain2.proceed()
                            }
                            Logger.i(TAG, "已挂载 ${cb.javaClass.name}.triggerBack flag")
                        } catch (t: Throwable) {
                            triggerHooked.set(false)
                            Logger.e(TAG, "挂载 triggerBack flag 失败", t)
                        }
                        result
                    }
                } catch (_: Throwable) {
                }
            }
            Logger.i(TAG, "已挂载 updateIsEnabledMZ 强制 flag")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载强制 flag 失败", e)
        }
    }

    /** 直接改 get(31025) 的返回值，无需 hook 抽象 BackCallback */
    private fun hookVibrationEffectGet(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                VibrationEffect::class.java,
                "get",
                Int::class.javaPrimitiveType,
            ) { chain ->
                val id = chain.getArg(0) as? Int ?: return@hookMethodOn chain.proceed()
                if (id != EDGE_BACK_EFFECT_ID) return@hookMethodOn chain.proceed()
                try {
                    if (intensity <= 0) {
                        // 极弱脉冲，近似关闭
                        return@hookMethodOn VibrationEffect.createOneShot(1L, 1)
                    } else {
                        val amp = ((intensity * 255) / 100).coerceIn(1, 255)
                        return@hookMethodOn VibrationEffect.createOneShot(VIBRATE_DURATION_MS, amp)
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "替换振动效果失败", t)
                    chain.proceed()
                }
            }
            Logger.i(TAG, "已挂载 VibrationEffect.get(31025)")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 VibrationEffect.get 失败", e)
        }
    }
}
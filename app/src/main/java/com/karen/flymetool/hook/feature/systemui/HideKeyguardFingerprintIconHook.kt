package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

/**
 * 隐藏锁屏指纹图标（UDFPS 图标 + DeviceEntry 指纹态）。
 * 仅挡图标显示，不主动关指纹监听；触摸/解锁仍走系统原逻辑。
 */
object HideKeyguardFingerprintIconHook : FeatureHook {

    private const val AUTH_CONTROLLER = "com.android.systemui.biometrics.AuthController"
    private const val UDFPS_CONTROLLER = "com.android.systemui.biometrics.UdfpsController"
    private const val DEVICE_ENTRY_ICON = "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"
    private const val TAG = "HideKeyguardFpIcon"
    private const val FEATURE_KEY = "hide_keyguard_fingerprint_icon"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled(FEATURE_KEY)) return
        if (ctx.packageName != "com.android.systemui") return

        blockShow(ctx, AUTH_CONTROLLER)
        blockShow(ctx, UDFPS_CONTROLLER)
        forceDismissOnShowBlocked(ctx)
        hideDeviceEntryFingerprint(ctx)

        Logger.i(TAG, "已加载")
    }

    /** 拦截 showFingerprintIcon，禁止把图标再亮出来 */
    private fun blockShow(ctx: HookContext, className: String) {
        try {
            val clazz = Reflect.findClass(className, ctx.classLoader)
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "showFingerprintIcon",
            ) { null }
            Logger.i(TAG, "已拦截 $className.showFingerprintIcon")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 $className.showFingerprintIcon 失败", e)
        }
    }

    /** 构造后清一次图标；shouldDismiss 恒 true 让系统更积极收起 */
    private fun forceDismissOnShowBlocked(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(UDFPS_CONTROLLER, ctx.classLoader)
            val dismiss: (io.github.libxposed.api.XposedInterface.Chain) -> Any? = { chain ->
                val result = chain.proceed()
                try {
                    Reflect.callMethod(ctx.api, chain.getThisObject(), "dismissFpIconWithoutAnim")
                } catch (_: Throwable) {
                    try {
                        Reflect.callMethod(ctx.api, chain.getThisObject(), "dismissFingerprintIcon")
                    } catch (_: Throwable) {
                    }
                }
                result
            }
            for (ctor in clazz.declaredConstructors) {
                try {
                    Reflect.hookConstructorOn(ctx.api, clazz, *ctor.parameterTypes) { chain -> dismiss(chain) }
                } catch (_: Throwable) {
                }
            }
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "shouldDismissFingerprintIcon",
            ) { true }

            Logger.i(TAG, "已挂载 UdfpsController dismiss + shouldDismiss")
        } catch (e: Throwable) {
            Logger.e(TAG, "forceDismiss 设置失败", e)
        }
    }

    /**
     * DeviceEntry 入口图标：指纹态时强制 GONE。
     * LOCK/UNLOCK 仍可显示；仅当 contentDescription 为指纹标签时也可 GONE 整个 view。
     * 更稳：getIconState(FINGERPRINT, *) 返回 StateSet.NOTHING，不画指纹帧。
     */
    private fun hideDeviceEntryFingerprint(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(DEVICE_ENTRY_ICON, ctx.classLoader)
            val iconTypeCl = Reflect.findClass(
                "$DEVICE_ENTRY_ICON\$IconType",
                ctx.classLoader
            )

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "getIconState",
                iconTypeCl,
                Boolean::class.javaPrimitiveType,
            ) { chain ->
                try {
                    val type = chain.getArg(0) ?: return@hookMethodOn chain.proceed()
                    if (type.toString().contains("FINGERPRINT")) {
                        // 与 IconType.NONE 相同：不展示指纹帧
                        return@hookMethodOn android.util.StateSet.NOTHING
                    }
                    chain.proceed()
                } catch (_: Throwable) {
                    chain.proceed()
                }
            }

            // 整 view 被设为 VISIBLE 时，若当前是指纹图标描述，则压回 GONE（兜底）
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "setVisibility",
                Int::class.javaPrimitiveType,
            ) { chain ->
                try {
                    val vis = chain.getArg(0) as Int
                    if (vis != View.VISIBLE && vis != View.INVISIBLE) return@hookMethodOn chain.proceed()
                    val view = chain.getThisObject() as View
                    val desc = view.contentDescription?.toString().orEmpty()
                    // 指纹相关无障碍文案时隐藏（LOCK/UNLOCK 一般不含 fingerprint）
                    if (desc.contains("fingerprint", ignoreCase = true) ||
                        desc.contains("指纹")
                    ) {
                        val args = chain.getArgs().toTypedArray()
                        args[0] = View.GONE
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                } catch (_: Throwable) {
                    chain.proceed()
                }
            }

            Logger.i(TAG, "已挂载 DeviceEntryIconView 指纹隐藏")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 DeviceEntryIconView 失败", e)
        }
    }
}
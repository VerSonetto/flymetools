package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object NotificationCardRadiusHook : FeatureHook {

    private const val TAG = "NotificationCardRadius"
    private const val CORNER_DIMEN = "notification_corner_radius"
    private const val BACKGROUND_DIMEN = "notification_background_radius"

    /** 目标资源 ID，-1 未解析。SystemUI 资源表进程内固定，首次解析后缓存全局有效 */
    private var cornerResId = -1
    private var backgroundResId = -1

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("notification_card_radius")) return
        if (ctx.packageName != "com.android.systemui") return

        val radiusDp = ctx.featureValue("notification_card_radius", 24)
        mount(ctx, radiusDp)
    }

    private fun mount(ctx: HookContext, radiusDp: Int) {
        hookResourcesDimension(ctx, radiusDp)
        hookRoundableState(ctx, radiusDp)
    }

    private fun isTargetDimen(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        if (cornerResId == -1) {
            cornerResId = resources.getIdentifier(CORNER_DIMEN, null, "com.android.systemui")
            backgroundResId = resources.getIdentifier(BACKGROUND_DIMEN, null, "com.android.systemui")
        }
        return resId == cornerResId || resId == backgroundResId
    }

    private fun hookResourcesDimension(ctx: HookContext, radiusDp: Int) {
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            ).toInt()

            Reflect.hookMethodOn(
                ctx.api,
                Resources::class.java,
                "getDimensionPixelSize",
                Int::class.java,
            ) { chain ->
                val result = chain.proceed()
                if (isTargetDimen(chain.getThisObject() as Resources, chain.getArg(0) as Int)) {
                    Logger.once(TAG, "dim", "已挂载通知卡圆角 = ${radiusPx}px")
                    return@hookMethodOn radiusPx
                }
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                Resources::class.java,
                "getDimension",
                Int::class.java,
            ) { chain ->
                val result = chain.proceed()
                if (isTargetDimen(chain.getThisObject() as Resources, chain.getArg(0) as Int)) {
                    return@hookMethodOn radiusPx.toFloat()
                }
                result
            }

            Logger.i(TAG, "已挂载 Resources.getDimension*，圆角=${radiusDp}dp")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Resources.getDimension* 失败", e)
        }
    }

    private fun hookRoundableState(ctx: HookContext, radiusDp: Int) {
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            val roundableStateClass = Reflect.findClass(
                "com.android.systemui.statusbar.notification.RoundableState",
                ctx.classLoader
            )

            Reflect.hookConstructorOn(
                ctx.api,
                roundableStateClass,
                android.view.View::class.java,
                Reflect.findClass("com.android.systemui.statusbar.notification.Roundable", ctx.classLoader),
                Float::class.java,
            ) { chain ->
                val args = chain.getArgs().toTypedArray()
                args[2] = radiusPx
                Logger.once(TAG, "roundable", "已挂载 RoundableState, maxRadius=${radiusPx}")
                chain.proceed(args)
            }

            Reflect.hookMethodOn(
                ctx.api,
                roundableStateClass,
                "setMaxRadius",
                Float::class.java,
            ) { chain ->
                val args = chain.getArgs().toTypedArray()
                args[0] = radiusPx
                Logger.once(TAG, "set_max_radius", "setMaxRadius 覆盖 = ${radiusPx}")
                chain.proceed(args)
            }

            Logger.i(TAG, "已挂载 RoundableState")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 RoundableState 失败", e)
        }
    }
}
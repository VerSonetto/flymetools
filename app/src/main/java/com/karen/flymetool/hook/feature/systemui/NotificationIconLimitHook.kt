package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object NotificationIconLimitHook : FeatureHook {

    private const val TAG = "NotificationIconLimit"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("notification_icon_limit")) return
        if (ctx.packageName != "com.android.systemui") return

        val maxIcons = ctx.featureValue("notification_icon_limit", 4)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(ctx, maxIcons)
            else -> hookLegacy(ctx, maxIcons)
        }
    }

    private fun hookFlyme12(ctx: HookContext, maxIcons: Int) {
        try {
            val clazz = Reflect.findClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                ctx.classLoader
            )

            // Flyme 12.6 把数量逻辑迁到 ViewModel -> ViewBinder -> setMaxIconsAmount
            // calculateIconXTranslations 实际用的是 mMaxIcons，不再是 mMaxStaticIcons
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "setMaxIconsAmount",
                Int::class.javaPrimitiveType,
            ) { chain ->
                val args = chain.getArgs().toTypedArray()
                args[0] = maxIcons
                chain.proceed(args)
            }

            // 兜底：initResources 里把两个字段都改掉，防止还有旧路径
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "initResources",
            ) { chain ->
                val result = chain.proceed()
                val thisObject = chain.getThisObject()
                Reflect.setObjectField(thisObject, "mMaxIcons", maxIcons)
                Reflect.setObjectField(thisObject, "mMaxStaticIcons", maxIcons)
                result
            }

            Logger.i(TAG, "通知图标数量限制(Flyme12): $maxIcons")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败（Flyme12）", e)
        }
    }

    private fun hookLegacy(ctx: HookContext, maxIcons: Int) {
        try {
            val clazz = Reflect.findClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                ctx.classLoader
            )

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "initResources",
            ) { chain ->
                val result = chain.proceed()
                val thisObject = chain.getThisObject()
                Reflect.setObjectField(thisObject, "mMaxStaticIcons", maxIcons)
                Logger.once(TAG, "max_static_icons", "mMaxStaticIcons = $maxIcons")
                result
            }

            Logger.i(TAG, "通知图标数量限制: $maxIcons")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}
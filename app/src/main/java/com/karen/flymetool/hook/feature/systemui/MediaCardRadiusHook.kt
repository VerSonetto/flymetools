package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object MediaCardRadiusHook : FeatureHook {

    private const val TAG = "MediaCardRadius"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("media_card_radius")) return
        if (ctx.packageName != "com.android.systemui") return

        val radiusDp = ctx.featureValue("media_card_radius", 14)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(ctx, radiusDp)
            FlymeVersionUtils.isFlyme11() -> hookFlyme11(ctx, radiusDp)
            FlymeVersionUtils.isFlyme10() -> hookFlyme10(ctx, radiusDp)
        }
    }

    private fun hookFlyme10(ctx: HookContext, radiusDp: Int) {
        hookLayout(ctx, radiusDp, "com.flyme.systemui.media.MediaCarouseTransitionLayout")
    }

    private fun hookFlyme11(ctx: HookContext, radiusDp: Int) {
        hookLayout(ctx, radiusDp, "com.flyme.systemui.media.MediaCarouseTransitionLayout")
    }

    private fun hookFlyme12(ctx: HookContext, radiusDp: Int) {
        hookLayout(ctx, radiusDp, "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout")
    }

    private fun hookLayout(ctx: HookContext, radiusDp: Int, classPath: String) {
        try {
            val clazz = Reflect.findClass(classPath, ctx.classLoader)
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            Reflect.hookConstructorOn(
                ctx.api,
                clazz,
                android.content.Context::class.java,
                android.util.AttributeSet::class.java,
            ) { chain ->
                val result = chain.proceed()
                val view = chain.getThisObject() as? View ?: return@hookConstructorOn result
                applyRadius(view, radiusPx)
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "onConfigurationChanged",
                android.content.res.Configuration::class.java,
            ) { chain ->
                val result = chain.proceed()
                val view = chain.getThisObject() as? View ?: return@hookMethodOn result
                applyRadius(view, radiusPx)
                result
            }

            Logger.i(TAG, "已挂载 $classPath，圆角=${radiusDp}dp")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 $classPath 失败", e)
        }
    }

    private fun applyRadius(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }
}
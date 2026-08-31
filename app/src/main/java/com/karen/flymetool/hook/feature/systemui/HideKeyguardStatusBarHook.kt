package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object HideKeyguardStatusBarHook : FeatureHook {

    private const val VIEW_CLASS = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
    private const val TAG = "HideKeyguardStatusBar"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("hide_keyguard_status_bar")) return
        if (ctx.packageName != "com.android.systemui") return

        try {
            val clazz = Reflect.findClass(VIEW_CLASS, ctx.classLoader)

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

            Logger.i(TAG, "已挂载 KeyguardStatusBarView.setVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}
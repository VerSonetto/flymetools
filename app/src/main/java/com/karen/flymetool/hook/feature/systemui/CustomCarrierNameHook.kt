package com.karen.flymetool.hook.feature.systemui

import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object CustomCarrierNameHook : FeatureHook {

    private const val TAG = "CustomCarrierName"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("custom_carrier_name")) return

        val name1 = ctx.featureString("custom_carrier_name", "")
        val name2 = ctx.featureString("custom_carrier_name_sim2", "")
        if (name1.isEmpty() && name2.isEmpty()) return

        val separator = when {
            FlymeVersionUtils.isFlyme10() -> "I"
            else -> "|"
        }

        hookTextView(ctx, name1, name2, separator)
        hookShadeCarrier(ctx, name1, name2)
    }

    private fun hookTextView(ctx: HookContext, name1: String, name2: String, separator: String) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                TextView::class.java,
                "setText", CharSequence::class.java,
            ) { chain ->
                val text = chain.getArg(0) as? CharSequence
                if (text == null || text.toString().isEmpty()) return@hookMethodOn chain.proceed()
                val args = chain.getArgs().toTypedArray()
                when (chain.getThisObject().javaClass.name) {
                    "com.android.keyguard.CarrierText" -> {
                        args[0] = if (name2.isNotEmpty()) "$name1$separator$name2" else name1
                    }
                    "com.android.systemui.statusbar.OperatorNameView" -> {
                        args[0] = name1
                    }
                }
                chain.proceed(args)
            }
            Logger.i(TAG, "已挂载 TextView.setText, 分隔符: $separator")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 TextView.setText 失败", e)
        }
    }

    private fun hookShadeCarrier(ctx: HookContext, name1: String, name2: String) {
        try {
            val clazz = Reflect.findClass("com.android.systemui.shade.carrier.ShadeCarrier", ctx.classLoader)
            Reflect.hookMethodOn(
                ctx.api, clazz, "setCarrierText", CharSequence::class.java,
            ) { chain ->
                if (chain.getArgs().isEmpty() || chain.getArg(0) !is CharSequence) return@hookMethodOn chain.proceed()
                val view = chain.getThisObject() as? View ?: return@hookMethodOn chain.proceed()
                val args = chain.getArgs().toTypedArray()
                val parent = view.parent as? ViewGroup
                if (parent != null) {
                    var simIndex = 0
                    var count = 0
                    for (i in 0 until parent.childCount) {
                        if (parent.getChildAt(i) === view) {
                            simIndex = count
                            break
                        }
                        if (clazz.isInstance(parent.getChildAt(i))) {
                            count++
                        }
                    }
                    args[0] = when (simIndex) {
                        0 -> if (name1.isNotEmpty()) name1 else name2
                        else -> if (name2.isNotEmpty()) name2 else name1
                    }
                } else {
                    args[0] = if (name2.isNotEmpty()) "$name1|$name2" else name1
                }
                chain.proceed(args)
            }
            Logger.i(TAG, "已挂载 ShadeCarrier.setCarrierText")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 ShadeCarrier 失败", e)
        }
    }
}
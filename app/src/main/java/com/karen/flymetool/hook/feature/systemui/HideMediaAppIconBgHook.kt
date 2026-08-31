package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object HideMediaAppIconBgHook : FeatureHook {

    private const val TAG = "HideMediaAppIconBg"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("hide_media_app_icon_bg")) return
        if (ctx.packageName != "com.android.systemui") return

        hookViewOnFinishInflate(ctx)
    }

    private fun hookViewOnFinishInflate(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                View::class.java,
                "onFinishInflate",
            ) { chain ->
                val result = chain.proceed()
                val view = chain.getThisObject() as? View
                if (view != null) {
                    try {
                        val resources = view.context.resources
                        val iconId = resources.getIdentifier("icon", "id", "com.android.systemui")
                        val albumArtId = resources.getIdentifier("album_art", "id", "com.android.systemui")

                        if (iconId != 0 && albumArtId != 0) {
                            val iconView = view.findViewById<View>(iconId)
                            val albumArtView = view.findViewById<View>(albumArtId)

                            if (iconView != null && albumArtView != null) {
                                iconView.background = null
                                Logger.once(TAG, "cleared_icon_bg", "已清除媒体图标背景")
                            }
                        }
                    } catch (_: Exception) {
                        // 布局差异等可恢复问题，静默忽略
                    }
                }
                result
            }

            Logger.i(TAG, "已挂载 View.onFinishInflate")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 View.onFinishInflate 失败", e)
        }
    }
}
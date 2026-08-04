package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideMediaAppIconBgHook : FeatureHook {

    private const val TAG = "HideMediaAppIconBg"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_media_app_icon_bg")) return
        if (lpparam.packageName != "com.android.systemui") return

        hookViewOnFinishInflate(lpparam)
    }

    private fun hookViewOnFinishInflate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return

                        try {
                            val resources = view.context.resources
                            val iconId = resources.getIdentifier("icon", "id", "com.android.systemui")
                            val albumArtId = resources.getIdentifier("album_art", "id", "com.android.systemui")

                            if (iconId == 0 || albumArtId == 0) return

                            val iconView = view.findViewById<View>(iconId)
                            val albumArtView = view.findViewById<View>(albumArtId)

                            if (iconView != null && albumArtView != null) {
                                iconView.background = null
                                Logger.once(TAG, "cleared_icon_bg", "已清除媒体图标背景")
                            }
                        } catch (_: Exception) {
                            // 布局差异等可恢复问题，静默忽略
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 View.onFinishInflate")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 View.onFinishInflate 失败", e)
        }
    }
}

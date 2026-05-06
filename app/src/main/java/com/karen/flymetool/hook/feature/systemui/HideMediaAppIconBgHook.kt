package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object HideMediaAppIconBgHook {

    private const val HOOK_NAME = "HideMediaAppIconBg"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        hookViewOnFinishInflate(lpparam)
    }

    /**
     * Hook View.onFinishInflate，通过特征定位媒体播放器视图
     * 特征：包含 icon 和 album_art 子视图的 ConstraintLayout
     */
    private fun hookViewOnFinishInflate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return

                        try {
                            // 获取资源 ID
                            val resources = view.context.resources
                            val iconId = resources.getIdentifier("icon", "id", "com.android.systemui")
                            val albumArtId = resources.getIdentifier("album_art", "id", "com.android.systemui")

                            if (iconId == 0 || albumArtId == 0) return

                            // 检查是否包含特征子视图
                            val iconView = view.findViewById<View>(iconId)
                            val albumArtView = view.findViewById<View>(albumArtId)

                            if (iconView != null && albumArtView != null) {
                                // 找到媒体播放器视图，清空 icon 的背景
                                iconView.background = null
                                Logger.once(HOOK_NAME, "Cleared media icon background")
                            }
                        } catch (e: Exception) {
                            // 忽略非媒体播放器视图的异常
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked View.onFinishInflate")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook View.onFinishInflate failed", e)
        }
    }
}

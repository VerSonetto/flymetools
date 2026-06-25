package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object ForceLiveNotificationHook : FeatureHook {

    private const val TAG = "ForceLiveNotification"
    private val iconColorCache = mutableMapOf<String, Int>()
    private var systemColor = -1

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_live_notification")) return

        val targetApps = XposedPrefs.getFeatureStringSet(
            lpparam, packageName, "force_live_notification", emptySet()
        )
        if (targetApps.isEmpty()) return

        try {
            hookStatusBarNotification(lpparam, targetApps)
            hookNotificationEntry(lpparam, targetApps)
            hookLiveNotificationController(lpparam)
            hookTickerController(lpparam, targetApps)
            revertLiveCardStyling(lpparam, targetApps)
            Logger.i(TAG, "Force live notification hooked for: $targetApps")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook failed", e)
        }
    }

    /**
     * NotificationContentView.buildContentContainer() 对实况通知做了两件事：
     * 1. setRoundForCustomNotification → setClipToOutline(true) + 圆角 outline
     * 2. setMargins(padding, padding, padding, padding) → 四边加间距
     *
     * 对标准通知模板来说，这些样式把内容区挤小了，操作按钮溢出被裁。
     * 这里执行完后把实况通知加的样式退掉，让通知保持标准卡片样式。
     */
    private fun revertLiveCardStyling(
        lpparam: XC_LoadPackage.LoadPackageParam,
        targetApps: Set<String>
    ) {
        val ncvClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.NotificationContentView",
            lpparam.classLoader
        )
        val sbnClass = XposedHelpers.findClass(
            "android.service.notification.StatusBarNotification", lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            ncvClass, "buildContentContainer", View::class.java, sbnClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sbn = param.args[1]
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg !in targetApps) return

                    val view = param.result as? View ?: return
                    view.setClipToOutline(false)
                    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    lp.setMargins(0, 0, 0, 0)
                    view.layoutParams = lp
                }
            }
        )
    }

    private fun hookLiveNotificationController(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.flyme.statusbar.livenotification.LiveNotificationController",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "onDarkChanged",
                java.util.ArrayList::class.java, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctrl = param.thisObject
                        val c = XposedHelpers.getIntField(ctrl, "mSystemColor")
                        if (c == 0) return
                        systemColor = c or -0x1000000
                        // onDarkChanged 只在 mLyricsInofo != null 时才调 updateStatusBarIcons，
                        // 对于普通通知胶囊，颜色不会刷新，需要手动触发
                        XposedHelpers.callMethod(ctrl, "updateStatusBarIcons", false)
                    }
                }
            )
        } catch (_: Throwable) { }
    }

    private fun hookTickerController(
        lpparam: XC_LoadPackage.LoadPackageParam,
        targetApps: Set<String>
    ) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.flyme.systemui.statusbar.ticker.NotificationTickController",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "tick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entry = param.args[0] ?: return
                    val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in targetApps) param.result = null
                }
            })
            XposedHelpers.findAndHookMethod(clazz, "updateNotificationTicker", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entry = param.args[0] ?: return
                    val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in targetApps) param.result = false
                }
            })
        } catch (_: Throwable) { }
    }

    private fun loadAppIconBitmap(pkg: String): android.graphics.Bitmap? {
        return try {
            val app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? android.app.Application ?: return null
            val drawable = app.packageManager.getApplicationIcon(pkg)
            val w = drawable.intrinsicWidth.coerceAtLeast(drawable.minimumWidth.coerceAtLeast(1))
            val h = drawable.intrinsicHeight.coerceAtLeast(drawable.minimumHeight.coerceAtLeast(1))
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (_: Throwable) { null }
    }

    private fun dominantColor(bmp: android.graphics.Bitmap): Int {
        val s = android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true)
        val pixels = IntArray(256)
        s.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        val counts = mutableMapOf<Int, Int>()
        for (p in pixels) {
            if (Color.alpha(p) < 128) continue
            val r = Color.red(p) / 16 * 16
            val g = Color.green(p) / 16 * 16
            val b = Color.blue(p) / 16 * 16
            val k = (r shl 16) or (g shl 8) or b
            counts[k] = (counts[k] ?: 0) + 1
        }
        val top = counts.entries.sortedByDescending { it.value }.take(3)
        val bestEntry = top.maxByOrNull { (k, _) ->
            val r = Color.red(k)
            val g = Color.green(k)
            val b = Color.blue(k)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0f else (max - min).toFloat() / max
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            if (lum in 40..220) (sat * 1000).toInt() else 0
        }
        val best: Int = bestEntry?.key ?: 0x888888
        return Color.rgb(Color.red(best), Color.green(best), Color.blue(best))
    }

    private fun hookStatusBarNotification(
        lpparam: XC_LoadPackage.LoadPackageParam,
        targetApps: Set<String>
    ) {
        val sbnClass = XposedHelpers.findClass(
            "android.service.notification.StatusBarNotification", lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(sbnClass, "isLive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result == true) return
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg in targetApps) param.result = true
            }
        })

        XposedHelpers.findAndHookMethod(sbnClass, "getCapsuleStatus", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if ((param.result as? Int) == 1) return
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg in targetApps) param.result = 1
            }
        })

        XposedHelpers.findAndHookMethod(sbnClass, "getCapsuleContent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val text = param.result as? CharSequence
                if (!text.isNullOrEmpty()) return
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg !in targetApps) return

                val notification = XposedHelpers.callMethod(sbn, "getNotification")
                val extras = XposedHelpers.getObjectField(notification, "extras") as? Bundle ?: return
                val title = extras.getString("android.title")
                val content = extras.getString("android.text")
                param.result = when {
                    !title.isNullOrEmpty() && !content.isNullOrEmpty() -> "$title: $content"
                    !title.isNullOrEmpty() -> title
                    !content.isNullOrEmpty() -> content
                    else -> ""
                }
            }
        })

        XposedHelpers.findAndHookMethod(sbnClass, "getCapsuleIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg !in targetApps) return
                val bmp = loadAppIconBitmap(pkg) ?: return
                param.result = Icon.createWithBitmap(bmp)
            }
        })

        XposedHelpers.findAndHookMethod(sbnClass, "getCapsuleBgColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg !in targetApps) return

                val cached = iconColorCache[pkg]
                if (cached != null) { param.result = cached; return }

                val bmp = loadAppIconBitmap(pkg) ?: return
                iconColorCache[pkg] = dominantColor(bmp)
                param.result = iconColorCache[pkg]!!
            }
        })

        XposedHelpers.findAndHookMethod(sbnClass, "getCapsuleContentColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sbn = param.thisObject
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg !in targetApps) return
                param.result = systemColor
            }
        })
    }

    private fun hookNotificationEntry(
        lpparam: XC_LoadPackage.LoadPackageParam,
        targetApps: Set<String>
    ) {
        val entryClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.collection.NotificationEntry",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(entryClass, "allowLive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result == true) return
                val entry = param.thisObject
                val sbn = XposedHelpers.callMethod(entry, "getSbn")
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                if (pkg in targetApps) param.result = true
            }
        })
    }
}

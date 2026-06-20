package com.karen.flymetool.hook.feature.systemui

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import androidx.palette.graphics.Palette

object LiveNotificationForceHook : FeatureHook {

    private const val TAG = "LiveNotificationForce"
    private const val SBN_CLASS = "android.service.notification.StatusBarNotification"
    private const val CONTROLLER_CLASS = "com.flyme.statusbar.livenotification.LiveNotificationController"
    private const val VIEW_CLASS = "com.flyme.statusbar.livenotification.LiveNotificationView"
    private const val TICK_CONTROLLER_CLASS = "com.flyme.systemui.statusbar.ticker.NotificationTickController"
    private const val WRAPPER_CLASS = "com.android.systemui.statusbar.notification.row.wrapper.NotificationCustomViewWrapper"

    private var enabledPackages = emptySet<String>()
    private val iconColorCache = mutableMapOf<String, Int>()
    private val appIconCache = mutableMapOf<String, Icon>()
    private var systemColor = Color.WHITE
    private var systemContext: Context? = null

    // 线程局部变量：控制 isLive() 在特定上下文中返回 false
    // 用于让 NotificationCustomViewWrapper.onContentUpdated 走普通通知分支
    private val suppressIsLive = ThreadLocal<Boolean>()

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "live_notification_force")) return
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return

        enabledPackages = XposedPrefs.getFeatureStringSet(
            lpparam, packageName, "live_notification_force", emptySet()
        )

        hookStatusBarNotification(lpparam)
        hookNotificationEntry(lpparam)
        hookNotificationCustomViewWrapper(lpparam)
        hookLiveNotificationInfoExt(lpparam)
        hookLiveNotificationView(lpparam)
        hookLiveNotificationController(lpparam)
        hookTickerController(lpparam)

        Logger.i(TAG, "All hooks applied, enabled packages: $enabledPackages")
    }

    private fun getContext(): Context? {
        if (systemContext != null) return systemContext
        try {
            val at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            val app = XposedHelpers.callMethod(at, "getApplication") ?: return null
            systemContext = XposedHelpers.callMethod(app, "getApplicationContext") as? Context
        } catch (e: Throwable) {
            Logger.e(TAG, "Get context failed", e)
        }
        return systemContext
    }

    private fun hookStatusBarNotification(lpparam: XC_LoadPackage.LoadPackageParam) {
        val sbnClass = XposedHelpers.findClass(SBN_CLASS, lpparam.classLoader)

        // Hook isLive()：对目标包名返回 true，但 suppressIsLive 为 true 时返回 false
        XposedBridge.hookAllMethods(sbnClass, "isLive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isTargetPackage(param.thisObject)) return
                if (suppressIsLive.get() == true) {
                    param.result = false
                } else {
                    param.result = true
                }
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleStatus", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) param.result = 1
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleType", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) param.result = 1
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isTargetPackage(param.thisObject)) return
                val pkg = XposedHelpers.callMethod(param.thisObject, "getPackageName") as? String ?: return
                val appIcon = getAppIcon(pkg)
                if (appIcon != null) {
                    param.result = appIcon
                } else {
                    val n = XposedHelpers.callMethod(param.thisObject, "getNotification") as? Notification
                    val smallIcon = n?.smallIcon
                    if (smallIcon != null && param.result == null) {
                        param.result = smallIcon
                    }
                }
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleContent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) {
                    val r = param.result as? String
                    if (r.isNullOrEmpty()) param.result = extractNotificationText(param.thisObject)
                }
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleBgColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isTargetPackage(param.thisObject)) return
                val pkg = XposedHelpers.callMethod(param.thisObject, "getPackageName") as? String ?: return
                param.result = getIconColor(pkg)
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleContentColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) {
                    param.result = systemColor
                }
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getLiveNotificationBgColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) {
                    param.result = -1
                }
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleContentRemoteView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) param.result = null
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getCapsuleIconLottie", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) param.result = null
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getProgress", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isTargetPackage(param.thisObject)) param.result = -1
            }
        })

        Logger.i(TAG, "Hooked StatusBarNotification")
    }

    private fun hookNotificationEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val entryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry", lpparam.classLoader)
            XposedBridge.hookAllMethods(entryClass, "allowLive", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sbn = XposedHelpers.callMethod(param.thisObject, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in enabledPackages) param.result = true
                }
            })
            Logger.i(TAG, "Hooked NotificationEntry.allowLive")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook NotificationEntry failed", e)
        }
    }

    // 关键 Hook：让 NotificationCustomViewWrapper.onContentUpdated 走普通通知分支
    // 在这个方法执行时，临时让 isLive() 返回 false，避免实况通知的特殊布局
    private fun hookNotificationCustomViewWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wrapperClass = XposedHelpers.findClass(WRAPPER_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(wrapperClass, "onContentUpdated", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val row = param.args[0] ?: return
                    val entry = XposedHelpers.callMethod(row, "getEntry") ?: return
                    val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in enabledPackages) {
                        suppressIsLive.set(true)
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    suppressIsLive.remove()
                }
            })
            Logger.i(TAG, "Hooked NotificationCustomViewWrapper.onContentUpdated")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook NotificationCustomViewWrapper failed", e)
        }
    }

    private fun hookLiveNotificationView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewClass = XposedHelpers.findClass(VIEW_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(viewClass, "setLiveNotificationInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.args[0] ?: return
                    val pkg = XposedHelpers.callMethod(info, "getPackageName") as? String ?: return
                    if (pkg !in enabledPackages) return

                    param.thisObject.javaClass.declaredFields.forEach { field ->
                        try {
                            field.isAccessible = true
                            val value = field.get(param.thisObject)
                            if (value is TextView) {
                                value.setTextColor(systemColor)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })
            Logger.i(TAG, "Hooked LiveNotificationView.setLiveNotificationInfo")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook LiveNotificationView failed", e)
        }
    }

    private fun hookLiveNotificationController(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val controllerClass = XposedHelpers.findClass(CONTROLLER_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(controllerClass, "onDarkChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val color = XposedHelpers.getObjectField(param.thisObject, "mSystemColor") as? Int
                    if (color != null && color != 0) {
                        systemColor = color
                    }
                }
            })
            Logger.i(TAG, "Hooked LiveNotificationController.onDarkChanged")
        } catch (e: Throwable) {
            Logger.w(TAG, "LiveNotificationController hook failed: ${e.message}")
        }
    }

    private fun hookTickerController(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tickClass = XposedHelpers.findClass(TICK_CONTROLLER_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(tickClass, "tick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entry = param.args[0] ?: return
                    val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in enabledPackages) {
                        param.result = null
                    }
                }
            })
            XposedBridge.hookAllMethods(tickClass, "updateNotificationTicker", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entry = param.args[0] ?: return
                    val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
                    val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                    if (pkg in enabledPackages) {
                        param.result = false
                    }
                }
            })
            Logger.i(TAG, "Hooked NotificationTickController")
        } catch (e: Throwable) {
            Logger.w(TAG, "TickerController hook failed: ${e.message}")
        }
    }

    private fun hookLiveNotificationInfoExt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val extClass = XposedHelpers.findClass(
                "com.flyme.statusbar.livenotification.LiveNotificationInfoExtKt",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(extClass, "getCapsuleContentExt", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sbn = param.args[1] as? StatusBarNotification ?: return
                    if (sbn.packageName !in enabledPackages) return
                    val r = param.result as? String
                    if (!r.isNullOrEmpty()) return
                    val text = extractNotificationTextFromSbn(sbn)
                    if (text.isNotEmpty()) param.result = text
                }
            })
            Logger.i(TAG, "Hooked LiveNotificationInfoExtKt.getCapsuleContentExt")
        } catch (e: Throwable) {
            Logger.w(TAG, "LiveNotificationInfoExtKt hook failed: ${e.message}")
        }
    }

    private fun getAppIcon(pkg: String): Icon? {
        appIconCache[pkg]?.let { return it }
        val ctx = getContext() ?: return null
        try {
            val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
            val drawable = appInfo.loadIcon(ctx.packageManager) ?: return null
            val bitmap = drawableToBitmap(drawable)
            val icon = Icon.createWithAdaptiveBitmap(bitmap)
            appIconCache[pkg] = icon
            return icon
        } catch (e: Throwable) {
            Logger.w(TAG, "Get app icon failed for $pkg: ${e.message}")
            return null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getIconColor(pkg: String): Int {
        iconColorCache[pkg]?.let { return it }
        val ctx = getContext() ?: return defaultColor()
        try {
            val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
            val drawable = appInfo.loadIcon(ctx.packageManager) ?: return defaultColor()
            val color = extractDominantColor(drawable)
            iconColorCache[pkg] = color
            return color
        } catch (e: Throwable) {
            Logger.e(TAG, "Extract icon color failed for $pkg", e)
            return defaultColor()
        }
    }

    private fun extractDominantColor(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(canvas)
        val palette = Palette.from(bitmap).generate()
        bitmap.recycle()
        val vibrant = palette.getVibrantColor(0)
        val dominant = palette.getDominantColor(0)
        val muted = palette.getMutedColor(0)
        val color = when {
            vibrant != 0 -> vibrant
            dominant != 0 -> dominant
            muted != 0 -> muted
            else -> return defaultColor()
        }
        return Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun defaultColor(): Int = Color.argb(255, 100, 100, 112)

    private fun isTargetPackage(sbn: Any): Boolean {
        val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return false
        return pkg in enabledPackages
    }

    private fun extractNotificationText(sbn: Any): String {
        return try {
            val n = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return ""
            extractTextFromNotification(n)
        } catch (_: Throwable) { "" }
    }

    private fun extractNotificationTextFromSbn(sbn: StatusBarNotification): String {
        return try { extractTextFromNotification(sbn.notification) }
        catch (_: Throwable) { "" }
    }

    private fun extractTextFromNotification(n: Notification): String {
        val extras = n.extras ?: return ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        return when {
            text.isNotEmpty() && title.isNotEmpty() -> "$title: $text"
            text.isNotEmpty() -> text
            bigText.isNotEmpty() -> bigText
            title.isNotEmpty() -> title
            subText.isNotEmpty() -> subText
            else -> ""
        }
    }
}

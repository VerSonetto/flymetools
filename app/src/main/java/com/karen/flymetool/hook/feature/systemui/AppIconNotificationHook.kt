package com.karen.flymetool.hook.feature.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object AppIconNotificationHook : FeatureHook {

    private const val TAG = "AppIconNotification"
    private const val ICON_DRAWING_SIZE_DP = 15f

    private val appIconPackages = mutableSetOf<String>()
    private var tickerSwitcher: android.widget.ImageSwitcher? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "app_icon_notification")) return
        if (lpparam.packageName != "com.android.systemui") return

        Logger.i(TAG, "Hooking StatusBarIconView and Ticker")

        try {
            hookFlymeNotificationIconUtils(lpparam)
            hookStatusBarIconView(lpparam)
            hookMarqueeTicker(lpparam)

            Logger.i(TAG, "All hooks applied successfully")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook failed", e)
        }
    }

    private fun hookStatusBarIconView(lpparam: XC_LoadPackage.LoadPackageParam) {
        val iconViewClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.StatusBarIconView",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            iconViewClass,
            "getIcon",
            "com.android.internal.statusbar.StatusBarIcon",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val thisObject = param.thisObject
                    val sbn = XposedHelpers.getObjectField(thisObject, "mNotification") ?: return

                    val context = XposedHelpers.callMethod(thisObject, "getContext") as android.content.Context
                    val pkgName = resolveAppPackage(sbn) ?: return

                    if (pkgName == "com.android.systemui" || pkgName == "android") {
                        return
                    }

                    try {
                        val appIcon = getAppIcon(context, pkgName)
                        if (appIcon != null) {
                            val density = context.resources.displayMetrics.density
                            val iconSizePx = (ICON_DRAWING_SIZE_DP * density).toInt()
                            val icon = iconFromDrawable(appIcon, iconSizePx)
                            val statusBarIcon = param.args[0]
                            XposedHelpers.setObjectField(statusBarIcon, "icon", icon)
                            param.result = icon.loadDrawable(context)

                            appIconPackages.add(pkgName)
                            XposedHelpers.setBooleanField(thisObject, "mShowsConversation", true)
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to get app icon for $pkgName", e)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            iconViewClass,
            "updateIconColor",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val thisObject = param.thisObject
                    val sbn = XposedHelpers.getObjectField(thisObject, "mNotification") ?: return

                    val pkgName = resolveAppPackage(sbn)
                    if (pkgName != null && pkgName in appIconPackages) {
                        XposedHelpers.callMethod(thisObject, "setColorFilter", null as Any?)
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookMarqueeTicker(lpparam: XC_LoadPackage.LoadPackageParam) {
        val marqueeTickerClass = findMarqueeTickerClass(lpparam) ?: return

        try {

            XposedHelpers.findAndHookMethod(
                marqueeTickerClass,
                "addEntry",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sbn = param.args[0]
                        val pkgName = resolveAppPackage(sbn) ?: return

                        if (pkgName == "com.android.systemui" || pkgName == "android") return

                        val notification = XposedHelpers.callMethod(sbn, "getNotification")
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                        val appIcon = getAppIcon(context, pkgName) ?: return

                        val density = context.resources.displayMetrics.density
                        val iconSizePx = (ICON_DRAWING_SIZE_DP * density).toInt()
                        val newIcon = iconFromDrawable(appIcon, iconSizePx)

                        XposedHelpers.callMethod(notification, "setSmallIcon", newIcon)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sw = XposedHelpers.getObjectField(param.thisObject, "mIconSwitcher") as? android.widget.ImageSwitcher
                        clearIconView(sw)
                        sw?.post { clearIconView(sw) }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                marqueeTickerClass,
                "onDarkChanged",
                ArrayList::class.java, Float::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sw = XposedHelpers.getObjectField(param.thisObject, "mIconSwitcher") as? android.widget.ImageSwitcher
                        clearIconView(sw)
                    }
                }
            )

            if (FlymeVersionUtils.isFlyme12()) {
                hookTickerIconColorFilter(lpparam)
            }

            Logger.i(TAG, "Hooked MarqueeTicker.addEntry and onDarkChanged")
        } catch (e: Throwable) {
            Logger.w(TAG, "MarqueeTicker hook failed: ${e.message}")
        }
    }

    private fun findMarqueeTickerClass(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
        try {
            return XposedHelpers.findClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker", lpparam.classLoader)
        } catch (_: Throwable) {}
        try {
            return XposedHelpers.findClass("com.flyme.statusbar.ticker.MarqueeTicker", lpparam.classLoader)
        } catch (_: Throwable) {}
        Logger.w(TAG, "MarqueeTicker class not found")
        return null
    }

    private fun clearIconView(switcher: android.widget.ImageSwitcher?) {
        val sw = switcher ?: return
        tickerSwitcher = sw
        val view = sw.getCurrentView() as? ImageView ?: return
        view.setColorFilter(null)
        view.drawable?.mutate()?.clearColorFilter()
    }

    private fun hookTickerIconColorFilter(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(
            ImageView::class.java, "setColorFilter",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val iv = param.thisObject as? ImageView ?: return
                    if (iv.parent !is android.widget.ImageSwitcher) return
                    if (iv.parent == tickerSwitcher) {
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookFlymeNotificationIconUtils(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val flymeIconUtilsClass = XposedHelpers.findClass(
                "com.flyme.notification.utils.FlymeNotificationIconUtils",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                flymeIconUtilsClass,
                "resetNotificationSmallIconIfNeed",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )

            Logger.i(TAG, "Hooked FlymeNotificationIconUtils.resetNotificationSmallIconIfNeed")
        } catch (e: Throwable) {
            Logger.w(TAG, "FlymeNotificationIconUtils not found or hook failed: ${e.message}")
        }
    }

    /** MEIZU Push 等代发通知：getPackageName=推送服务，getOrigPackageName=真应用 */
    private fun resolveAppPackage(sbn: Any): String? {
        return try {
            val orig = XposedHelpers.callMethod(sbn, "getOrigPackageName") as? String
            if (!orig.isNullOrEmpty()) orig
            else XposedHelpers.callMethod(sbn, "getPackageName") as? String
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(sbn, "getPackageName") as? String
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun getAppIcon(context: android.content.Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to get app icon for $packageName: ${e.message}")
            null
        }
    }

    private fun iconFromDrawable(drawable: Drawable, targetSizePx: Int): Icon {
        val sourceBitmap: Bitmap? = if (drawable is BitmapDrawable) drawable.bitmap else null

        val bitmap = if (sourceBitmap != null) {
            Bitmap.createScaledBitmap(sourceBitmap, targetSizePx, targetSizePx, true)
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else targetSizePx
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else targetSizePx

            val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val tempCanvas = Canvas(tempBitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(tempCanvas)

            val scaledBitmap = Bitmap.createScaledBitmap(tempBitmap, targetSizePx, targetSizePx, true)
            tempBitmap.recycle()
            scaledBitmap
        }

        return Icon.createWithBitmap(bitmap)
    }
}

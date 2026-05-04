package com.karen.flymetool.hook.feature.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object AppIconNotificationHook {

    private const val TAG = "AppIconNotification"
    private const val ICON_DRAWING_SIZE_DP = 15f

    private val appIconPackages = mutableSetOf<String>()

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        if (!XposedPrefs.isFeatureEnabled(lpparam, "com.android.systemui", "app_icon_notification")) {
            return
        }

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
                    val notification = XposedHelpers.getObjectField(thisObject, "mNotification")
                    if (notification == null) {
                        return
                    }

                    val context = XposedHelpers.callMethod(thisObject, "getContext") as android.content.Context
                    val pkgName = XposedHelpers.callMethod(notification, "getPackageName") as? String ?: return

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
                    val notification = XposedHelpers.getObjectField(thisObject, "mNotification")
                    if (notification == null) {
                        return
                    }

                    val pkgName = XposedHelpers.callMethod(notification, "getPackageName") as? String
                    if (pkgName != null && pkgName in appIconPackages) {
                        XposedHelpers.callMethod(thisObject, "setColorFilter", null as Any?)
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookMarqueeTicker(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val marqueeTickerClass = XposedHelpers.findClass(
                "com.flyme.statusbar.ticker.MarqueeTicker",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                marqueeTickerClass,
                "addEntry",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sbn = param.args[0]
                        val pkgName = XposedHelpers.callMethod(sbn, "getPackageName") as String

                        if (pkgName == "com.android.systemui" || pkgName == "android") {
                            return
                        }

                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                        val appIcon = getAppIcon(context, pkgName) ?: return

                        val density = context.resources.displayMetrics.density
                        val iconSizePx = (ICON_DRAWING_SIZE_DP * density).toInt()
                        val newIcon = iconFromDrawable(appIcon, iconSizePx)

                        val notification = XposedHelpers.callMethod(sbn, "getNotification")
                        XposedHelpers.callMethod(notification, "setSmallIcon", newIcon)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val iconSwitcher = XposedHelpers.getObjectField(param.thisObject, "mIconSwitcher") as? android.widget.ImageSwitcher
                        iconSwitcher?.getCurrentView()?.let { view ->
                            (view as? android.widget.ImageView)?.setColorFilter(null)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                marqueeTickerClass,
                "onDarkChanged",
                ArrayList::class.java, Float::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val iconSwitcher = XposedHelpers.getObjectField(param.thisObject, "mIconSwitcher") as? android.widget.ImageSwitcher
                        iconSwitcher?.getCurrentView()?.let { view ->
                            (view as? android.widget.ImageView)?.setColorFilter(null)
                        }
                    }
                }
            )

            Logger.i(TAG, "Hooked MarqueeTicker.addEntry and onDarkChanged")
        } catch (e: Throwable) {
            Logger.w(TAG, "MarqueeTicker hook failed: ${e.message}")
        }
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

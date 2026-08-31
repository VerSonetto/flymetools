package com.karen.flymetool.hook.feature.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.LruCache
import android.widget.ImageView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils
import io.github.libxposed.api.XposedInterface

object AppIconNotificationHook : FeatureHook {

    private const val TAG = "AppIconNotification"
    private const val ICON_DRAWING_SIZE_DP = 15f

    private val appIconPackages = mutableSetOf<String>()
    private var tickerSwitcher: android.widget.ImageSwitcher? = null

    /** 应用图标缓存：getApplicationIcon 是 binder + 位图解码，按包缓存避免每次图标更新重取 */
    private val appIconCache = LruCache<String, Drawable>(64)

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("app_icon_notification")) return
        if (ctx.packageName != "com.android.systemui") return

        Logger.i(TAG, "开始挂载 StatusBarIconView 与 Ticker")

        try {
            hookFlymeNotificationIconUtils(ctx)
            hookStatusBarIconView(ctx)
            hookMarqueeTicker(ctx)

            Logger.i(TAG, "全部 Hook 挂载完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook 挂载失败", e)
        }
    }

    private fun hookStatusBarIconView(ctx: HookContext) {
        val iconViewClass = Reflect.findClass(
            "com.android.systemui.statusbar.StatusBarIconView",
            ctx.classLoader
        )

        Reflect.hookMethodOn(
            ctx.api,
            iconViewClass,
            "getIcon",
            Reflect.findClass("com.android.internal.statusbar.StatusBarIcon", ctx.classLoader),
        ) { chain ->
            val result = chain.proceed()
            val thisObject = chain.getThisObject()
            val sbn = Reflect.getObjectField(thisObject, "mNotification") ?: return@hookMethodOn result

            val context = Reflect.callMethod(ctx.api, thisObject, "getContext") as android.content.Context
            val pkgName = resolveAppPackage(ctx.api, sbn) ?: return@hookMethodOn result

            if (pkgName == "com.android.systemui" || pkgName == "android") {
                return@hookMethodOn result
            }

            try {
                val appIcon = getAppIcon(context, pkgName)
                if (appIcon != null) {
                    val density = context.resources.displayMetrics.density
                    val iconSizePx = (ICON_DRAWING_SIZE_DP * density).toInt()
                    val icon = iconFromDrawable(appIcon, iconSizePx)
                    val statusBarIcon = chain.getArg(0)
                    Reflect.setObjectField(statusBarIcon, "icon", icon)
                    val newResult = icon.loadDrawable(context)

                    appIconPackages.add(pkgName)
                    Reflect.setBooleanField(thisObject, "mShowsConversation", true)
                    newResult
                } else {
                    result
                }
            } catch (e: Exception) {
                Logger.once(TAG, "app_icon_$pkgName", "获取应用图标失败 pkg=$pkgName")
                result
            }
        }

        Reflect.hookMethodOn(ctx.api, iconViewClass, "updateIconColor") { chain ->
            val thisObject = chain.getThisObject()
            val sbn = Reflect.getObjectField(thisObject, "mNotification") ?: return@hookMethodOn chain.proceed()

            val pkgName = resolveAppPackage(ctx.api, sbn)
            if (pkgName != null && pkgName in appIconPackages) {
                Reflect.callMethod(ctx.api, thisObject, "setColorFilter", null as Any?)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookMarqueeTicker(ctx: HookContext) {
        val marqueeTickerClass = findMarqueeTickerClass(ctx) ?: return

        try {

            Reflect.hookMethodOn(
                ctx.api,
                marqueeTickerClass,
                "addEntry",
                Reflect.findClass("android.service.notification.StatusBarNotification", ctx.classLoader),
            ) { chain ->
                val sbn = chain.getArg(0)
                val pkgName = resolveAppPackage(ctx.api, sbn)
                if (pkgName != null && pkgName != "com.android.systemui" && pkgName != "android") {
                    try {
                        val notification = Reflect.callMethod(ctx.api, sbn, "getNotification")
                        val context = Reflect.getObjectField(chain.getThisObject(), "mContext") as android.content.Context
                        val appIcon = getAppIcon(context, pkgName)
                        if (appIcon != null) {
                            val density = context.resources.displayMetrics.density
                            val iconSizePx = (ICON_DRAWING_SIZE_DP * density).toInt()
                            val newIcon = iconFromDrawable(appIcon, iconSizePx)

                            Reflect.callMethod(ctx.api, notification, "setSmallIcon", newIcon)
                        }
                    } catch (_: Throwable) {
                    }
                }

                val result = chain.proceed()

                try {
                    val sw = Reflect.getObjectField(chain.getThisObject(), "mIconSwitcher") as? android.widget.ImageSwitcher
                    clearIconView(sw)
                    sw?.post { clearIconView(sw) }
                } catch (_: Throwable) {
                }

                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                marqueeTickerClass,
                "onDarkChanged",
                ArrayList::class.java, Float::class.java, Int::class.java,
            ) { chain ->
                val result = chain.proceed()
                val sw = Reflect.getObjectField(chain.getThisObject(), "mIconSwitcher") as? android.widget.ImageSwitcher
                clearIconView(sw)
                result
            }

            if (FlymeVersionUtils.isFlyme12()) {
                hookTickerIconColorFilter(ctx)
            }

            Logger.i(TAG, "已挂载 MarqueeTicker.addEntry 和 onDarkChanged")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MarqueeTicker 失败", e)
        }
    }

    private fun findMarqueeTickerClass(ctx: HookContext): Class<*>? {
        try {
            return Reflect.findClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker", ctx.classLoader)
        } catch (_: Throwable) {}
        try {
            return Reflect.findClass("com.flyme.statusbar.ticker.MarqueeTicker", ctx.classLoader)
        } catch (_: Throwable) {}
        Logger.w(TAG, "未找到 MarqueeTicker 类")
        return null
    }

    private fun clearIconView(switcher: android.widget.ImageSwitcher?) {
        val sw = switcher ?: return
        tickerSwitcher = sw
        val view = sw.getCurrentView() as? ImageView ?: return
        view.setColorFilter(null)
        view.drawable?.mutate()?.clearColorFilter()
    }

    private fun hookTickerIconColorFilter(ctx: HookContext) {
        Reflect.hookAllMethods(ctx.api, ImageView::class.java, "setColorFilter", excluded = { false }) { chain ->
            val iv = chain.getThisObject() as? ImageView ?: return@hookAllMethods chain.proceed()
            if (iv.parent !is android.widget.ImageSwitcher) return@hookAllMethods chain.proceed()
            if (iv.parent == tickerSwitcher) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookFlymeNotificationIconUtils(ctx: HookContext) {
        try {
            val flymeIconUtilsClass = Reflect.findClass(
                "com.flyme.notification.utils.FlymeNotificationIconUtils",
                ctx.classLoader
            )

            Reflect.hookMethodOn(
                ctx.api,
                flymeIconUtilsClass,
                "resetNotificationSmallIconIfNeed",
                Reflect.findClass("android.service.notification.StatusBarNotification", ctx.classLoader),
            ) { chain ->
                null
            }

            Logger.i(TAG, "已挂载 FlymeNotificationIconUtils.resetNotificationSmallIconIfNeed")
        } catch (e: Throwable) {
            if (e is ClassNotFoundException || e is NoClassDefFoundError) {
                // 该版本无此类：预期降级
                Logger.w(TAG, "FlymeNotificationIconUtils 不存在（该版本无此类）")
            } else {
                Logger.e(TAG, "挂载 FlymeNotificationIconUtils 失败", e)
            }
        }
    }

    /** MEIZU Push 等代发通知：getPackageName=推送服务，getOrigPackageName=真应用 */
    private fun resolveAppPackage(api: XposedInterface, sbn: Any): String? {
        return try {
            val orig = Reflect.callMethod(api, sbn, "getOrigPackageName") as? String
            if (!orig.isNullOrEmpty()) orig
            else Reflect.callMethod(api, sbn, "getPackageName") as? String
        } catch (_: Throwable) {
            try {
                Reflect.callMethod(api, sbn, "getPackageName") as? String
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun getAppIcon(context: android.content.Context, packageName: String): Drawable? {
        appIconCache.get(packageName)?.let { return it }
        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            appIconCache.put(packageName, icon)
            icon
        } catch (e: Exception) {
            Logger.once(TAG, "no_icon_$packageName", "获取应用图标失败 pkg=$packageName")
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
package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils
import io.github.libxposed.api.XposedInterface

object ForceLiveNotificationHook : FeatureHook {

    private const val TAG = "ForceLiveNotification"
    private val iconColorCache = mutableMapOf<String, Int>()
    private var systemColor = -1

    override fun handle(ctx: HookContext) {
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!ctx.featureEnabled("force_live_notification")) return

        val targetApps = ctx.featureStringSet(
            "force_live_notification", emptySet()
        )
        if (targetApps.isEmpty()) return

        try {
            hookStatusBarNotification(ctx, targetApps)
            hookNotificationEntry(ctx, targetApps)
            hookNotificationRowDismiss(ctx, targetApps)
            hookLiveNotificationController(ctx)
            hookTickerController(ctx, targetApps)
            revertLiveCardStyling(ctx, targetApps)
            Logger.i(TAG, "已强制灵动通知: $targetApps")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
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
        ctx: HookContext,
        targetApps: Set<String>
    ) {
        val ncvClass = Reflect.findClass(
            "com.android.systemui.statusbar.notification.row.NotificationContentView",
            ctx.classLoader
        )
        val sbnClass = Reflect.findClass(
            "android.service.notification.StatusBarNotification", ctx.classLoader
        )

        Reflect.hookMethodOn(
            ctx.api, ncvClass, "buildContentContainer", View::class.java, sbnClass,
        ) { chain ->
            val result = chain.proceed()
            val sbn = chain.getArg(1)
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg !in targetApps) return@hookMethodOn result

            val view = result as? View ?: return@hookMethodOn result
            view.setClipToOutline(false)
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@hookMethodOn result
            lp.setMargins(0, 0, 0, 0)
            view.layoutParams = lp
            result
        }
    }

    private fun hookLiveNotificationController(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(
                "com.flyme.statusbar.livenotification.LiveNotificationController",
                ctx.classLoader
            )
            Reflect.hookMethodOn(ctx.api, clazz, "onDarkChanged",
                java.util.ArrayList::class.java, Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            ) { chain ->
                val result = chain.proceed()
                val ctrl = chain.getThisObject()
                val c = Reflect.getIntField(ctrl, "mSystemColor")
                if (c == 0) return@hookMethodOn result
                systemColor = c or -0x1000000
                // onDarkChanged 只在 mLyricsInofo != null 时才调 updateStatusBarIcons，
                // 对于普通通知胶囊，颜色不会刷新，需要手动触发
                Reflect.callMethod(ctx.api, ctrl, "updateStatusBarIcons", false)
                result
            }
        } catch (_: Throwable) { }
    }

    private fun hookTickerController(
        ctx: HookContext,
        targetApps: Set<String>
    ) {
        try {
            val clazz = Reflect.findClass(
                "com.flyme.systemui.statusbar.ticker.NotificationTickController",
                ctx.classLoader
            )
            val entryClass = Reflect.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                ctx.classLoader
            )
            Reflect.hookMethodOn(ctx.api, clazz, "tick", entryClass) { chain ->
                val entry = chain.getArg(0) ?: return@hookMethodOn chain.proceed()
                val sbn = Reflect.callMethod(ctx.api, entry, "getSbn") ?: return@hookMethodOn chain.proceed()
                val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn chain.proceed()
                if (pkg in targetApps) null else chain.proceed()
            }
            Reflect.hookMethodOn(ctx.api, clazz, "updateNotificationTicker", entryClass) { chain ->
                val entry = chain.getArg(0) ?: return@hookMethodOn chain.proceed()
                val sbn = Reflect.callMethod(ctx.api, entry, "getSbn") ?: return@hookMethodOn chain.proceed()
                val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn chain.proceed()
                if (pkg in targetApps) false else chain.proceed()
            }
            Logger.i(TAG, "已挂载 NotificationTickController 的 Ticker Hook")
        } catch (e: Throwable) {
            Logger.e(TAG, "Ticker Hook 挂载失败", e)
        }
    }

    private fun loadAppIconBitmap(api: XposedInterface, pkg: String): android.graphics.Bitmap? {
        return try {
            val app = Reflect.callStaticMethod(
                api,
                Reflect.findClass("android.app.ActivityThread", null),
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
        ctx: HookContext,
        targetApps: Set<String>
    ) {
        val sbnClass = Reflect.findClass(
            "android.service.notification.StatusBarNotification", ctx.classLoader
        )

        Reflect.hookMethodOn(ctx.api, sbnClass, "isLive") { chain ->
            val result = chain.proceed()
            if (result == true) return@hookMethodOn result
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg in targetApps) true else result
        }

        Reflect.hookMethodOn(ctx.api, sbnClass, "getCapsuleStatus") { chain ->
            val result = chain.proceed()
            if ((result as? Int) == 1) return@hookMethodOn result
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg in targetApps) 1 else result
        }

        Reflect.hookMethodOn(ctx.api, sbnClass, "getCapsuleContent") { chain ->
            val result = chain.proceed()
            val text = result as? CharSequence
            if (!text.isNullOrEmpty()) return@hookMethodOn result
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg !in targetApps) return@hookMethodOn result

            val notification = Reflect.callMethod(ctx.api, sbn, "getNotification") ?: return@hookMethodOn result
            val extras = Reflect.getObjectField(notification, "extras") as? Bundle ?: return@hookMethodOn result
            val title = extras.getString("android.title")
            val content = extras.getString("android.text")
            when {
                !title.isNullOrEmpty() && !content.isNullOrEmpty() -> "$title: $content"
                !title.isNullOrEmpty() -> title
                !content.isNullOrEmpty() -> content
                else -> ""
            }
        }

        Reflect.hookMethodOn(ctx.api, sbnClass, "getCapsuleIcon") { chain ->
            val result = chain.proceed()
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg !in targetApps) return@hookMethodOn result
            val bmp = loadAppIconBitmap(ctx.api, pkg) ?: return@hookMethodOn result
            Icon.createWithBitmap(bmp)
        }

        Reflect.hookMethodOn(ctx.api, sbnClass, "getCapsuleBgColor") { chain ->
            val result = chain.proceed()
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg !in targetApps) return@hookMethodOn result

            val cached = iconColorCache[pkg]
            if (cached != null) { return@hookMethodOn cached }

            val bmp = loadAppIconBitmap(ctx.api, pkg) ?: return@hookMethodOn result
            iconColorCache[pkg] = dominantColor(bmp)
            return@hookMethodOn iconColorCache[pkg]!!
        }

        Reflect.hookMethodOn(ctx.api, sbnClass, "getCapsuleContentColor") { chain ->
            val result = chain.proceed()
            val sbn = chain.getThisObject()
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg !in targetApps) return@hookMethodOn result
            systemColor
        }
    }

    private fun hookNotificationEntry(
        ctx: HookContext,
        targetApps: Set<String>
    ) {
        val entryClass = Reflect.findClass(
            "com.android.systemui.statusbar.notification.collection.NotificationEntry",
            ctx.classLoader
        )

        Reflect.hookMethodOn(ctx.api, entryClass, "allowLive") { chain ->
            val result = chain.proceed()
            if (result == true) return@hookMethodOn result
            val entry = chain.getThisObject()
            val sbn = Reflect.callMethod(ctx.api, entry, "getSbn")
            val pkg = Reflect.callMethod(ctx.api, sbn, "getPackageName") as? String ?: return@hookMethodOn result
            if (pkg in targetApps) true else result
        }

        Reflect.hookMethodOn(
            ctx.api,
            entryClass,
            "isDismissableForState",
            Boolean::class.javaPrimitiveType!!,
        ) { chain ->
            val result = chain.proceed()
            if (result == true) return@hookMethodOn result
            val entry = chain.getThisObject()
            if (!shouldForceDirectDismiss(ctx.api, entry, targetApps)) return@hookMethodOn result
            true
        }
    }

    private fun hookNotificationRowDismiss(
        ctx: HookContext,
        targetApps: Set<String>
    ) {
        val rowClass = Reflect.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            ctx.classLoader
        )

        Reflect.hookMethodOn(ctx.api, rowClass, "canViewBeDismissed") { chain ->
            val result = chain.proceed()
            if (result == true) return@hookMethodOn result
            val row = chain.getThisObject()
            val entry = Reflect.getObjectField(row, "mEntry")
            if (!shouldForceDirectDismiss(ctx.api, entry, targetApps)) return@hookMethodOn result
            true
        }
    }

    private fun shouldForceDirectDismiss(api: XposedInterface, entry: Any?, targetApps: Set<String>): Boolean {
        val sbn = runCatching { Reflect.callMethod(api, entry, "getSbn") }.getOrNull() ?: return false
        val pkg = runCatching { Reflect.callMethod(api, sbn, "getPackageName") as? String }.getOrNull()
            ?: return false
        if (pkg !in targetApps) return false
        return runCatching { Reflect.callMethod(api, sbn, "canDelete") as? Boolean }.getOrNull() == true
    }
}
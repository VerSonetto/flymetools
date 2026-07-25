package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object AODLyricHook : FeatureHook {

    private const val AOD_BASIC_VIEW = "com.flyme.aod.view.AODBasicView"
    private const val CLOCK_VIEW = "com.flyme.aod.view.ClockView"
    /** 跟随锁屏 AOD 的日期时钟容器（id=time） */
    private const val KEYGUARD_DATE_CLOCK = "com.flyme.keyguard.clock.KeyguardDateClockView"
    private const val ADVERT_TICKER_VIEW_OLD = "com.flyme.statusbar.ticker.AdvertTickerView"
    private const val ADVERT_TICKER_VIEW_NEW = "com.flyme.systemui.statusbar.ticker.AdvertTickerView"
    private const val LIVE_NOTI_CONTROLLER = "com.flyme.statusbar.livenotification.LiveNotificationController"
    private const val HOOK_NAME = "AODLyric"
    private const val VIEW_TAG = "flymetool_aod_lyric"
    /** 与 AdvertTickerView 歌词判定一致：notification.flags & 0x1000000 */
    private const val FLYME_LYRIC_FLAG = 0x1000000

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    /** 可能同时存在经典 AOD / 跟随锁屏多棵树，统一刷新 */
    private val lyricViews = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<TextView>>()
    private var currentLyric: CharSequence = ""
    /** 跟随锁屏时钟与锁屏共用：仅 doze/AOD 时显示歌词 */
    @Volatile
    private var isDozing: Boolean = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "aod_lyric")) return
        if (lpparam.packageName != "com.android.systemui") return

        // 经典 AOD：时钟在 date_time_layout（含 aod_time），插到其下方（仅 AOD 树，无锁屏问题）
        hookInsertBelowNamedChild(lpparam, AOD_BASIC_VIEW, "date_time_layout", aodOnly = false)
        if (FlymeVersionUtils.isFlyme12()) {
            hookInsertBelowSelf(lpparam, CLOCK_VIEW, aodOnly = false)
            // 跟随锁屏：挂在 KeyguardDateClockView，需配合 setDozing 只在 AOD 显示
            hookInsertBelowNamedChild(lpparam, KEYGUARD_DATE_CLOCK, "normal_clock_view", aodOnly = true)
            hookKeyguardDozing(lpparam)
        }

        when {
            FlymeVersionUtils.isFlyme12() -> {
                hookAdvertTickerView(lpparam, ADVERT_TICKER_VIEW_NEW, "Flyme 12")
                hookLyricsNotifications(lpparam)
            }
            else -> hookAdvertTickerView(lpparam, ADVERT_TICKER_VIEW_OLD, "Flyme 10")
        }
    }

    /** host 内按资源名找时钟行，插到其正下方；aodOnly=true 表示跟随锁屏共用树，仅 AOD 可见 */
    private fun hookInsertBelowNamedChild(
        lpparam: XC_LoadPackage.LoadPackageParam,
        hostClass: String,
        childName: String,
        aodOnly: Boolean
    ) {
        try {
            val clazz = XposedHelpers.findClass(hostClass, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject as ViewGroup
                            val clockRow = findViewByName(host, childName) ?: host
                            val parent = (clockRow.parent as? ViewGroup) ?: host
                            val anchor = if (clockRow.parent is ViewGroup) clockRow else host.getChildAt(0) ?: return
                            attachBelow(parent, anchor, aodOnly)
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "insert below $childName failed", t)
                        }
                    }
                }
            )
            Logger.i(HOOK_NAME, "Hooked $hostClass -> below $childName (aodOnly=$aodOnly)")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $hostClass failed", e)
        }
    }

    /** 把歌词插到控件自身在父布局中的正下方 */
    private fun hookInsertBelowSelf(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        aodOnly: Boolean
    ) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val clock = param.thisObject as View
                            val parent = clock.parent as? ViewGroup ?: return
                            attachBelow(parent, clock, aodOnly)
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "insert below $className failed", t)
                        }
                    }
                }
            )
            Logger.i(HOOK_NAME, "Hooked $className -> below self (aodOnly=$aodOnly)")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $className failed", e)
        }
    }

    /** 跟随锁屏时钟 setDozing：亮屏锁屏隐藏歌词，进入 AOD 再显示 */
    private fun hookKeyguardDozing(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(KEYGUARD_DATE_CLOCK, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "setDozing",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            isDozing = param.args[0] as? Boolean ?: false
                            refreshAllLyricViews()
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "setDozing hook error", t)
                        }
                    }
                }
            )
            Logger.i(HOOK_NAME, "Hooked KeyguardDateClockView.setDozing")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook setDozing failed", e)
        }
    }

    private fun findViewByName(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        if (id == 0) return null
        return root.findViewById(id)
    }

    /** 把歌词 TextView 插到 anchor 的正下方（同一 parent 中 index+1） */
    private fun attachBelow(parent: ViewGroup, anchor: View, aodOnly: Boolean) {
        if (parent.findViewWithTag<View>(VIEW_TAG) != null) return

        val index = parent.indexOfChild(anchor)
        if (index < 0) return

        val tv = createLyricTextView(parent.context, aodOnly)
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(parent.context, 8f)
        }
        val finalLp: ViewGroup.LayoutParams = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(lp.width, lp.height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = lp.topMargin
            }
        } else {
            lp
        }
        parent.addView(tv, index + 1, finalLp)
        registerLyricView(tv)
        Logger.i(
            HOOK_NAME,
            "Lyric attached below ${anchor.javaClass.simpleName} in ${parent.javaClass.simpleName} aodOnly=$aodOnly"
        )
    }

    private fun registerLyricView(tv: TextView) {
        lyricViews.removeAll { it.get() == null || it.get() === tv }
        lyricViews.add(java.lang.ref.WeakReference(tv))
        applyLyricToView(tv, currentLyric)
    }

    private fun hookLyricsNotifications(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(LIVE_NOTI_CONTROLLER, lpparam.classLoader)
            val entryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLyricsNotifications",
                CharSequence::class.java,
                Drawable::class.java,
                entryClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val text = param.args[0] as? CharSequence
                            if (!text.isNullOrEmpty() && text != currentLyric) {
                                currentLyric = text
                                updateLyricText(text)
                            }
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "getLyricsNotifications hook error", t)
                        }
                    }
                }
            )
            Logger.i(HOOK_NAME, "Hooked LiveNotificationController.getLyricsNotifications")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook getLyricsNotifications failed", e)
        }
    }

    private fun hookAdvertTickerView(lpparam: XC_LoadPackage.LoadPackageParam, className: String, versionTag: String) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "addNotification",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val sbn = param.args[0] ?: return
                            val notification = XposedHelpers.getObjectField(sbn, "notification") ?: return
                            val tickerText = XposedHelpers.getObjectField(notification, "tickerText") as? CharSequence
                            if (tickerText.isNullOrEmpty()) return

                            if (!isLyricNotification(sbn, notification, param.result)) {
                                Logger.d(HOOK_NAME, "Skip non-lyric notification")
                                return
                            }

                            if (tickerText != currentLyric) {
                                currentLyric = tickerText
                                updateLyricText(tickerText)
                            }
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "addNotification hook error", t)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "removeNotification",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            currentLyric = ""
                            updateLyricText("")
                        } catch (t: Throwable) {
                            Logger.e(HOOK_NAME, "removeNotification hook error", t)
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked $versionTag: $className")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $versionTag failed", e)
        }
    }

    private fun isLyricNotification(sbn: Any, notification: Any, addResult: Any?): Boolean {
        if (addResult as? Boolean == true) return true

        try {
            val flags = XposedHelpers.getIntField(notification, "flags")
            if (flags and FLYME_LYRIC_FLAG != 0) {
                val clearable = XposedHelpers.callMethod(sbn, "isClearable") as? Boolean ?: true
                if (!clearable) return true
            }
        } catch (_: Throwable) {
        }

        return try {
            XposedHelpers.callMethod(notification, "isMediaNotification") as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createLyricTextView(context: Context, aodOnly: Boolean): TextView {
        return TextView(context).apply {
            tag = VIEW_TAG
            // 用 contentDescription 标记是否仅 AOD 显示，避免额外 map
            contentDescription = if (aodOnly) "aod_only" else "always"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            maxLines = 2
            setLineSpacing(0f, 1.2f)
            visibility = View.GONE
        }
    }

    private fun updateLyricText(text: CharSequence) {
        refreshAllLyricViews()
    }

    private fun refreshAllLyricViews() {
        handler.post {
            lyricViews.removeAll { it.get() == null }
            for (ref in lyricViews) {
                val tv = ref.get() ?: continue
                applyLyricToView(tv, currentLyric)
            }
        }
    }

    private fun applyLyricToView(tv: TextView, text: CharSequence) {
        val aodOnly = tv.contentDescription == "aod_only"
        val allowShow = !aodOnly || isDozing
        if (text.isNotEmpty() && allowShow) {
            tv.text = text
            tv.visibility = View.VISIBLE
        } else {
            // 有歌词但锁屏非 AOD：仍缓存文案，只隐藏
            if (text.isNotEmpty()) tv.text = text
            tv.visibility = View.GONE
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * iOS 锁屏通知堆叠 — 对齐 showcase.html layoutFor。
 *
 * 折叠 p≈0：整组沉底，首卡全尺寸顶在 THRESH，下层 L*PEEK 错位 + 缩放下沉。
 * 展开 p→1：与原生列表 Y 插值；仅 overflow 继续堆叠。
 * 折叠态点击卡片：展开列表（不跳转）；有 pinned HUN 时整段不碰。
 *
 * 性能：prefs/dp 缓存、Item 带 viewState、阴影只抑制一次、字段变更才写。
 */
object IosNotificationStackHook : FeatureHook {

    private const val TAG = "IosNotifStack"
    private const val FEATURE_KEY = "ios_notification_stack"
    private const val SINK_ALL_KEY = "sink_all"
    private const val DEFAULT_BOTTOM_PAD = 180

    private const val STEP_DP = 108f
    private const val PEEK_DP = 46f
    private const val EXPAND_E_DP = 200f
    private const val SCALE_PER_L = 0.05f
    private const val MIN_SCALE = 0.84f
    private const val MAX_L_VISIBLE = 3.5f
    private const val Z_BASE = 8f
    private const val Z_STEP = 2f
    private const val COLLAPSED_CLICK_PE = 0.92f
    private const val PREFS_TTL_MS = 800L

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    private var lastHostRef: WeakReference<ViewGroup>? = null
    private var lastPe: Float = 1f
    private var lastESeg: Float = 0f
    private var lastScrollRange: Float = 0f
    private var stackHitTop: Float = 0f
    private var stackHitBottom: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var touchTracking: Boolean = false
    private var touchMoved: Boolean = false

    // —— 缓存 ——
    private var density = 0f
    private var pxStep = 0f
    private var pxPeek = 0f
    private var pxExpandE = 0f
    private var px8 = 0f
    private var px6 = 0f
    private var px4 = 0f
    private var px48 = 0f
    private var px72 = 0f

    private var cachedBottomPad = DEFAULT_BOTTOM_PAD
    private var cachedSinkAll = true
    private var prefsCachedAt = 0L

    /** 已做过 outline/elevation 抑制的 row，避免每帧反射清阴影 */
    private val shadowDone = IdentityHashMap<View, Boolean>()
    private var shadowGen = 0

    private data class Item(
        val view: View,
        val st: Any,
        val height: Float,
        val nativeY: Float,
        val index: Int,
        val parentY: Float? = null,
        val summary: View? = null,
        val foldedSummary: Boolean = false,
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return
        prefsPackage = packageName
        loadParam = lpparam
        try {
            mount(lpparam)
            Logger.i(TAG, "堆叠 Hook 完成 (showcase layoutFor)")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook 挂载失败", e)
        }
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam) {
        ensurePx()
        val algoCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm",
            lpparam.classLoader
        )
        val ambientCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.stack.AmbientState",
            lpparam.classLoader
        )
        val rowCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            algoCl,
            "resetViewStates",
            ambientCl,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        apply(param.args[0], param.thisObject, rowCl)
                    } catch (e: Throwable) {
                        Logger.e(TAG, "堆叠失败", e)
                    }
                }
            }
        )

        try {
            val clickerCl = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.NotificationClicker",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clickerCl,
                "onClick",
                View::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!rowCl.isInstance(view)) return
                        if (!isStackCollapsed()) return
                        if (tryExpandStackFromClick()) {
                            param.result = null
                            Logger.d(TAG, "折叠点击(row) → 展开列表")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "点击展开 Hook 失败", e)
        }

        try {
            val nsslCl = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                nsslCl,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val host = param.thisObject as? ViewGroup ?: return
                        val ev = param.args[0] as? MotionEvent ?: return
                        if (!isStackCollapsed()) {
                            touchTracking = false
                            return
                        }
                        val slop = ViewConfiguration.get(host.context).scaledTouchSlop
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchDownX = ev.x
                                touchDownY = ev.y
                                touchMoved = false
                                touchTracking = isInStackHitRegion(ev.x, ev.y, host)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (touchTracking &&
                                    (abs(ev.x - touchDownX) > slop ||
                                        abs(ev.y - touchDownY) > slop)
                                ) {
                                    touchMoved = true
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (touchTracking && !touchMoved &&
                                    isInStackHitRegion(ev.x, ev.y, host)
                                ) {
                                    if (tryExpandStackFromClick()) {
                                        param.result = true
                                        Logger.d(TAG, "折叠整区点击 → 展开列表")
                                    }
                                }
                                touchTracking = false
                            }
                            MotionEvent.ACTION_CANCEL -> touchTracking = false
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "NSSL 整区点击 Hook 失败", e)
        }
    }

    private fun isStackCollapsed(): Boolean = lastPe < COLLAPSED_CLICK_PE

    private fun isInStackHitRegion(x: Float, y: Float, host: ViewGroup): Boolean {
        if (stackHitBottom <= stackHitTop + 1f) return false
        val padX = px8
        if (x < -padX || x > host.width + padX) return false
        return y in (stackHitTop - px4)..(stackHitBottom + px(12f))
    }

    private fun tryExpandStackFromClick(): Boolean {
        val host = lastHostRef?.get() ?: return false
        val eSeg = lastESeg
        val range = lastScrollRange
        if (eSeg <= 1f && range <= 2f) return false
        val target = when {
            range > 2f -> min(max(eSeg, pxExpandE), range).toInt()
            else -> max(eSeg, pxExpandE).toInt()
        }.coerceAtLeast(1)

        val cur = try {
            XposedHelpers.callMethod(host, "getOwnScrollY") as Int
        } catch (_: Throwable) {
            try {
                XposedHelpers.getIntField(host, "mOwnScrollY")
            } catch (_: Throwable) {
                0
            }
        }
        if (target <= cur + 2) return false
        val dy = target - cur

        return try {
            val scroller = XposedHelpers.getObjectField(host, "mScroller")
            val scrollX = try {
                host.scrollX
            } catch (_: Throwable) {
                0
            }
            XposedHelpers.callMethod(scroller, "startScroll", scrollX, cur, 0, dy)
            try {
                XposedHelpers.setBooleanField(host, "mDontReportNextOverScroll", true)
            } catch (_: Throwable) {
            }
            XposedHelpers.callMethod(host, "animateScroll")
            true
        } catch (e: Throwable) {
            Logger.e(TAG, "动画展开失败，回退 setOwnScrollY", e)
            try {
                XposedHelpers.callMethod(host, "setOwnScrollY", target)
                true
            } catch (e2: Throwable) {
                Logger.e(TAG, "setOwnScrollY 失败", e2)
                false
            }
        }
    }

    private fun refreshPrefsIfNeeded() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - prefsCachedAt < PREFS_TTL_MS) return
        prefsCachedAt = now
        val lp = loadParam
        cachedBottomPad = if (lp != null) {
            XposedPrefs.getFeatureValue(lp, prefsPackage, FEATURE_KEY, DEFAULT_BOTTOM_PAD)
                .coerceIn(80, 400)
        } else {
            DEFAULT_BOTTOM_PAD
        }
        cachedSinkAll = try {
            val prefs = de.robv.android.xposed.XSharedPreferences(
                "com.karen.flymetool", "flymetool_prefs"
            )
            prefs.reload()
            prefs.getBoolean("$prefsPackage:$SINK_ALL_KEY", true)
        } catch (_: Throwable) {
            true
        }
    }

    private fun apply(ambient: Any, algo: Any, rowCl: Class<*>) {
        ensurePx()
        refreshPrefsIfNeeded()

        val host = XposedHelpers.getObjectField(algo, "mHostView") as? ViewGroup ?: return
        lastHostRef = WeakReference(host)
        if (hasPinnedHeadsUp(host, rowCl)) {
            lastPe = 1f
            return
        }

        // QS 先读：展开控制中心时尽量少干活
        val qsFrac = readQsExpansion(ambient)
        val trackedHun = readTrackedHun(ambient)
        val items = collect(host, rowCl, ambient, trackedHun)
        if (items.isEmpty()) {
            lastPe = 1f
            return
        }

        val stackY = readStackY(ambient)
        val innerH = readInnerHeight(ambient)
        if (innerH <= 0) return

        val contentBottom = stackY + innerH
        val thresh = contentBottom - cachedBottomPad * density
        val step = pxStep
        val peek = pxPeek
        val pad = readPadding(algo)
        val scrollY = readScrollY(ambient)
        val scrollRange = readScrollRange(host, items, pad, innerH)
        val eSeg = if (scrollRange > 2f) {
            min(pxExpandE, max(scrollRange * 0.35f, pxExpandE * 0.5f))
        } else {
            pxExpandE
        }
        lastESeg = eSeg
        lastScrollRange = scrollRange
        val pe = if (eSeg <= 1f || scrollRange <= 2f) {
            1f
        } else {
            (scrollY / eSeg).coerceIn(0f, 1f)
        }
        lastPe = pe

        if (qsFrac > 0.08f) {
            lastPe = 1f
            restoreSystemList(items, ambient, trackedHun)
            hideShelf(ambient)
            return
        }

        if (scrollRange <= 2f || (scrollRange > 2f && scrollY >= scrollRange - 4f)) {
            lastPe = 1f
            restoreSystemList(items, ambient, trackedHun)
            hideShelf(ambient)
            return
        }

        val sinkAll = cachedSinkAll
        val listTop = stackY + px8
        val ease = pe * (2f - pe)
        val collapsedTop = if (sinkAll) thresh else listTop
        // winTop 保留与 showcase 一致（后续 et 用 nativeY lerp，不直接用 winTop）
        @Suppress("UNUSED_VARIABLE")
        val winTop = lerp(collapsedTop, listTop, ease)

        val maxLayer = min(items.size - 1, MAX_L_VISIBLE.toInt())
        stackHitTop = thresh
        stackHitBottom = thresh + maxLayer * peek +
            (items.firstOrNull()?.height ?: px72)

        val firstOverflow = if (sinkAll) {
            0
        } else {
            items.indexOfFirst { it.nativeY >= thresh - px8 }.coerceAtLeast(0)
        }
        // 堆叠起点（通知与媒体共用，媒体不占位挤通知）
        val stackAnchor = if (sinkAll || firstOverflow <= 0) {
            thresh
        } else {
            val lastFull = items[firstOverflow - 1]
            max(thresh, lastFull.nativeY + lastFull.height + px6)
        }

        // 媒体：沉在堆叠起点之上（Y = stackAnchor - h - pad），不挤通知、不参与 L 堆叠
        if (sinkAll) {
            val media = findMediaContainer(host)
            if (media != null) {
                placeMediaAboveStack(media, pe, stackAnchor, pad)
            }
        }

        val touchedSummaries = ArrayList<View>(2)

        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, item.st, ambient, trackedHun)) continue
            val st = item.st
            val h = max(item.height, 1f)
            val i = item.index
            val childInGroup = item.parentY != null
            val foldedSummary = item.foldedSummary

            val ty: Float
            var scale: Float
            val alpha: Float

            if (!sinkAll && item.nativeY < thresh - px8) {
                ty = item.nativeY
                scale = 1f
                alpha = 1f
            } else {
                val relativeI = if (sinkAll) i else (i - firstOverflow).coerceAtLeast(0)
                val collapsedEt = stackAnchor + relativeI * step
                val et = lerp(collapsedEt, item.nativeY, pe)
                val over = et - thresh

                if (over < 0f) {
                    ty = et
                    scale = 1f
                    alpha = 1f
                } else {
                    val L = over / step
                    if (L >= MAX_L_VISIBLE) {
                        setBool(st, "hidden", true)
                        setAlpha(st, 0f)
                        setScale(st, MIN_SCALE)
                        setZ(st, 0f)
                        writeY(st, item, stackAnchor + MAX_L_VISIBLE * peek)
                        continue
                    }
                    ty = thresh + L * peek
                    scale = max(MIN_SCALE, 1f - L * SCALE_PER_L)
                    alpha = (1f - (L - 0.2f) * 0.5f).coerceIn(0f, 1f)
                }
            }

            if (foldedSummary) {
                scale = 1f
            }

            val yAbs = if (foldedSummary) ty else ty - h * (1f - scale) * 0.5f
            val z = Z_BASE - i * Z_STEP

            writeY(st, item, yAbs)
            // 堆叠布局使用的高度写回 ViewState，避免系统按大布局 intrinsic 撑高
            setInt(st, "height", h.toInt().coerceAtLeast(1))
            setScale(st, scale)
            setAlpha(st, alpha)
            setZ(st, z)
            setBool(st, "hidden", false)
            setBool(st, "inShelf", false)
            setInt(st, "clipBottomAmount", 0)
            setInt(st, "clipTopAmount", 0)

            clearElevationIfNeeded(item.view)
            if (foldedSummary || childInGroup) {
                suppressShadowOnce(item.view)
            }
            val sum = item.summary
            if (sum != null && sum !in touchedSummaries) touchedSummaries += sum
        }

        for (summary in touchedSummaries) {
            flattenExpandedSummaryShell(summary, items, ambient, trackedHun)
        }

        hideShelf(ambient)
        Logger.once(
            TAG,
            "stack sink=$sinkAll pe=$pe scroll=$scrollY/$scrollRange n=${items.size}"
        )
    }

    private fun writeY(st: Any, item: Item, yAbs: Float) {
        val local = item.parentY?.let { yAbs - it } ?: yAbs
        setY(st, local)
    }

    private fun flattenExpandedSummaryShell(
        summary: View,
        items: List<Item>,
        ambient: Any,
        trackedHun: Any?,
    ) {
        val sst = viewState(summary) ?: return
        if (isPinnedOrAnimatingHun(summary, sst, ambient, trackedHun)) return
        val kids = items.filter { it.summary === summary }
        if (kids.isEmpty()) return
        val oldParentY = kids.first().parentY ?: return
        val headerInset = groupHeaderInset(summary)

        var minAbs = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE
        var minIndex = Int.MAX_VALUE
        val n = kids.size
        val absYArr = FloatArray(n)
        val hArr = FloatArray(n)
        val stArr = arrayOfNulls<Any>(n)
        var k = 0
        for (c in kids) {
            val localY = getY(c.st)
            val absY = oldParentY + localY
            val h = max(c.height, 1f)
            absYArr[k] = absY
            hArr[k] = h
            stArr[k] = c.st
            if (absY < minAbs) minAbs = absY
            if (absY + h > maxBottom) maxBottom = absY + h
            if (c.index < minIndex) minIndex = c.index
            k++
        }
        if (minAbs == Float.MAX_VALUE) return

        val shellTop = minAbs - headerInset
        val shellH = max(maxBottom - shellTop, headerInset + hArr[0]).toInt().coerceAtLeast(1)
        val topZ = Z_BASE - minIndex * Z_STEP

        setY(sst, shellTop)
        setInt(sst, "height", shellH)
        setScale(sst, 1f)
        setAlpha(sst, 1f)
        setZ(sst, topZ - 0.5f)
        setBool(sst, "hidden", false)
        setInt(sst, "clipBottomAmount", 0)
        setInt(sst, "clipTopAmount", 0)

        for (i in 0 until n) {
            val s = stArr[i] ?: continue
            setY(s, absYArr[i] - shellTop)
        }

        raiseGroupHeaderAboveChildren(summary, topZ + Z_STEP)
        clearElevationIfNeeded(summary)
        suppressShadowOnce(summary)
        try {
            if (summary.scaleX != 1f) summary.scaleX = 1f
            if (summary.scaleY != 1f) summary.scaleY = 1f
        } catch (_: Throwable) {
        }
    }

    private fun groupHeaderInset(summary: View): Float {
        try {
            val container = XposedHelpers.callMethod(summary, "getChildrenContainer")
            if (container != null) {
                for (field in arrayOf("mHeaderHeight", "mCollapsedHeaderMargin")) {
                    try {
                        val v = XposedHelpers.getIntField(container, field)
                        if (v > 8) return v.toFloat()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    val header = XposedHelpers.callMethod(container, "getGroupHeader") as? View
                    val h = header?.height ?: 0
                    if (h > 8) return h.toFloat()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return px48
    }

    private fun raiseGroupHeaderAboveChildren(summary: View, z: Float) {
        try {
            val container = XposedHelpers.callMethod(summary, "getChildrenContainer") ?: return
            val header = try {
                XposedHelpers.callMethod(container, "getGroupHeader") as? View
            } catch (_: Throwable) {
                null
            }
            if (header != null) {
                if (header.translationZ != z) header.translationZ = z
                if (header.elevation != 0f) header.elevation = 0f
                val hst = try {
                    XposedHelpers.getObjectField(container, "mHeaderViewState")
                } catch (_: Throwable) {
                    null
                }
                if (hst != null) {
                    setZ(hst, z)
                    setY(hst, 0f)
                    setAlpha(hst, 1f)
                    setBool(hst, "hidden", false)
                }
            }
            try {
                val gc = XposedHelpers.callMethod(summary, "getGroupCollapseContainer") as? View
                if (gc != null && gc.visibility == View.VISIBLE && gc.alpha > 0.01f) {
                    if (gc.translationZ != z) gc.translationZ = z
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreSystemList(
        items: List<Item>,
        ambient: Any,
        trackedHun: Any?,
    ) {
        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, item.st, ambient, trackedHun)) continue
            val st = item.st
            setScale(st, 1f)
            if (boolField(st, "inShelf")) setBool(st, "inShelf", false)
            try {
                val a = XposedHelpers.callMethod(st, "getAlpha") as Float
                if (a in 0.01f..0.99f && !boolField(st, "hidden")) setAlpha(st, 1f)
            } catch (_: Throwable) {
                setAlpha(st, 1f)
            }
            setZ(st, 0f)
            try {
                if (item.view.translationZ != 0f) item.view.translationZ = 0f
            } catch (_: Throwable) {
            }
            setInt(st, "clipBottomAmount", 0)
            setInt(st, "clipTopAmount", 0)
            clearElevationIfNeeded(item.view)
            if (item.foldedSummary || item.parentY != null) {
                suppressShadowOnce(item.view)
            }
        }
    }

    private fun readQsExpansion(ambient: Any): Float {
        try {
            val v = XposedHelpers.callMethod(ambient, "getQsExpansionFraction")
            if (v is Float && v >= 0f) return v
            if (v is Double && v >= 0.0) return v.toFloat()
        } catch (_: Throwable) {
        }
        return 0f
    }

    private fun isGroupSummary(row: View): Boolean = callBool(row, "isSummaryWithChildren")

    private fun isGroupExpandedLike(row: View): Boolean {
        if (callBool(row, "isGroupExpanded")) return true
        if (callBool(row, "areChildrenExpanded")) return true
        return callBool(row, "isGroupExpansionChanging")
    }

    private fun clearElevationIfNeeded(row: View) {
        try {
            if (row.elevation != 0f) row.elevation = 0f
        } catch (_: Throwable) {
        }
    }

    private fun suppressShadowOnce(row: View) {
        if (shadowDone.put(row, true) != null) return
        // 防止无限涨：偶发清理
        if (shadowDone.size > 64) {
            shadowDone.clear()
            shadowDone[row] = true
            shadowGen++
        }
        killOutlineShadow(row)
        try {
            val bg = XposedHelpers.getObjectField(row, "mBackgroundFlyme") as? View
            if (bg != null) killOutlineShadow(bg)
        } catch (_: Throwable) {
        }
        try {
            val bgN = XposedHelpers.getObjectField(row, "mBackgroundNormal") as? View
            if (bgN != null) killOutlineShadow(bgN)
        } catch (_: Throwable) {
        }
        try {
            val gc = XposedHelpers.callMethod(row, "getGroupCollapseContainer") as? View
            if (gc != null) {
                killOutlineShadow(gc)
                try {
                    val gbg = XposedHelpers.getObjectField(gc, "mBackgroundFlyme") as? View
                    if (gbg != null) killOutlineShadow(gbg)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(row, "setFakeShadowIntensity", 0f, 0f, 0, 0)
        } catch (_: Throwable) {
        }
    }

    private fun killOutlineShadow(v: View) {
        try {
            if (v.elevation != 0f) v.elevation = 0f
        } catch (_: Throwable) {
        }
        try {
            v.outlineSpotShadowColor = 0
            v.outlineAmbientShadowColor = 0
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(v, "setOutlineAlpha", 0f)
        } catch (_: Throwable) {
        }
    }

    private fun readScrollY(ambient: Any): Float = try {
        (XposedHelpers.callMethod(ambient, "getScrollY") as Int).toFloat()
    } catch (_: Throwable) {
        0f
    }

    private fun readPadding(algo: Any): Float = try {
        XposedHelpers.getFloatField(algo, "mPaddingBetweenElements")
    } catch (_: Throwable) {
        px4
    }

    private fun readScrollRange(
        host: ViewGroup,
        items: List<Item>,
        pad: Float,
        innerH: Int,
    ): Float {
        for (name in arrayOf("getScrollRange", "getMaxScrollAmount")) {
            try {
                val v = XposedHelpers.callMethod(host, name)
                val f = when (v) {
                    is Int -> v.toFloat()
                    is Float -> v
                    else -> continue
                }
                if (f > 2f) return f
            } catch (_: Throwable) {
            }
        }
        try {
            val content = XposedHelpers.callMethod(host, "getContentHeight") as Int
            val maxH = try {
                XposedHelpers.getIntField(host, "mMaxLayoutHeight")
            } catch (_: Throwable) {
                host.height
            }
            val r = (content - maxH).toFloat()
            if (r > 2f) return r
        } catch (_: Throwable) {
        }
        var total = 0f
        for (item in items) total += max(item.height, 1f) + pad
        return max(0f, total - innerH)
    }

    private fun hasPinnedHeadsUp(host: ViewGroup, rowCl: Class<*>): Boolean {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (!rowCl.isInstance(child)) continue
            if (callBool(child, "isHeadsUp") && callBool(child, "isPinned")) return true
            if (boolField(child, "mIsHeadsUpStatus") && callBool(child, "isPinned")) return true
        }
        return false
    }

    /**
     * 锁屏媒体容器 MediaContainerView（非 ExpandableNotificationRow）。
     * 特征：类名 MediaContainerView / 继承 ExpandableView 且非 Row。
     */
    private fun findMediaContainer(host: ViewGroup): View? {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (child.visibility == View.GONE) continue
            val name = child.javaClass.name
            if (name.endsWith("MediaContainerView") || name.contains("MediaContainerView")) {
                val st = viewState(child) ?: continue
                if (boolField(st, "gone")) continue
                // shouldBeVisible=false 时系统视为不展示
                if (!mediaShouldBeVisible(st)) continue
                return child
            }
        }
        return null
    }

    private fun mediaShouldBeVisible(st: Any): Boolean {
        return try {
            XposedHelpers.callMethod(st, "getShouldBeVisible") as? Boolean ?: true
        } catch (_: Throwable) {
            try {
                XposedHelpers.getBooleanField(st, "shouldBeVisible")
            } catch (_: Throwable) {
                true
            }
        }
    }

    /**
     * 媒体沉到堆叠起点之上：折叠时底边贴 stackAnchor（Y = stackAnchor - h - pad），
     * 通知仍从 stackAnchor 堆叠；展开插值回 nativeY。不改 stackAnchor、不参与 L 缩放。
     */
    private fun placeMediaAboveStack(media: View, pe: Float, stackAnchor: Float, pad: Float) {
        val st = viewState(media) ?: return
        val h = readMediaHeight(media, st)
        if (h <= 1f) return
        val nativeY = getY(st)
        // 折叠：媒体在堆叠首卡正上方
        val collapsedY = stackAnchor - h - pad
        val y = lerp(collapsedY, nativeY, pe)
        setY(st, y)
        setInt(st, "height", h.toInt().coerceAtLeast(1))
        setScale(st, 1f)
        setAlpha(st, 1f)
        setZ(st, Z_BASE + Z_STEP)
        setBool(st, "hidden", false)
        setBool(st, "inShelf", false)
        setInt(st, "clipBottomAmount", 0)
        setInt(st, "clipTopAmount", 0)
        clearElevationIfNeeded(media)
    }

    private fun readMediaHeight(media: View, st: Any): Float {
        try {
            val h = XposedHelpers.getIntField(st, "height").toFloat()
            if (h > 1f) return h
        } catch (_: Throwable) {
        }
        try {
            val ih = XposedHelpers.callMethod(media, "getIntrinsicHeight") as Int
            if (ih > 1) return ih.toFloat()
        } catch (_: Throwable) {
        }
        try {
            val ah = XposedHelpers.callMethod(media, "getActualHeight") as Int
            if (ah > 1) return ah.toFloat()
        } catch (_: Throwable) {
        }
        return media.height.toFloat().coerceAtLeast(0f)
    }

    private fun collect(
        host: ViewGroup,
        rowCl: Class<*>,
        ambient: Any,
        trackedHun: Any?,
    ): List<Item> {
        val tops = ArrayList<View>(host.childCount)
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (!rowCl.isInstance(child)) continue
            if (child.visibility == View.GONE) continue
            val st = viewState(child) ?: continue
            if (boolField(st, "gone")) continue
            if (isPinnedOrAnimatingHun(child, st, ambient, trackedHun)) continue
            tops += child
        }
        tops.sortBy {
            try {
                XposedHelpers.getIntField(viewState(it)!!, "notGoneIndex")
            } catch (_: Throwable) {
                Int.MAX_VALUE
            }
        }

        val flat = ArrayList<Item>(tops.size + 4)
        var idx = 0
        for (v in tops) {
            val st = viewState(v)!!
            val parentY = getY(st)
            if (isGroupExpandedLike(v)) {
                val children = attachedChildren(v)
                if (children.isNotEmpty()) {
                    for (c in children) {
                        if (c.visibility == View.GONE) continue
                        val cst = viewState(c) ?: continue
                        if (boolField(cst, "gone")) continue
                        flat += Item(
                            view = c,
                            st = cst,
                            height = readStackHeight(c, cst),
                            nativeY = parentY + getY(cst),
                            index = idx++,
                            parentY = parentY,
                            summary = v,
                        )
                    }
                    continue
                }
            }
            flat += Item(
                view = v,
                st = st,
                height = readStackHeight(v, st),
                nativeY = parentY,
                index = idx++,
                foldedSummary = isGroupSummary(v),
            )
        }
        return flat
    }

    @Suppress("UNCHECKED_CAST")
    private fun attachedChildren(summary: View): List<View> {
        return try {
            val list = XposedHelpers.callMethod(summary, "getAttachedChildren") as? List<*>
            list?.filterIsInstance<View>() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isPinnedOrAnimatingHun(
        row: View,
        st: Any?,
        ambient: Any,
        trackedHun: Any?,
    ): Boolean {
        if (trackedHun != null && trackedHun === row) return true
        if (callBool(row, "isPinned")) return true
        if (callBool(row, "isHeadsUpAnimatingAway")) return true
        if (callBool(row, "showingPulsing")) return true
        if (boolField(row, "mHeadsupDisappearRunning")) return true
        if (st != null) {
            try {
                if (XposedHelpers.getIntField(st, "location") == 1) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun callBool(obj: Any, name: String): Boolean = try {
        XposedHelpers.callMethod(obj, name) as Boolean
    } catch (_: Throwable) {
        false
    }

    private fun readTrackedHun(ambient: Any): Any? = try {
        XposedHelpers.callMethod(ambient, "getTrackedHeadsUpRow")
    } catch (_: Throwable) {
        null
    }

    private fun hideShelf(ambient: Any) {
        try {
            val shelf = XposedHelpers.callMethod(ambient, "getShelf") ?: return
            val st = XposedHelpers.callMethod(shelf, "getViewState") ?: return
            setBool(st, "hidden", true)
            setAlpha(st, 0f)
        } catch (_: Throwable) {
        }
    }

    /**
     * 堆叠态高度：未手动展开 → 强制折叠高（不被长文撑开）；
     * 用户手动展开 → 保留系统内容高度。
     */
    private fun readStackHeight(row: View, st: Any): Float {
        if (allowContentExpand(row)) {
            return readSystemHeight(row, st)
        }
        val collapsed = try {
            (XposedHelpers.callMethod(row, "getCollapsedHeight") as Int).toFloat()
        } catch (_: Throwable) {
            try {
                (XposedHelpers.callMethod(row, "getMinHeight") as Int).toFloat()
            } catch (_: Throwable) {
                0f
            }
        }
        if (collapsed > 1f) return collapsed
        return readSystemHeight(row, st)
    }

    /** 用户点开单卡展开（含 hasUserChangedExpansion）才允许内容撑高 */
    private fun allowContentExpand(row: View): Boolean {
        if (callBool(row, "isUserExpanded")) return true
        return callBool(row, "hasUserChangedExpansion") && callBool(row, "isExpanded")
    }

    private fun readSystemHeight(row: View, st: Any): Float {
        val h = try {
            XposedHelpers.getIntField(st, "height").toFloat()
        } catch (_: Throwable) {
            0f
        }
        if (h > 1f) return h
        return try {
            (XposedHelpers.callMethod(row, "getIntrinsicHeight") as Int).toFloat()
        } catch (_: Throwable) {
            px72
        }
    }

    private fun viewState(view: Any): Any? = try {
        XposedHelpers.callMethod(view, "getViewState")
    } catch (_: Throwable) {
        null
    }

    private fun boolField(obj: Any, name: String): Boolean = try {
        XposedHelpers.getBooleanField(obj, name)
    } catch (_: Throwable) {
        false
    }

    private fun readStackY(ambient: Any): Float = try {
        XposedHelpers.callMethod(ambient, "getStackY") as Float
    } catch (_: Throwable) {
        try {
            XposedHelpers.callMethod(ambient, "getStackTop") as Float
        } catch (_: Throwable) {
            0f
        }
    }

    private fun readInnerHeight(ambient: Any): Int = try {
        XposedHelpers.callMethod(ambient, "getInnerHeight") as Int
    } catch (_: Throwable) {
        try {
            XposedHelpers.callMethod(ambient, "getLayoutMaxHeight") as Int
        } catch (_: Throwable) {
            0
        }
    }

    // —— ViewState 写：尽量少反射失败分支 ——

    private fun getY(st: Any): Float = try {
        XposedHelpers.callMethod(st, "getYTranslation") as Float
    } catch (_: Throwable) {
        0f
    }

    private fun setY(st: Any, y: Float) {
        try {
            XposedHelpers.callMethod(st, "setYTranslation", y)
        } catch (_: Throwable) {
        }
    }

    private fun setScale(st: Any, s: Float) {
        try {
            XposedHelpers.callMethod(st, "setScaleX", s)
            XposedHelpers.callMethod(st, "setScaleY", s)
        } catch (_: Throwable) {
        }
    }

    private fun setAlpha(st: Any, a: Float) {
        try {
            XposedHelpers.callMethod(st, "setAlpha", a)
        } catch (_: Throwable) {
        }
    }

    private fun setZ(st: Any, z: Float) {
        try {
            XposedHelpers.callMethod(st, "setZTranslation", z)
        } catch (_: Throwable) {
        }
    }

    private fun setBool(st: Any, name: String, v: Boolean) {
        try {
            XposedHelpers.setBooleanField(st, name, v)
        } catch (_: Throwable) {
        }
    }

    private fun setInt(st: Any, name: String, v: Int) {
        try {
            XposedHelpers.setIntField(st, name, v)
        } catch (_: Throwable) {
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun ensurePx() {
        val d = Resources.getSystem().displayMetrics.density
        if (d == density && pxStep > 0f) return
        density = d
        pxStep = STEP_DP * d
        pxPeek = PEEK_DP * d
        pxExpandE = EXPAND_E_DP * d
        px8 = 8f * d
        px6 = 6f * d
        px4 = 4f * d
        px48 = 48f * d
        px72 = 72f * d
    }

    private fun px(v: Float): Float = v * density

    private fun dp(v: Float): Float {
        ensurePx()
        return v * density
    }
}

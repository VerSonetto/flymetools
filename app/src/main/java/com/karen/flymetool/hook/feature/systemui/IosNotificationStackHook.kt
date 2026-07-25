package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * iOS 锁屏通知堆叠 — 对齐 showcase.html layoutFor。
 *
 * 折叠 p≈0：整组沉底，首卡全尺寸顶在 THRESH，下层 L*PEEK 错位 + 缩放下沉。
 * 展开 p→1：与原生列表 Y 插值；仅 overflow 继续堆叠。
 * 折叠态点击卡片：展开列表（不跳转）；有 pinned HUN 时整段不碰。
 */
object IosNotificationStackHook : FeatureHook {

    private const val TAG = "IosNotifStack"
    private const val FEATURE_KEY = "ios_notification_stack"
    private const val SINK_ALL_KEY = "sink_all"
    /** THRESH 距内容区底的 inset（越大堆叠越高） */
    private const val DEFAULT_BOTTOM_PAD = 180

    // showcase.html
    private const val STEP_DP = 108f
    private const val PEEK_DP = 46f
    /** HTML E：前这段 scroll 只做堆叠摊开，之后才是列表滚动 */
    private const val EXPAND_E_DP = 200f
    private const val SCALE_PER_L = 0.05f
    private const val MIN_SCALE = 0.84f
    private const val MAX_L_VISIBLE = 3.5f
    private const val Z_BASE = 8f
    private const val Z_STEP = 2f
    /** pe 低于此视为折叠，点击展开而非跳转 */
    private const val COLLAPSED_CLICK_PE = 0.92f

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    /** 最近一次布局的 NSSL，用于点击展开 */
    private var lastHostRef: WeakReference<ViewGroup>? = null
    private var lastPe: Float = 1f
    private var lastESeg: Float = 0f
    private var lastScrollRange: Float = 0f
    /** 折叠堆叠可点区域（NSSL 本地坐标，Y 向下） */
    private var stackHitTop: Float = 0f
    private var stackHitBottom: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var touchTracking: Boolean = false
    private var touchMoved: Boolean = false

    private data class Item(
        val view: View,
        val height: Float,
        /** NSSL 内容坐标系 Y（子卡 = parentY + childLocalY） */
        val nativeY: Float,
        val index: Int,
        /** 展开聚合的子卡：Y 写回时要减 parentY；null=顶层 NSSL child */
        val parentY: Float? = null,
        /** 对应的 summary row（子卡时用于压 summary 高度/clip） */
        val summary: View? = null,
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

        // 折叠态：任意通知点击都展开（顶卡也会走到这里）
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

        // 整区点击：顶卡全高会盖住下层，点 peek 往往落在顶卡上；
        // 在 NSSL 上拦截「折叠堆叠带」内的轻点，保证整摞都能展开
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
                                        // 消费点击，避免落到 row 再跳转
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
        // 左右略放宽，上下用堆叠带
        val padX = dp(8f)
        if (x < -padX || x > host.width + padX) return false
        return y in (stackHitTop - dp(4f))..(stackHitBottom + dp(12f))
    }

    /**
     * 点击展开：走 NSSL 同款 OverScroller 动画（与 scrollTo / 手指滑动一致），
     * 不用瞬时 setOwnScrollY。
     */
    private fun tryExpandStackFromClick(): Boolean {
        val host = lastHostRef?.get() ?: return false
        val eSeg = lastESeg
        val range = lastScrollRange
        if (eSeg <= 1f && range <= 2f) return false
        val target = when {
            range > 2f -> min(max(eSeg, expandEPx()), range).toInt()
            else -> max(eSeg, expandEPx()).toInt()
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
            // 与 NSSL.scrollTo 相同：startScroll + animateScroll
            XposedHelpers.callMethod(
                scroller, "startScroll",
                scrollX, cur, 0, dy
            )
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

    private fun expandEPx(): Float = dp(EXPAND_E_DP)

    private fun readBottomPadDp(): Int {
        val lp = loadParam ?: return DEFAULT_BOTTOM_PAD
        return XposedPrefs.getFeatureValue(
            lp, prefsPackage, FEATURE_KEY, DEFAULT_BOTTOM_PAD
        ).coerceIn(0, 280)
    }

    /** 整组沉底开关：默认开启；关闭后只堆叠溢出卡（顶部保持列表） */
    private fun isSinkAllEnabled(): Boolean {
        val lp = loadParam ?: return true
        val key = "$prefsPackage:$SINK_ALL_KEY"
        return try {
            val prefs = de.robv.android.xposed.XSharedPreferences(
                "com.karen.flymetool", "flymetool_prefs"
            )
            prefs.getBoolean(key, true)
        } catch (_: Throwable) {
            true
        }
    }

    private fun apply(ambient: Any, algo: Any, rowCl: Class<*>) {
        val host = XposedHelpers.getObjectField(algo, "mHostView") as? ViewGroup ?: return
        lastHostRef = WeakReference(host)
        if (hasPinnedHeadsUp(host, rowCl)) {
            lastPe = 1f
            return
        }

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
        // THRESH：折叠时首卡顶边（整组沉底锚点）
        val thresh = contentBottom - dp(readBottomPadDp().toFloat())
        val step = dp(STEP_DP)
        val peek = dp(PEEK_DP)
        val pad = readPadding(algo)
        val scrollY = readScrollY(ambient)
        val scrollRange = readScrollRange(host, ambient, items, pad, innerH)
        val expandE = dp(EXPAND_E_DP)
        // HTML: p=clamp(scroll/E)；listOffset=max(0, scroll-E) — 后半段继续跟进度滚，不瞬间全开
        val eSeg = if (scrollRange > 2f) min(expandE, max(scrollRange * 0.35f, expandE * 0.5f)) else expandE
        lastESeg = eSeg
        lastScrollRange = scrollRange
        val pe = if (eSeg <= 1f || scrollRange <= 2f) {
            1f
        } else {
            (scrollY / eSeg).coerceIn(0f, 1f)
        }
        lastPe = pe
        val listOffset = max(0f, scrollY - eSeg)

        // 仅装得下或滚到底时交还系统（清 scale）；中间全程跟进度
        if (scrollRange <= 2f || (scrollRange > 2f && scrollY >= scrollRange - 4f)) {
            lastPe = 1f
            restoreSystemList(items, ambient, trackedHun)
            hideShelf(ambient)
            return
        }

        // showcase: winTop = lerp(THRESH, WIN_TOP, easeOut(p))
        //           et = winTop + i*STEP - listOffset
        val listTop = stackY + dp(8f)
        val ease = pe * (2f - pe)
        // 整组沉底：winTop 从 THRESH（底部）开始；关闭时从 listTop（顶部）开始 → 只堆叠溢出卡
        val collapsedTop = if (isSinkAllEnabled()) thresh else listTop
        val winTop = lerp(collapsedTop, listTop, ease)

        // 折叠堆叠整区命中：首卡顶 → 末层 peek 底（相对 host 内容坐标系 ≈ translationY）
        // 顶卡全高会盖住下层，但整区仍可用 NSSL 点击展开
        val maxLayer = min(items.size - 1, MAX_L_VISIBLE.toInt())
        stackHitTop = thresh
        stackHitBottom = thresh + maxLayer * peek +
            (items.firstOrNull()?.height ?: dp(72f))

        // 非沉底时，找第一张溢出卡索引，用于堆叠的 relativeI
        val firstOverflow = if (isSinkAllEnabled()) 0 else {
            items.indexOfFirst { it.nativeY >= thresh - dp(8f) }.coerceAtLeast(0)
        }
        // 非沉底时，堆叠锚点 = 最后一张完整卡底边 + 间隙，避免与完整区重叠
        val stackAnchor = if (isSinkAllEnabled() || firstOverflow <= 0) {
            thresh
        } else {
            val lastFull = items[firstOverflow - 1]
            max(thresh, lastFull.nativeY + lastFull.height + dp(6f))
        }

        // 展开 summary 在 collect 里已被子卡替换；这里再给仍折叠的 summary 做防阴影
        val touchedSummaries = LinkedHashSet<View>()

        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, viewState(item.view), ambient, trackedHun)) {
                continue
            }
            val st = viewState(item.view) ?: continue
            val h = max(item.height, 1f)
            val i = item.index
            val childInGroup = item.parentY != null
            val foldedSummary = !childInGroup && isGroupSummary(item.view)

            val ty: Float
            var scale: Float
            val alpha: Float
            var stacked: Boolean

            // 非沉底模式：未溢出卡直接用原生 Y（原生间距），仅溢出卡走堆叠
            if (!isSinkAllEnabled() && item.nativeY < thresh - dp(8f)) {
                ty = item.nativeY
                scale = 1f
                alpha = 1f
                stacked = false
            } else {
                // 沉底模式 或 溢出卡：lerp 堆叠位 → 原生位
                val relativeI = if (isSinkAllEnabled()) i else (i - firstOverflow).coerceAtLeast(0)
                val collapsedEt = stackAnchor + relativeI * step
                val et = lerp(collapsedEt, item.nativeY, pe)
                val over = et - thresh

                if (over < 0f) {
                    ty = et
                    scale = 1f
                    alpha = 1f
                    stacked = false
                } else {
                    val L = over / step
                    if (L >= MAX_L_VISIBLE) {
                        XposedHelpers.setBooleanField(st, "hidden", true)
                        XposedHelpers.callMethod(st, "setAlpha", 0f)
                        XposedHelpers.callMethod(st, "setScaleX", MIN_SCALE)
                        XposedHelpers.callMethod(st, "setScaleY", MIN_SCALE)
                        XposedHelpers.callMethod(st, "setZTranslation", 0f)
                        clearShadowArtifacts(item.view)
                        val hideY = stackAnchor + MAX_L_VISIBLE * peek
                        writeY(st, item, hideY)
                        continue
                    }
                    ty = thresh + L * peek
                    scale = max(MIN_SCALE, 1f - L * SCALE_PER_L)
                    alpha = (1f - (L - 0.2f) * 0.5f).coerceIn(0f, 1f)
                    stacked = true
                }
            }

            // 折叠 summary：不 scale（灰边）；展开子卡：完整参与堆叠 scale/z
            if (foldedSummary) {
                scale = 1f
                stacked = false
            }

            val yAbs = if (foldedSummary) ty else ty - h * (1f - scale) * 0.5f
            val z = Z_BASE - i * Z_STEP

            writeY(st, item, yAbs)
            XposedHelpers.callMethod(st, "setScaleX", scale)
            XposedHelpers.callMethod(st, "setScaleY", scale)
            XposedHelpers.callMethod(st, "setAlpha", alpha)
            XposedHelpers.callMethod(st, "setZTranslation", z)
            XposedHelpers.setBooleanField(st, "hidden", false)
            XposedHelpers.setBooleanField(st, "inShelf", false)
            XposedHelpers.setIntField(st, "clipBottomAmount", 0)
            XposedHelpers.setIntField(st, "clipTopAmount", 0)

            clearShadowArtifacts(item.view)
            // Flyme row 全程 clipToOutline；组 summary/子卡在 z/scale 下都会出灰圆角边
            if (foldedSummary || childInGroup) {
                if (foldedSummary) {
                    try {
                        if (item.view.scaleX != 1f) item.view.scaleX = 1f
                        if (item.view.scaleY != 1f) item.view.scaleY = 1f
                    } catch (_: Throwable) {
                    }
                }
                suppressGroupOutlineShadow(item.view)
            }
            item.summary?.let { touchedSummaries += it }
        }

        // 展开聚合：summary 本体只作容器，压高度/清 outline，避免整组大框
        for (summary in touchedSummaries) {
            flattenExpandedSummaryShell(summary, items, ambient, trackedHun)
        }

        hideShelf(ambient)
        Logger.once(
            TAG,
            "stack sink=${isSinkAllEnabled()} pe=$pe scroll=$scrollY/$scrollRange n=${items.size}"
        )
    }

    /** 子卡 nativeY 是 NSSL 绝对坐标，ViewState 要 parent 本地 Y */
    private fun writeY(st: Any, item: Item, yAbs: Float) {
        val local = item.parentY?.let { yAbs - it } ?: yAbs
        XposedHelpers.callMethod(st, "setYTranslation", local)
    }

    /**
     * 展开后的 summary：子卡已各自堆叠，summary 只作容器。
     * 组头/收起按钮在 ChildrenContainer 顶（Y≈0），子卡 localY 不得压到 0。
     * summary 顶 = 首子绝对 Y - headerInset，子卡 local 从 headerInset 起排。
     */
    private fun flattenExpandedSummaryShell(
        summary: View,
        items: List<Item>,
        ambient: Any,
        trackedHun: Any?,
    ) {
        if (isPinnedOrAnimatingHun(summary, viewState(summary), ambient, trackedHun)) return
        val st = viewState(summary) ?: return
        val kids = items.filter { it.summary === summary }
        if (kids.isEmpty()) return
        val oldParentY = kids.first().parentY ?: return
        val headerInset = groupHeaderInset(summary)

        data class KidAbs(val view: View, val st: Any, val absY: Float, val h: Float, val index: Int)
        val absKids = ArrayList<KidAbs>(kids.size)
        for (k in kids) {
            val kst = viewState(k.view) ?: continue
            val localY = try {
                XposedHelpers.callMethod(kst, "getYTranslation") as Float
            } catch (_: Throwable) {
                continue
            }
            absKids += KidAbs(k.view, kst, oldParentY + localY, max(k.height, 1f), k.index)
        }
        if (absKids.isEmpty()) return

        val minAbs = absKids.minOf { it.absY }
        val maxBottom = absKids.maxOf { it.absY + it.h }
        // summary 顶上留组头；子卡相对 summary 至少 headerInset
        val shellTop = minAbs - headerInset
        val shellH = max(maxBottom - shellTop, headerInset + absKids.first().h)
            .toInt().coerceAtLeast(1)
        val topZ = Z_BASE - absKids.minOf { it.index } * Z_STEP

        try {
            XposedHelpers.callMethod(st, "setYTranslation", shellTop)
            XposedHelpers.setIntField(st, "height", shellH)
            XposedHelpers.callMethod(st, "setScaleX", 1f)
            XposedHelpers.callMethod(st, "setScaleY", 1f)
            XposedHelpers.callMethod(st, "setAlpha", 1f)
            // 容器略低于子卡，组头单独抬 z
            XposedHelpers.callMethod(st, "setZTranslation", topZ - 0.5f)
            XposedHelpers.setBooleanField(st, "hidden", false)
            XposedHelpers.setIntField(st, "clipBottomAmount", 0)
            XposedHelpers.setIntField(st, "clipTopAmount", 0)
        } catch (_: Throwable) {
        }

        for (k in absKids) {
            try {
                XposedHelpers.callMethod(k.st, "setYTranslation", k.absY - shellTop)
            } catch (_: Throwable) {
            }
        }

        raiseGroupHeaderAboveChildren(summary, topZ + Z_STEP)
        clearShadowArtifacts(summary)
        suppressGroupOutlineShadow(summary)
        try {
            if (summary.scaleX != 1f) summary.scaleX = 1f
            if (summary.scaleY != 1f) summary.scaleY = 1f
        } catch (_: Throwable) {
        }
    }

    /** 展开组头高度（收起按钮所在条），读不到则 48dp */
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
        return dp(48f)
    }

    /** 组头/收起钮抬到子卡之上，避免被堆叠卡挡住点击 */
    private fun raiseGroupHeaderAboveChildren(summary: View, z: Float) {
        try {
            val container = XposedHelpers.callMethod(summary, "getChildrenContainer") ?: return
            val header = try {
                XposedHelpers.callMethod(container, "getGroupHeader") as? View
            } catch (_: Throwable) {
                null
            }
            if (header != null) {
                header.translationZ = z
                header.elevation = 0f
                try {
                    header.bringToFront()
                } catch (_: Throwable) {
                }
                val hst = try {
                    XposedHelpers.getObjectField(container, "mHeaderViewState")
                } catch (_: Throwable) {
                    null
                }
                if (hst != null) {
                    XposedHelpers.callMethod(hst, "setZTranslation", z)
                    XposedHelpers.callMethod(hst, "setYTranslation", 0f)
                    XposedHelpers.callMethod(hst, "setAlpha", 1f)
                    try {
                        XposedHelpers.setBooleanField(hst, "hidden", false)
                    } catch (_: Throwable) {
                    }
                }
            }
            // Flyme 折叠容器上的 header（若仍可见）
            try {
                val gc = XposedHelpers.callMethod(summary, "getGroupCollapseContainer") as? View
                if (gc != null && gc.visibility == View.VISIBLE && gc.alpha > 0.01f) {
                    gc.translationZ = z
                    gc.bringToFront()
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    /** pe=1：清掉我们写过的 scale/alpha/z，保留系统 Y */
    private fun restoreSystemList(
        items: List<Item>,
        ambient: Any,
        trackedHun: Any?,
    ) {
        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, viewState(item.view), ambient, trackedHun)) {
                continue
            }
            val st = viewState(item.view) ?: continue
            XposedHelpers.callMethod(st, "setScaleX", 1f)
            XposedHelpers.callMethod(st, "setScaleY", 1f)
            // 不强制 alpha/hidden：交给系统（含敏感内容等）
            if (boolField(st, "inShelf")) {
                XposedHelpers.setBooleanField(st, "inShelf", false)
            }
            try {
                val a = XposedHelpers.callMethod(st, "getAlpha") as Float
                if (a in 0.01f..0.99f && !boolField(st, "hidden")) {
                    XposedHelpers.callMethod(st, "setAlpha", 1f)
                }
            } catch (_: Throwable) {
                XposedHelpers.callMethod(st, "setAlpha", 1f)
            }
            // 全展开时仍保持列表序 z，避免聚合展开与下方卡片瞬态重叠穿层
            XposedHelpers.callMethod(st, "setZTranslation", Z_BASE - item.index * Z_STEP)
            XposedHelpers.setIntField(st, "clipBottomAmount", 0)
            XposedHelpers.setIntField(st, "clipTopAmount", 0)
            clearShadowArtifacts(item.view)
            if (isGroupSummary(item.view) || item.parentY != null || callBool(item.view, "isChildInGroup")) {
                suppressGroupOutlineShadow(item.view)
            }
        }
    }

    private fun isGroupSummary(row: View): Boolean = callBool(row, "isSummaryWithChildren")

    private fun isGroupExpandedLike(row: View): Boolean {
        if (callBool(row, "isGroupExpanded")) return true
        if (callBool(row, "areChildrenExpanded")) return true
        // 展开动画中 needsOutline 仍 true，按已展开处理避免闪阴影
        return callBool(row, "isGroupExpansionChanging")
    }

    /** elevation + FakeShadow + 残留 translationZ */
    private fun clearShadowArtifacts(row: View) {
        try {
            if (row.elevation != 0f) row.elevation = 0f
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(row, "setFakeShadowIntensity", 0f, 0f, 0, 0)
        } catch (_: Throwable) {
        }
    }

    /**
     * Flyme ENR 全程 clipToOutline + outlineAlpha≈1，translationZ/scale 会出灰圆角边。
     * outlineAlpha=0 + 透明 spot/ambient，保留 z 分层但不画阴影框。
     */
    private fun suppressGroupOutlineShadow(row: View) {
        try {
            XposedHelpers.callMethod(row, "setOutlineAlpha", 0f)
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(row, "setOutlineAlpha", java.lang.Float.valueOf(0f))
            } catch (_: Throwable) {
            }
        }
        try {
            row.outlineSpotShadowColor = 0
            row.outlineAmbientShadowColor = 0
        } catch (_: Throwable) {
        }
        // 背景层自己的 outline 阴影
        try {
            val bg = XposedHelpers.getObjectField(row, "mBackgroundFlyme") as? View
            if (bg != null) {
                bg.elevation = 0f
                bg.outlineSpotShadowColor = 0
                bg.outlineAmbientShadowColor = 0
            }
        } catch (_: Throwable) {
        }
        try {
            val bgN = XposedHelpers.getObjectField(row, "mBackgroundNormal") as? View
            if (bgN != null) {
                bgN.elevation = 0f
                bgN.outlineSpotShadowColor = 0
                bgN.outlineAmbientShadowColor = 0
            }
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
        dp(4f)
    }

    private fun readScrollRange(
        host: ViewGroup,
        ambient: Any,
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
        // 兜底：用卡片总高估算（息屏回来 host 方法可能暂时为 0）
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

    private fun collect(
        host: ViewGroup,
        rowCl: Class<*>,
        ambient: Any,
        trackedHun: Any?,
    ): List<Item> {
        val tops = ArrayList<View>()
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

        // 展开聚合：拆成子卡进全局堆叠；折叠 summary 仍作一张卡
        val flat = ArrayList<Item>(tops.size * 2)
        var idx = 0
        for (v in tops) {
            val st = viewState(v)!!
            val parentY = try {
                XposedHelpers.callMethod(st, "getYTranslation") as Float
            } catch (_: Throwable) {
                0f
            }
            if (isGroupExpandedLike(v)) {
                val children = attachedChildren(v)
                if (children.isNotEmpty()) {
                    for (c in children) {
                        if (c.visibility == View.GONE) continue
                        val cst = viewState(c) ?: continue
                        if (boolField(cst, "gone")) continue
                        val localY = try {
                            XposedHelpers.callMethod(cst, "getYTranslation") as Float
                        } catch (_: Throwable) {
                            0f
                        }
                        flat += Item(
                            view = c,
                            height = readHeight(c, cst),
                            nativeY = parentY + localY,
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
                height = readHeight(v, st),
                nativeY = parentY,
                index = idx++,
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
        if (callBool(row, "isHeadsUp") && callBool(row, "isPinned")) return true
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
        if (trackedHun != null && trackedHun === row) return true
        try {
            val tracked = XposedHelpers.callMethod(ambient, "getTrackedHeadsUpRow")
            if (tracked != null && tracked === row) return true
        } catch (_: Throwable) {
        }
        try {
            val pinnedStatus = XposedHelpers.getObjectField(row, "mPinnedStatus")
            if (pinnedStatus != null && callBool(pinnedStatus, "isPinned")) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun callBool(obj: Any, name: String): Boolean = try {
        XposedHelpers.callMethod(obj, name) as Boolean
    } catch (_: Throwable) {
        false
    }

    private fun callFloat(obj: Any, name: String, default: Float): Float = try {
        when (val v = XposedHelpers.callMethod(obj, name)) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            else -> default
        }
    } catch (_: Throwable) {
        default
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
            XposedHelpers.setBooleanField(st, "hidden", true)
            XposedHelpers.callMethod(st, "setAlpha", 0f)
        } catch (_: Throwable) {
        }
    }

    private fun readHeight(row: View, st: Any): Float {
        val h = try {
            XposedHelpers.getIntField(st, "height").toFloat()
        } catch (_: Throwable) {
            0f
        }
        if (h > 1f) return h
        return try {
            (XposedHelpers.callMethod(row, "getIntrinsicHeight") as Int).toFloat()
        } catch (_: Throwable) {
            dp(72f)
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

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, Resources.getSystem().displayMetrics
    )
}

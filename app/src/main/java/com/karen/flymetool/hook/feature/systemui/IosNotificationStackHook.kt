package com.karen.flymetool.hook.feature.systemui

import android.content.res.Configuration
import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * iOS 锁屏通知堆叠 — 对齐 showcase.html layoutFor。
 *
 * 折叠 p≈0：整组沉底，首卡全尺寸顶在 THRESH，下层 L*PEEK 错位 + 缩放下沉。
 * 展开 p→1：与原生列表 Y 插值；仅 overflow 继续堆叠。
 * 折叠态点击卡片：展开列表（不跳转）；顶层卡与横屏走系统默认（跳转）；有 pinned HUN 时整段不碰。
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
    /**
     * 堆叠视觉折叠中（含滑动过渡）。供 [NotificationCardBlurHook] 降低 Live 模糊半径，
     * 减轻多卡错位时的跨窗口模糊 GPU 负担。
     */
    @Volatile
    var stackBlurSoftCapActive: Boolean = false
        private set
    /** Flyme 控制中心展开进度（CenterController.mExpandedFraction），AOSP QS fraction 在 legacy 下恒 0 */
    @Volatile
    private var controlCenterFrac: Float = 0f
    private var centerControllerRef: WeakReference<Any>? = null
    private var centerControllerHooked = false
    /** 是否因控制中心把 NSSL/媒体层透明度压过；用于返回时强制恢复 */
    @Volatile
    private var dimmedForControlCenter = false
    private var stackHitTop: Float = 0f
    private var stackHitBottom: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var touchTracking: Boolean = false
    private var touchMoved: Boolean = false
    /** 折叠态堆叠顶层卡（全尺寸那张，items[firstOverflow]），点击它走默认跳转不展开 */
    private var stackTopRowRef: WeakReference<View>? = null

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
    private var lastStackSoftCap = false

    /** 已做过深度清阴影的 row（Weak），滑动热路径只做廉价 elevation/假阴影检查 */
    private val shadowDeepCleared =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private data class Item(
        val view: View,
        val st: Any,
        /** 堆叠区强制折叠高 */
        val collapsedH: Float,
        /** 系统布局高（列表区/间距与 nativeY 一致） */
        val systemH: Float,
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
                        // 横屏 / 顶层卡：不展开，放行系统默认（跳转）
                        if (isLandscapeClick(view)) return
                        if (isTopStackCard(view)) return
                        if (tryExpandStackFromClick()) {
                            param.result = null
                            Logger.d(TAG) { "折叠点击(row) → 展开列表" }
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
                        // 横屏不展开：不追踪，事件交还系统
                        if (isLandscapeClick(host)) {
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
                                        Logger.d(TAG) { "折叠整区点击 → 展开列表" }
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

        hookControlCenterExpansion(lpparam)
        hookMzBlurUtils(lpparam)
    }

    /**
     * 控制中心过渡期禁用卡片实时毛玻璃（BackgroundBlurDrawable）。
     *
     * Flyme 通知卡片背景是系统级实时模糊（MzBlurUtils.setBackgroundBlurDrawable，半径 180），
     * 跟随下拉手势更新 alpha（NSSL.updateLiveBlurAlpha → 各 row.updateLiveBlurAlpha）。
     * 其隐藏条件 shouldHideBlurForControlCenter() 要求 panelAlpha==255 且完全展开，
     * 过渡期间不隐藏 → 多张堆叠卡片的模糊块错位，在控制中心模糊背景上形成
     * 「先从卡片外缘展开、最后成整张矩形模糊」的卡片轮廓。
     * 这里在控制中心展开期间把 radius 与 alpha 都压 0（阴影清理管不到系统级模糊）。
     */
    private fun hookMzBlurUtils(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val blurCl = XposedHelpers.findClass(
                "com.flyme.systemui.utils.MzBlurUtils",
                lpparam.classLoader
            )
            // 创建路径：setBackgroundBlurDrawable(View,int,float,float,int,boolean,int,int,Function0,Consumer)
            val create = blurCl.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.parameterTypes.size == 10 &&
                    m.parameterTypes[0] == View::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Float::class.javaPrimitiveType &&
                    m.parameterTypes[3] == Float::class.javaPrimitiveType &&
                    m.parameterTypes[4] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[5] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[9] == Consumer::class.java
            }
            if (create != null) {
                XposedBridge.hookMethod(create, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (controlCenterFrac <= 0.02f) return
                        param.args[1] = 0 // blurRadius -> 0，无模糊（保留底色/圆角）
                    }
                })
                Logger.i(TAG, "已挂载 MzBlurUtils.create")
            }
            // 更新路径：setBackgroundBlurDrawableAlpha(View,int,boolean,Function0,Consumer)
            val update = blurCl.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.parameterTypes.size == 5 &&
                    m.parameterTypes[0] == View::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[4] == Consumer::class.java
            }
            if (update != null) {
                XposedBridge.hookMethod(update, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (controlCenterFrac <= 0.02f) return
                        param.args[1] = 0 // alpha -> 0，已创建的模糊立即隐藏
                    }
                })
                Logger.i(TAG, "已挂载 MzBlurUtils.alpha")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "MzBlurUtils hook 失败（卡片实时模糊无法在控制中心隐藏）", e)
        }
    }

    private fun hookControlCenterExpansion(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (centerControllerHooked) return
        try {
            val ccCl = XposedHelpers.findClass(
                "com.flyme.systemui.controlcenter.phone.CenterController",
                lpparam.classLoader
            )
            // setExpandedHeightInternal 每帧更新 mExpandedFraction
            XposedHelpers.findAndHookMethod(
                ccCl,
                "setExpandedHeightInternal",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        centerControllerRef = WeakReference(param.thisObject)
                        val frac = readCenterControllerFraction(param.thisObject)
                        val prev = controlCenterFrac
                        controlCenterFrac = frac
                        // 不等 resetViewStates：进度一变立刻压/恢复通知层，消除「全展开后残影一会」
                        if (frac > 0.02f || prev > 0.02f || dimmedForControlCenter) {
                            applyControlCenterHostDim(frac, forceUpdate = frac <= 0.02f && prev > 0.02f)
                        }
                    }
                }
            )
            centerControllerHooked = true
            Logger.i(TAG, "CenterController 展开进度 Hook 完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "CenterController Hook 失败（将回退 NSSL/Ambient QS）", e)
        }
    }

    private fun isStackCollapsed(): Boolean = lastPe < COLLAPSED_CLICK_PE

    private fun isTopStackCard(view: View): Boolean = stackTopRowRef?.get() === view

    /**
     * 点击场景的横屏判断：只用 resources。
     * 不能用 [isLandscape] 的宽高兜底 —— 竖屏下卡片宽 > 高同样成立，会误判横屏。
     */
    private fun isLandscapeClick(view: View): Boolean = try {
        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    } catch (_: Throwable) {
        false
    }

    private fun setStackSoftCap(active: Boolean, rows: List<View>) {
        stackBlurSoftCapActive = active
        if (active == lastStackSoftCap) return
        lastStackSoftCap = active
        if (rows.isEmpty()) return
        try {
            NotificationCardBlurHook.onStackSoftCapChanged(rows, active)
        } catch (_: Throwable) {
        }
    }

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
            setStackSoftCap(false, emptyList())
            return
        }

        // 控制中心 / QS 展开：必须先于堆叠布局判断。
        // Flyme 上 AmbientState.getQsExpansionFraction() 在 legacy 模式恒为 0，
        // 旧逻辑永远进不了退出堆叠分支，堆叠卡片会透到控制中心模糊背景上成「轮廓」。
        val qsFrac = readControlCenterOrQsExpansion(ambient, host)
        val trackedHun = readTrackedHun(ambient)
        val items = collect(host, rowCl, ambient, trackedHun)
        if (items.isEmpty()) {
            lastPe = 1f
            setStackSoftCap(false, emptyList())
            return
        }

        if (qsFrac > 0.02f) {
            lastPe = 1f
            setStackSoftCap(false, emptyList())
            // 只压宿主层透明度，不改各 row 的 ViewState.hidden/alpha，避免返回后「卡住要滑一下」
            applyControlCenterHostDim(qsFrac, forceUpdate = false)
            // 系统本帧已算出 native 列表态；不要再叠堆叠 scale，否则轮廓会透到模糊底
            // 也不要 restoreSystemList 把 alpha 拉回 1
            hideShelf(ambient)
            return
        }

        // 刚离开控制中心：先恢复宿主层，并清掉可能残留的 row 透明度
        if (dimmedForControlCenter) {
            applyControlCenterHostDim(0f, forceUpdate = true)
            restoreAfterControlCenter(items, host, ambient, trackedHun)
        }

        val stackY = readStackY(ambient)
        val innerH = readInnerHeight(ambient)
        if (innerH <= 0) return

        val contentBottom = stackY + innerH
        val landscape = isLandscape(host)
        // 横屏可视高度小：收紧底部留白，避免锚点过高把堆叠顶出屏幕
        val bottomPadPx = if (landscape) {
            landscapeBottomPadPx(innerH, items)
        } else {
            cachedBottomPad * density
        }
        val listTop = stackY + px8
        val step = pxStep
        val peek = pxPeek
        val firstCollapsedH = items.firstOrNull()?.collapsedH ?: px72
        val maxLayer = min(items.size - 1, MAX_L_VISIBLE.toInt())
        // 堆叠本体最小占用：首卡 + 下层 peek
        val minStackBody = firstCollapsedH + maxLayer * peek
        val threshRaw = contentBottom - bottomPadPx
        // 横屏再夹紧：锚点不得高于 listTop，且保证堆叠底不超出 contentBottom
        val thresh = if (landscape) {
            val maxThresh = (contentBottom - minStackBody).coerceAtLeast(listTop)
            threshRaw.coerceIn(listTop, maxThresh)
        } else {
            threshRaw
        }
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

        if (scrollRange <= 2f || (scrollRange > 2f && scrollY >= scrollRange - 4f)) {
            lastPe = 1f
            setStackSoftCap(false, items.map { it.view })
            restoreSystemList(items, ambient, trackedHun)
            hideShelf(ambient)
            return
        }

        // 折叠/过渡滑动中：多卡 Live blur 错位重，提示模糊 hook 用更低半径上限
        setStackSoftCap(pe < COLLAPSED_CLICK_PE, items.map { it.view })

        val sinkAll = cachedSinkAll
        val ease = pe * (2f - pe)
        val collapsedTop = if (sinkAll) thresh else listTop
        // winTop 保留与 showcase 一致（后续 et 用 nativeY lerp，不直接用 winTop）
        @Suppress("UNUSED_VARIABLE")
        val winTop = lerp(collapsedTop, listTop, ease)

        // 横屏：媒体若按 thresh 贴顶会越出上沿时，下推通知锚点给媒体留位，
        // 避免把媒体夹到 listTop 后底边盖住通知（竖屏空间够，无需预留）。
        val media = findMediaContainer(host)
        val mediaH = media?.let { readMediaHeight(it, viewState(it) ?: return@let 0f) } ?: 0f
        val stackTopForMedia = if (landscape && media != null && mediaH > 1f) {
            val idealAbove = thresh - mediaH - pad
            if (idealAbove < listTop) {
                listTop + mediaH + pad
            } else {
                thresh
            }
        } else {
            thresh
        }

        val firstOverflow = if (sinkAll) {
            0
        } else {
            items.indexOfFirst { it.nativeY >= stackTopForMedia - px8 }.coerceAtLeast(0)
        }
        // 堆叠起点：横屏可能已含媒体预留
        val stackAnchor = if (sinkAll || firstOverflow <= 0) {
            stackTopForMedia
        } else {
            val lastFull = items[firstOverflow - 1]
            max(stackTopForMedia, lastFull.nativeY + lastFull.systemH + px6)
        }

        // 顶层卡 = 堆叠起点那张（sinkAll 时即 items[0]），点击它走默认跳转不展开
        stackTopRowRef = items.getOrNull(firstOverflow)?.let { WeakReference(it.view) }

        // 媒体始终贴「通知堆叠顶」上方；横屏用 stackTopForMedia，随 pe 跟滚不压通知
        if (media != null) {
            placeMediaAboveStack(
                media, pe, stackTopForMedia, pad, stick = sinkAll,
            )
        }

        // 命中区与实际锚点一致（横屏含媒体预留下推）
        stackHitTop = stackTopForMedia
        stackHitBottom = stackTopForMedia + maxLayer * peek + firstCollapsedH

        val touchedSummaries = ArrayList<View>(2)

        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, item.st, ambient, trackedHun)) continue
            val st = item.st
            val colH = max(item.collapsedH, 1f)
            val sysH = max(item.systemH, 1f)
            val i = item.index
            val childInGroup = item.parentY != null
            val foldedSummary = item.foldedSummary

            val ty: Float
            var scale: Float
            val alpha: Float
            // 高度随 pe 与 Y 同步 ease：colH↔sysH，无分区硬切
            val hBlend = lerp(colH, sysH, ease)
            val h: Float

            // 溢出/peek 相对 stackTopForMedia（横屏含媒体预留）
            val overflowBase = stackTopForMedia
            if (!sinkAll && item.nativeY < overflowBase - px8) {
                ty = item.nativeY
                scale = 1f
                alpha = 1f
                h = sysH
            } else {
                val relativeI = if (sinkAll) i else (i - firstOverflow).coerceAtLeast(0)
                val collapsedEt = stackAnchor + relativeI * step
                val et = lerp(collapsedEt, item.nativeY, pe)
                val over = et - overflowBase
                h = hBlend

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
                        setInt(st, "height", h.toInt().coerceAtLeast(1))
                        writeY(st, item, stackAnchor + MAX_L_VISIBLE * peek)
                        continue
                    }
                    ty = overflowBase + L * peek
                    scale = max(MIN_SCALE, 1f - L * SCALE_PER_L)
                    // 约 3 层可见：L≈0/1/2 → ~1/0.68/0.24，L→2.4 渐隐
                    alpha = (1f - (L - 0.25f) * 0.44f).coerceIn(0f, 1f)
                }
            }

            if (foldedSummary) {
                scale = 1f
            }

            val yAbs = if (foldedSummary) ty else ty - h * (1f - scale) * 0.5f
            val z = Z_BASE - i * Z_STEP

            writeY(st, item, yAbs)
            setInt(st, "height", h.toInt().coerceAtLeast(1))
            setScale(st, scale)
            setAlpha(st, alpha)
            setZ(st, z)
            setBool(st, "hidden", false)
            setBool(st, "inShelf", false)
            setInt(st, "clipBottomAmount", 0)
            setInt(st, "clipTopAmount", 0)

            // 每帧对所有堆叠卡片清阴影：缩小/错位后 outline 与阴影不随 scale 走，
            // 控制中心模糊根容器时会把残留投影/假阴影放大成「卡片外廓」。
            clearShadowHard(item.view)
            val sum = item.summary
            if (sum != null && sum !in touchedSummaries) touchedSummaries += sum
        }

        for (summary in touchedSummaries) {
            flattenExpandedSummaryShell(summary, items, ambient, trackedHun)
        }

        hideShelf(ambient)
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
            val h = max(c.systemH, 1f)
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
        clearShadowHard(summary)
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
            // 离开堆叠路径：恢复系统高度，避免列表区仍占折叠高
            setInt(st, "height", max(item.systemH, 1f).toInt())
            if (boolField(st, "inShelf")) setBool(st, "inShelf", false)
            setBool(st, "hidden", false)
            try {
                val a = XposedHelpers.callMethod(st, "getAlpha") as Float
                if (a < 0.99f) setAlpha(st, 1f)
            } catch (_: Throwable) {
                setAlpha(st, 1f)
            }
            setZ(st, 0f)
            try {
                if (item.view.translationZ != 0f) item.view.translationZ = 0f
            } catch (_: Throwable) {
            }
            // 若控制中心路径强制 INVISIBLE，回通知栏时恢复
            try {
                if (item.view.visibility == View.INVISIBLE) {
                    item.view.visibility = View.VISIBLE
                }
                if (item.view.alpha < 0.99f) item.view.alpha = 1f
            } catch (_: Throwable) {
            }
            setInt(st, "clipBottomAmount", 0)
            setInt(st, "clipTopAmount", 0)
            clearShadowHard(item.view)
        }
        // 媒体若被控制中心路径隐藏，也恢复 View 可见性（ViewState 交给系统）
        lastHostRef?.get()?.let { host ->
            findMediaContainer(host)?.let { media ->
                try {
                    if (media.visibility == View.INVISIBLE) media.visibility = View.VISIBLE
                    if (media.alpha < 0.99f) media.alpha = 1f
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * 读取「是否正在/已经展开控制中心或 QS」。
     * 优先级：
     * 1) Flyme CenterController.mExpandedFraction（hook 缓存）
     * 2) NSSL.mQsExpansionFraction（AOSP 写在 View 上，不经 Ambient legacy 短路）
     * 3) AmbientState.getQsExpansionFraction（Scene 模式才有值）
     * 4) CentralSurfaces.isControlCenterExpanded
     */
    private fun readCenterControllerFraction(cc: Any?): Float {
        if (cc == null) return 0f
        return try {
            XposedHelpers.getFloatField(cc, "mExpandedFraction").coerceIn(0f, 1f)
        } catch (_: Throwable) {
            try {
                if (XposedHelpers.callMethod(cc, "isExpanded") as? Boolean == true) 1f else 0f
            } catch (_: Throwable) {
                0f
            }
        }
    }

    private fun readControlCenterOrQsExpansion(ambient: Any, host: ViewGroup): Float {
        // 每帧直读 CenterController，避免折叠路径未走 setExpandedHeightInternal 导致缓存脏
        val liveCc = readCenterControllerFraction(centerControllerRef?.get())
        if (liveCc > 0.001f || centerControllerRef?.get() != null) {
            controlCenterFrac = liveCc
            if (liveCc > 0.001f) return liveCc
        } else if (controlCenterFrac > 0.001f) {
            return controlCenterFrac
        }

        try {
            val f = XposedHelpers.getFloatField(host, "mQsExpansionFraction")
            if (f > 0.001f) return f.coerceIn(0f, 1f)
        } catch (_: Throwable) {
        }

        try {
            val v = XposedHelpers.callMethod(ambient, "getQsExpansionFraction")
            when (v) {
                is Float -> if (v > 0.001f) return v.coerceIn(0f, 1f)
                is Double -> if (v > 0.001) return v.toFloat().coerceIn(0f, 1f)
            }
        } catch (_: Throwable) {
        }

        try {
            val v = XposedHelpers.callMethod(ambient, "getFilterQsExpansionFraction")
            when (v) {
                is Float -> if (v > 0.001f) return v.coerceIn(0f, 1f)
                is Double -> if (v > 0.001) return v.toFloat().coerceIn(0f, 1f)
            }
        } catch (_: Throwable) {
        }

        if (isControlCenterExpandedFlag()) return 1f
        return 0f
    }

    private fun isControlCenterExpandedFlag(): Boolean {
        return try {
            val cl = hostClassLoader()
            val dep = XposedHelpers.findClass("com.android.systemui.Dependency", cl)
            val utilCl = XposedHelpers.findClass(
                "com.flyme.notification.utils.CentralSurfaceUtil",
                cl
            )
            val util = XposedHelpers.callStaticMethod(dep, "get", utilCl)
            val cs = XposedHelpers.callMethod(util, "getCentralSurfacesImpl")
            XposedHelpers.callMethod(cs, "isControlCenterExpanded") as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun hostClassLoader(): ClassLoader {
        return loadParam?.classLoader
            ?: lastHostRef?.get()?.javaClass?.classLoader
            ?: IosNotificationStackHook::class.java.classLoader!!
    }

    /**
     * 进控制中心时强制隐掉堆叠通知。
     * 不能用 restoreSystemList：那会把 alpha 拉回 1，轮廓更容易透到模糊背景上。
     * frac 越大越彻底隐藏；>0.12 直接 hidden。
     */
    /**
     * 控制中心展开时压暗/隐藏整个通知栈宿主，而不是改每条 row 的 ViewState。
     * 原因：
     * - 改 row.hidden/alpha 后，返回通知栏时系统不一定立刻 resetViewStates，表现成「通知没了，滑一下才回来」
     * - 宿主 alpha=0 可立刻去掉模糊底上的堆叠轮廓，且恢复时一次设回 1 即可
     */
    private fun applyControlCenterHostDim(frac: Float, forceUpdate: Boolean) {
        val host = lastHostRef?.get() ?: return
        val hide = frac > 0.02f
        // 0.02→0.10 快速淡出，之后保持全隐，避免全展开后还闪一会轮廓
        val alpha = when {
            !hide -> 1f
            frac >= 0.10f -> 0f
            else -> (1f - (frac - 0.02f) / 0.08f).coerceIn(0f, 1f)
        }
        try {
            if (host.alpha != alpha) host.alpha = alpha
            // 不改 visibility，避免系统面板状态机不同步
        } catch (_: Throwable) {
        }
        // 媒体容器有时不在同一 alpha 链路，一并压
        try {
            findMediaContainer(host)?.let { media ->
                if (media.alpha != alpha) media.alpha = alpha
            }
        } catch (_: Throwable) {
        }

        val was = dimmedForControlCenter
        dimmedForControlCenter = hide || alpha < 0.999f
        if (forceUpdate || (was && !dimmedForControlCenter)) {
            requestHostChildrenUpdate(host)
        }
    }

    private fun requestHostChildrenUpdate(host: ViewGroup) {
        for (name in arrayOf("requestChildrenUpdate", "updateChildren", "requestLayout")) {
            try {
                XposedHelpers.callMethod(host, name)
                return
            } catch (_: Throwable) {
            }
        }
        try {
            host.invalidate()
        } catch (_: Throwable) {
        }
    }

    /**
     * 离开控制中心后，确保 row 的 ViewState/View 层不被上一版 hide 逻辑残留影响。
     * （兼容：若旧逻辑写过 hidden/alpha，这里兜底清掉）
     */
    private fun restoreAfterControlCenter(
        items: List<Item>,
        host: ViewGroup,
        ambient: Any,
        trackedHun: Any?,
    ) {
        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, item.st, ambient, trackedHun)) continue
            val st = item.st
            try {
                if (boolField(st, "hidden")) setBool(st, "hidden", false)
            } catch (_: Throwable) {
            }
            try {
                setAlpha(st, 1f)
            } catch (_: Throwable) {
            }
            try {
                if (item.view.alpha < 0.99f) item.view.alpha = 1f
                if (item.view.visibility != View.VISIBLE) item.view.visibility = View.VISIBLE
            } catch (_: Throwable) {
            }
        }
        findMediaContainer(host)?.let { media ->
            val st = viewState(media)
            if (st != null) {
                setBool(st, "hidden", false)
                setAlpha(st, 1f)
            }
            try {
                if (media.alpha < 0.99f) media.alpha = 1f
                if (media.visibility != View.VISIBLE) media.visibility = View.VISIBLE
            } catch (_: Throwable) {
            }
        }
        if (host.alpha < 0.99f) {
            try {
                host.alpha = 1f
            } catch (_: Throwable) {
            }
        }
        requestHostChildrenUpdate(host)
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

    /**
     * 堆叠模式下清阴影。深度清理（反射取字段/outline）每张卡只做一次；
     * 滑动热路径仅保持 elevation=0 与假阴影 GONE，避免每帧多段反射拖垮滚动。
     */
    private fun clearShadowHard(row: View) {
        try {
            if (row.elevation != 0f) row.elevation = 0f
        } catch (_: Throwable) {
        }

        if (row in shadowDeepCleared) {
            // 系统可能每帧重新打开假阴影：只做廉价 visibility 检查
            try {
                val fs = XposedHelpers.getObjectField(row, "mFakeShadow") as? View
                if (fs != null && fs.visibility != View.GONE) {
                    fs.visibility = View.GONE
                }
            } catch (_: Throwable) {
            }
            return
        }

        // —— 首次深度清理 ——
        try {
            val fs = XposedHelpers.getObjectField(row, "mFakeShadow") as? View
            if (fs != null) {
                if (fs.visibility != View.GONE) fs.visibility = View.GONE
                try {
                    val inner = XposedHelpers.getObjectField(fs, "mFakeShadow") as? View
                    if (inner != null && inner.visibility != View.GONE) {
                        inner.visibility = View.GONE
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(row, "setFakeShadowIntensity", 0f, 0f, 0, 0)
        } catch (_: Throwable) {
        }
        killOutlineShadow(row)
        for (bgField in arrayOf("mBackgroundFlyme", "mBackgroundNormal")) {
            try {
                val bg = XposedHelpers.getObjectField(row, bgField) as? View
                if (bg != null) killOutlineShadow(bg)
            } catch (_: Throwable) {
            }
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
        shadowDeepCleared.add(row)
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
        for (item in items) total += max(item.systemH, 1f) + pad
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
     * 媒体相对堆叠顶界 stackTop（帧间稳定；resetViewStates 后读到的 Y 即算法 native）：
     * - stick：折叠贴顶界上方，ease → native
     * - !stick：min(native, 放宽上界)，只防重叠
     * 横屏由调用方下推 stackTop 预留媒体高度，此处不再抬 Y 压通知。
     */
    private fun placeMediaAboveStack(
        media: View,
        pe: Float,
        stackTop: Float,
        pad: Float,
        stick: Boolean,
    ) {
        val st = viewState(media) ?: return
        val h = readMediaHeight(media, st)
        if (h <= 1f) return
        val nativeY = getY(st)
        val above = stackTop - h - pad
        val ease = pe * (2f - pe)
        val y = if (stick) {
            lerp(above, nativeY, ease)
        } else {
            min(nativeY, lerp(above, max(nativeY, above), ease))
        }
        setY(st, y)
        setInt(st, "height", h.toInt().coerceAtLeast(1))
        setScale(st, 1f)
        setAlpha(st, 1f)
        val zMedia = Z_BASE + Z_STEP * 2f
        setZ(st, zMedia)
        setBool(st, "hidden", false)
        setBool(st, "inShelf", false)
        setInt(st, "clipBottomAmount", 0)
        setInt(st, "clipTopAmount", 0)
        clearElevationIfNeeded(media)
        try {
            if (media.translationZ != zMedia) media.translationZ = zMedia
        } catch (_: Throwable) {
        }
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
                            collapsedH = readCollapsedHeight(c, cst),
                            systemH = readSystemHeight(c, cst),
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
                collapsedH = readCollapsedHeight(v, st),
                systemH = readSystemHeight(v, st),
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
     * 堆叠 peek 区用的高度：未手动展开 → 折叠高；用户展开 → 系统高。
     * 列表区仍写 systemH，与 nativeY 间距一致。
     */
    private fun readCollapsedHeight(row: View, st: Any): Float {
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

    private fun isLandscape(host: View): Boolean {
        return try {
            host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (_: Throwable) {
            host.width > host.height && host.height > 0
        }
    }

    /**
     * 横屏底部留白：不超过配置值，且按可视高度与首卡高度自适应，
     * 避免固定 180dp 把折叠锚点顶出屏幕上沿。
     */
    private fun landscapeBottomPadPx(innerH: Int, items: List<Item>): Float {
        val configured = cachedBottomPad * density
        val firstH = items.firstOrNull()?.collapsedH ?: px72
        // 预留堆叠可见区（首卡 + 约 2 层 peek），剩余才给底边
        val stackNeed = firstH + pxPeek * 2f + px8
        val room = (innerH - stackNeed).coerceAtLeast(0f)
        // 底边最多占可视高度 22%，且不低于 48dp 以免贴底难看
        val maxByHeight = (innerH * 0.22f).coerceAtLeast(px48)
        return min(configured, min(room, maxByHeight))
    }

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

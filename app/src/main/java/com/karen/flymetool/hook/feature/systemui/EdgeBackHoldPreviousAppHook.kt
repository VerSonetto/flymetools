package com.karen.flymetool.hook.feature.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 侧滑长按回上个应用（针对 Flyme EdgeBackView）。
 *
 * Flyme 12 流程（见 EdgeBackView）：
 * DOWN → resetOnDown → MOVE/whetherTriggerBack(mSwipeThreshold/mMinDeltaForSwitch)
 * → UP：mTriggerBack ? triggerBack() : cancelBack()
 * triggerBack() 内先调 mBackCallback.triggerBack()（真正发返回）。
 *
 * 幅度：mSwipeThreshold / mMinDeltaForSwitch 均为 final（系统约 16dp / 32dp），
 * 直接改字段不可靠（写入失败或 ART 内联），因此在 whetherTriggerBack 之后按配置重算 mTriggerBack。
 *
 * 策略：按下即计时；松手时幅度够且时长≥holdMs → 切上个应用，并走 cancelBack 收起指示条
 * （避免发 KEYCODE_BACK）。幅度与时长都满足时在鼓包内画上个应用图标。
 */
object EdgeBackHoldPreviousAppHook : FeatureHook {

    private const val TAG = "EdgeBackHoldPreviousApp"
    private const val FEATURE_KEY = "edge_back_hold_previous_app"
    private const val DEFAULT_HOLD_MS = 1000
    private const val DEFAULT_THRESHOLD_DP = 36
    private const val PREFS_TTL_MS = 800L
    private const val EDGE_BACK_VIEW =
        "com.flyme.systemui.navigationbar.gestural.EdgeBackView"
    private const val HANDLER =
        "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
    private const val ICON_SIZE_DP_DEFAULT = 20
    private const val ICON_SIZE_DP_MIN = 12
    private const val ICON_SIZE_DP_MAX = 36
    /** Flyme 构造里写死的回滑取消幅度基准 */
    private const val SYSTEM_MIN_DELTA_DP = 32f
    /** 滑块区间（与 UI 一致，收窄） */
    private const val THRESHOLD_UI_MIN = 24
    private const val THRESHOLD_UI_MAX = 56
    /** 映射后的实际触发距离两端，拉开相邻档手感 */
    private const val THRESHOLD_EFF_MIN = 22f
    private const val THRESHOLD_EFF_MAX = 70f
    /** ActivityTaskManager.getRecentTasks flags：忽略不可用任务 */
    private const val RECENT_IGNORE_UNAVAILABLE = 2

    private val SKIP_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    @Volatile
    private var holdMs: Long = DEFAULT_HOLD_MS.toLong()

    @Volatile
    private var thresholdDp: Int = DEFAULT_THRESHOLD_DP

    @Volatile
    private var iconSizeDp: Int = ICON_SIZE_DP_DEFAULT
    private var prefsCachedAt = 0L
    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    @Volatile
    private var armedAtElapsed: Long = 0L

    @Volatile
    private var appContext: Context? = null

    private var classLoader: ClassLoader? = null
    private val callbackHooked = AtomicBoolean(false)

    /** Zygote/类加载时 MainLooper 可能为 null，必须懒创建 */
    private val mainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private var edgeViewRef: WeakReference<View>? = null

    @Volatile
    private var previewIcon: Drawable? = null

    @Volatile
    private var previewTaskId: Int = -1

    @Volatile
    private var previewPkg: String? = null

    @Volatile
    private var showPreview: Boolean = false

    /** triggerBack 内主动调用 cancelBack 收起指示条时置位，避免 afterHook 误清 */
    @Volatile
    private var switchingApp: Boolean = false

    private val holdReadyRunnable = Runnable {
        if (armedAtElapsed <= 0L) return@Runnable
        val held = SystemClock.elapsedRealtime() - armedAtElapsed
        if (held < holdMs) return@Runnable
        val view = edgeViewRef?.get()
        // 必须幅度够（mTriggerBack）才展示预览，与松手判定一致
        if (view == null || !isTriggerBack(view)) return@Runnable
        preparePreviewIcon(view.context)
        showPreview = previewIcon != null && previewTaskId > 0
        Logger.d(TAG) {
            "长按就绪 show=$showPreview task=$previewTaskId pkg=$previewPkg"
        }
        if (showPreview) {
            view.invalidate()
            pulseInvalidate()
        }
    }

    private fun resolveContext(): Context? {
        edgeViewRef?.get()?.context?.let {
            appContext = it.applicationContext ?: it
            return it
        }
        if (appContext != null) return appContext
        return try {
            val app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                "currentApplication"
            ) as? Context
            appContext = app
            app
        } catch (_: Throwable) {
            null
        }
    }

    private val invalidatePulse = object : Runnable {
        override fun run() {
            if (!showPreview || armedAtElapsed <= 0L) return
            edgeViewRef?.get()?.invalidate()
            try {
                mainHandler.postDelayed(this, 32L)
            } catch (_: Throwable) {
            }
        }
    }

    private fun pulseInvalidate() {
        try {
            mainHandler.removeCallbacks(invalidatePulse)
            mainHandler.post(invalidatePulse)
        } catch (_: Throwable) {
        }
    }

    private fun refreshPrefsIfNeeded() {
        val now = SystemClock.uptimeMillis()
        if (now - prefsCachedAt < PREFS_TTL_MS) return
        prefsCachedAt = now
        val lp = loadParam
        holdMs = if (lp != null) {
            XposedPrefs.getFeatureValue(lp, prefsPackage, FEATURE_KEY, DEFAULT_HOLD_MS)
                .coerceIn(300, 2000)
        } else {
            DEFAULT_HOLD_MS
        }.toLong()
        thresholdDp = if (lp != null) {
            XposedPrefs.getFeatureExtraValue(
                lp, prefsPackage, FEATURE_KEY, "threshold_dp", DEFAULT_THRESHOLD_DP
            ).coerceIn(THRESHOLD_UI_MIN, THRESHOLD_UI_MAX)
        } else {
            DEFAULT_THRESHOLD_DP
        }
        iconSizeDp = if (lp != null) {
            XposedPrefs.getFeatureExtraValue(
                lp, prefsPackage, FEATURE_KEY, "icon_size_dp", ICON_SIZE_DP_DEFAULT
            ).coerceIn(ICON_SIZE_DP_MIN, ICON_SIZE_DP_MAX)
        } else {
            ICON_SIZE_DP_DEFAULT
        }
    }

    private fun postHoldReady() {
        try {
            refreshPrefsIfNeeded()
            mainHandler.removeCallbacks(holdReadyRunnable)
            mainHandler.postDelayed(holdReadyRunnable, holdMs)
        } catch (t: Throwable) {
            Logger.e(TAG, "延迟执行长按就绪失败", t)
        }
    }

    private fun cancelHoldReady() {
        try {
            mainHandler.removeCallbacks(holdReadyRunnable)
            mainHandler.removeCallbacks(invalidatePulse)
        } catch (_: Throwable) {
        }
    }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return
        classLoader = lpparam.classLoader
        prefsPackage = packageName
        loadParam = lpparam
        refreshPrefsIfNeeded()

        try {
            appContext = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentApplication"
            ) as? Context
        } catch (_: Throwable) {
        }

        // Flyme 12 默认插件即 EdgeBackView（EdgeBackGestureHandler.onPluginDisconnected）
        hookFlymeEdgeBackView(lpparam)
        // 兜底：若插件路径未拦住，再拦 Handler 的 BackCallback.triggerBack
        hookCallbackFromHandler(lpparam)

        Logger.i(
            TAG,
            "已加载 hold=${holdMs}ms thresholdUi=${thresholdDp}dp " +
                "eff=${effectiveThresholdDp(thresholdDp).toInt()}dp icon=${iconSizeDp}dp"
        )
    }

    private fun hookFlymeEdgeBackView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewCl = XposedHelpers.findClass(EDGE_BACK_VIEW, lpparam.classLoader)

            for (ctor in viewCl.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            edgeViewRef = WeakReference(param.thisObject as View)
                        }
                    })
                } catch (_: Throwable) {
                }
            }

            // DOWN：resetOnDown 后开表
            XposedHelpers.findAndHookMethod(
                viewCl,
                "resetOnDown",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View
                        if (view != null) {
                            edgeViewRef = WeakReference(view)
                            appContext = view.context.applicationContext ?: view.context
                        }
                        startGestureTimer()
                    }
                }
            )

            // MOVE：用配置幅度覆盖 mTriggerBack；幅度够 + 时长够 → 显示图标
            XposedHelpers.findAndHookMethod(
                viewCl,
                "whetherTriggerBack",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as View
                            val event = param.args[0] as? MotionEvent ?: return
                            edgeViewRef = WeakReference(view)
                            appContext = view.context.applicationContext ?: view.context
                            if (armedAtElapsed == 0L) {
                                startGestureTimer()
                            }
                            refreshPrefsIfNeeded()
                            // 系统 whetherTriggerBack 仍按 final 的 ~16dp 判定；此处按配置重算
                            val trigger = overrideTriggerByConfig(view, event)
                            val held = SystemClock.elapsedRealtime() - armedAtElapsed
                            if (trigger && held >= holdMs) {
                                if (!showPreview) {
                                    preparePreviewIcon(view.context)
                                    showPreview = previewIcon != null && previewTaskId > 0
                                    if (showPreview) {
                                        view.invalidate()
                                        pulseInvalidate()
                                    }
                                }
                            } else if (!trigger && showPreview) {
                                showPreview = false
                                previewIcon = null
                                view.invalidate()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                viewCl,
                "onDraw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!showPreview) return
                        val icon = previewIcon ?: return
                        val view = param.thisObject as View
                        val canvas = param.args[0] as? Canvas ?: return
                        drawPreviewIcon(view, canvas, icon)
                    }
                }
            )

            // UP 且 mTriggerBack：拦截系统返回，满足长按则切应用并走 cancelBack 收起
            XposedHelpers.findAndHookMethod(
                viewCl,
                "triggerBack",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshPrefsIfNeeded()
                        val view = param.thisObject as View
                        val armed = armedAtElapsed
                        val held = if (armed > 0L) SystemClock.elapsedRealtime() - armed else 0L
                        val taskId = previewTaskId
                        val shouldSwitch = held >= holdMs
                        if (!shouldSwitch) {
                            clearArmedState()
                            return
                        }
                        val ok = if (taskId > 0) {
                            startFromRecents(taskId)
                        } else {
                            switchToPreviousApp()
                        }
                        clearArmedState()
                        if (!ok) return
                        // 走 View.cancelBack：回调 cancelBack + 收起，不发返回键
                        switchingApp = true
                        try {
                            XposedHelpers.callMethod(view, "cancelBack")
                        } catch (_: Throwable) {
                            try {
                                val cb = XposedHelpers.getObjectField(view, "mBackCallback")
                                XposedHelpers.callMethod(cb, "cancelBack")
                            } catch (_: Throwable) {
                            }
                            try {
                                XposedHelpers.callMethod(view, "setVisibility", 8)
                            } catch (_: Throwable) {
                            }
                        } finally {
                            switchingApp = false
                        }
                        param.result = null
                        vibrateConfirm()
                        Logger.i(TAG, "EdgeBackView 长按 ${held}ms → 切换到上一个应用")
                    }
                }
            )

            // 取消 / dismiss：清计时与预览，避免松手后仍弹出图标
            // switchingApp：triggerBack 内主动走 cancelBack 收起时跳过，避免竞态
            for (name in arrayOf("cancelBack", "dismiss")) {
                try {
                    XposedHelpers.findAndHookMethod(
                        viewCl,
                        name,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (switchingApp) return
                                if (armedAtElapsed > 0L || showPreview) {
                                    clearArmedState()
                                }
                            }
                        }
                    )
                } catch (t: Throwable) {
                    Logger.e(TAG, "挂载 $name 失败", t)
                }
            }

            Logger.i(TAG, "已挂载 EdgeBackView")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 EdgeBackView 失败", e)
        }
    }

    private fun isTriggerBack(view: View): Boolean {
        return try {
            XposedHelpers.getBooleanField(view, "mTriggerBack")
        } catch (_: Throwable) {
            false
        }
    }

    /** 手势开始：开表（与幅度并行） */
    private fun startGestureTimer() {
        armedAtElapsed = SystemClock.elapsedRealtime()
        showPreview = false
        previewIcon = null
        previewTaskId = -1
        previewPkg = null
        cancelHoldReady()
        postHoldReady()
    }

    private fun clearArmedState() {
        armedAtElapsed = 0L
        showPreview = false
        previewIcon = null
        previewTaskId = -1
        previewPkg = null
        cancelHoldReady()
        try {
            edgeViewRef?.get()?.invalidate()
        } catch (_: Throwable) {
        }
    }

    /**
     * 画在灰色指示条鼓包内。
     * computePath：鼓包尖端偏移 ≈ interpolation(mScale) * mMaxHeight。
     */
    private fun drawPreviewIcon(view: View, canvas: Canvas, icon: Drawable) {
        try {
            val density = view.resources.displayMetrics.density
            refreshPrefsIfNeeded()
            val size = (iconSizeDp * density).toInt().coerceAtLeast(1)
            val centerY = try {
                XposedHelpers.getFloatField(view, "mCenterY")
            } catch (_: Throwable) {
                view.height / 2f
            }
            val isLeft = try {
                XposedHelpers.getBooleanField(view, "mIsLeftPanel")
            } catch (_: Throwable) {
                true
            }
            val maxH = try {
                XposedHelpers.getFloatField(view, "mMaxHeight")
            } catch (_: Throwable) {
                36f * density
            }
            val scale = try {
                XposedHelpers.getFloatField(view, "mScale").coerceIn(0.15f, 1f)
            } catch (_: Throwable) {
                0.5f
            }
            val bulge = (scale * maxH).coerceAtLeast(size.toFloat())
            val cx = if (isLeft) {
                (bulge * 0.45f).coerceAtLeast(size / 2f + 4f * density)
            } else {
                val w = try {
                    val lp = XposedHelpers.getObjectField(view, "mLayoutParams")
                    XposedHelpers.getIntField(lp, "width").toFloat()
                } catch (_: Throwable) {
                    view.width.toFloat()
                }
                w - (bulge * 0.45f).coerceAtLeast(size / 2f + 4f * density)
            }
            val left = (cx - size / 2f).toInt()
            val top = (centerY - size / 2f).toInt()
            val d = icon.mutate()
            d.alpha = 255
            d.setBounds(left, top, left + size, top + size)
            val save = canvas.save()
            d.draw(canvas)
            canvas.restoreToCount(save)
        } catch (t: Throwable) {
            Logger.e(TAG, "绘制预览图标失败", t)
        }
    }

    /** 预加载上一任务图标与 taskId */
    private fun preparePreviewIcon(hint: Context?) {
        try {
            val ctx = hint ?: resolveContext() ?: run {
                Logger.d(TAG) { "preparePreview: 无 context" }
                return
            }
            appContext = ctx.applicationContext ?: ctx
            val target = findPreviousTask() ?: run {
                Logger.d(TAG) { "preparePreview: 无目标任务" }
                return
            }
            previewTaskId = getTaskId(target)
            if (previewTaskId <= 0) return
            val pkg = resolveTaskPackage(target)
            previewPkg = pkg
            if (pkg.isNullOrEmpty()) {
                Logger.d(TAG) { "preparePreview: 任务 $previewTaskId 无包名" }
                return
            }
            previewIcon = try {
                val pm = ctx.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationIcon(ai).mutate()
            } catch (t: Throwable) {
                Logger.e(TAG, "获取应用图标失败", t, "pkg" to pkg)
                null
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "preparePreviewIcon 失败", t)
        }
    }

    private fun resolveTaskPackage(task: Any): String? {
        try {
            val intent = XposedHelpers.getObjectField(task, "baseIntent") as? Intent
            val pkg = intent?.component?.packageName ?: intent?.`package`
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {
        }
        for (field in arrayOf("topActivity", "realActivity", "baseActivity", "origActivity")) {
            try {
                val comp = XposedHelpers.getObjectField(task, field) as? ComponentName
                val pkg = comp?.packageName
                if (!pkg.isNullOrEmpty()) return pkg
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * 兜底挂载 EdgeBackGestureHandler.mBackCallback.triggerBack。
     * Flyme 主路径已在 EdgeBackView.triggerBack 拦截；此处防插件替换或漏挂。
     */
    private fun hookCallbackFromHandler(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val handlerCl = XposedHelpers.findClass(HANDLER, lpparam.classLoader)
            val afterCtor = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!callbackHooked.compareAndSet(false, true)) return
                    try {
                        val cb = XposedHelpers.getObjectField(param.thisObject, "mBackCallback")
                            ?: return
                        val m = cb.javaClass.getDeclaredMethod("triggerBack")
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) {
                                refreshPrefsIfNeeded()
                                val armed = armedAtElapsed
                                val held =
                                    if (armed > 0L) SystemClock.elapsedRealtime() - armed else 0L
                                val taskId = previewTaskId
                                if (held < holdMs) {
                                    clearArmedState()
                                    return
                                }
                                val ok = if (taskId > 0) {
                                    startFromRecents(taskId)
                                } else {
                                    switchToPreviousApp()
                                }
                                clearArmedState()
                                if (!ok) return
                                p.result = null
                                vibrateConfirm()
                                Logger.i(TAG, "callback 长按 ${held}ms → 切换到上一个应用")
                            }
                        })
                        Logger.i(TAG, "已挂载 callback ${cb.javaClass.name}")
                    } catch (t: Throwable) {
                        callbackHooked.set(false)
                        Logger.e(TAG, "挂载 callback 失败", t)
                    }
                }
            }
            for (ctor in handlerCl.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(ctor, afterCtor)
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "hookCallbackFromHandler 失败", e)
        }
    }

    /**
     * 滑块档位 → 实际触发 dp。
     * UI 24..56 线性映射到 22..70，单位滑块位移对应更大实际距离，手感差更明显。
     */
    private fun effectiveThresholdDp(uiDp: Int): Float {
        val t = ((uiDp - THRESHOLD_UI_MIN).toFloat() /
            (THRESHOLD_UI_MAX - THRESHOLD_UI_MIN)).coerceIn(0f, 1f)
        return THRESHOLD_EFF_MIN + t * (THRESHOLD_EFF_MAX - THRESHOLD_EFF_MIN)
    }

    /**
     * 按配置重算 mTriggerBack（镜像 Flyme whetherTriggerBack 算法，换用可配置阈值）。
     *
     * 原算法：
     * 1) |x - mStartX| > mSwipeThreshold → mDragSlopPassed=true, mTriggerBack=true
     * 2) |mTotalTouchDelta| > mMinDeltaForSwitch → mTriggerBack = (delta > 0)  // 回滑取消
     *
     * mSwipeThreshold/mMinDeltaForSwitch 为 final，不能依赖改字段；只覆写非 final 的 mTriggerBack。
     * 位移仍用系统已更新的 mStartX / mTotalTouchDelta（与 getX 一致）。
     */
    private fun overrideTriggerByConfig(view: View, event: MotionEvent): Boolean {
        refreshPrefsIfNeeded()
        val density = view.resources.displayMetrics.density
        val effDp = effectiveThresholdDp(thresholdDp)
        val thresholdPx = effDp * density
        // 回滑取消幅度随实际触发距离放大，避免大阈值下仍用系统 32dp 过早取消
        val minDeltaPx = (thresholdPx * 0.85f)
            .coerceAtLeast(SYSTEM_MIN_DELTA_DP * density * 0.75f)

        val startX = try {
            XposedHelpers.getFloatField(view, "mStartX")
        } catch (_: Throwable) {
            event.x
        }
        val absTravel = abs(event.x - startX)
        val totalDelta = try {
            XposedHelpers.getFloatField(view, "mTotalTouchDelta")
        } catch (_: Throwable) {
            absTravel
        }

        val trigger = if (absTravel > thresholdPx) {
            if (abs(totalDelta) > minDeltaPx) {
                totalDelta > 0f
            } else {
                true
            }
        } else {
            false
        }

        try {
            XposedHelpers.setBooleanField(view, "mTriggerBack", trigger)
        } catch (t: Throwable) {
            Logger.e(TAG, "写入 mTriggerBack 失败", t)
            return isTriggerBack(view)
        }
        Logger.d(TAG) {
            "trigger=$trigger abs=${"%.1f".format(absTravel)}px " +
                "need=${"%.1f".format(thresholdPx)}px(ui=${thresholdDp}→eff=${"%.0f".format(effDp)}dp) " +
                "delta=${"%.1f".format(totalDelta)}"
        }
        return trigger
    }

    private fun switchToPreviousApp(): Boolean {
        return try {
            val previous = findPreviousTask() ?: return false
            val previousId = getTaskId(previous)
            if (previousId <= 0) return false
            startFromRecents(previousId)
        } catch (t: Throwable) {
            Logger.e(TAG, "switchToPreviousApp 失败", t)
            false
        }
    }

    /** 从最近任务里找「当前之外」的第一个可切换应用任务 */
    private fun findPreviousTask(): Any? {
        val tasks = getRecentTasks(12)
        if (tasks.isEmpty()) return null
        val currentId = getRunningTaskId()
        for (task in tasks) {
            val id = getTaskId(task)
            if (id <= 0) continue
            if (currentId > 0 && id == currentId) continue
            if (shouldSkipTask(task)) continue
            return task
        }
        return null
    }

    private fun shouldSkipTask(task: Any): Boolean {
        val pkg = resolveTaskPackage(task) ?: return true
        if (pkg in SKIP_PACKAGES) return true
        // 排除 FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        try {
            val intent = XposedHelpers.getObjectField(task, "baseIntent") as? Intent
            val flags = intent?.flags ?: 0
            if (flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun getRecentTasks(max: Int): List<Any> {
        val cl = classLoader ?: return emptyList()
        try {
            val atmCl = XposedHelpers.findClass("android.app.ActivityTaskManager", cl)
            val atm = XposedHelpers.callStaticMethod(atmCl, "getInstance")
            val userId = try {
                XposedHelpers.callStaticMethod(
                    ActivityManager::class.java,
                    "getCurrentUser"
                ) as Int
            } catch (_: Throwable) {
                0
            }
            val list = try {
                XposedHelpers.callMethod(
                    atm, "getRecentTasks", max, RECENT_IGNORE_UNAVAILABLE, userId
                ) as? List<*>
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(
                        atm, "getRecentTasks", max, RECENT_IGNORE_UNAVAILABLE
                    ) as? List<*>
                } catch (_: Throwable) {
                    null
                }
            }
            if (!list.isNullOrEmpty()) return list.filterNotNull()
        } catch (_: Throwable) {
        }
        try {
            val ctx = appContext ?: return emptyList()
            @Suppress("DEPRECATION")
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return am.getRecentTasks(max, ActivityManager.RECENT_IGNORE_UNAVAILABLE) ?: emptyList()
        } catch (_: Throwable) {
        }
        return emptyList()
    }

    private fun getTaskId(task: Any): Int {
        return try {
            XposedHelpers.getIntField(task, "taskId")
        } catch (_: Throwable) {
            try {
                XposedHelpers.getIntField(task, "persistentId")
            } catch (_: Throwable) {
                -1
            }
        }
    }

    private fun getRunningTaskId(): Int {
        return try {
            val cl = classLoader ?: return -1
            val wrapperCl = XposedHelpers.findClass(
                "com.android.systemui.shared.system.ActivityManagerWrapper",
                cl
            )
            val inst = XposedHelpers.callStaticMethod(wrapperCl, "getInstance")
            // Flyme 走 getRunningTaskInfoListMz / WindowManagerExt.getFilteredTasks
            val task = XposedHelpers.callMethod(inst, "getRunningTask") ?: return -1
            XposedHelpers.getIntField(task, "taskId")
        } catch (_: Throwable) {
            try {
                val ctx = appContext ?: return -1
                @Suppress("DEPRECATION")
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val running = am.getRunningTasks(1)
                if (running.isNullOrEmpty()) -1 else running[0].id
            } catch (_: Throwable) {
                -1
            }
        }
    }

    private fun startFromRecents(taskId: Int): Boolean {
        val cl = classLoader ?: return false
        return try {
            val atmCl = XposedHelpers.findClass("android.app.ActivityTaskManager", cl)
            val service = XposedHelpers.callStaticMethod(atmCl, "getService")
            XposedHelpers.callMethod(
                service,
                "startActivityFromRecents",
                taskId,
                null as Bundle?
            )
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "startActivityFromRecents 失败", t)
            try {
                val service = XposedHelpers.callStaticMethod(
                    ActivityManager::class.java,
                    "getService"
                )
                XposedHelpers.callMethod(service, "moveTaskToFront", taskId, 0)
                true
            } catch (t2: Throwable) {
                Logger.e(TAG, "moveTaskToFront 失败", t2)
                false
            }
        }
    }

    private fun vibrateConfirm() {
        try {
            val ctx = appContext ?: return
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(40L, 180))
        } catch (_: Throwable) {
        }
    }
}

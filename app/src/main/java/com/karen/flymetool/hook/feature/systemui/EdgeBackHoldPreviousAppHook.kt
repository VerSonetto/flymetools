package com.karen.flymetool.hook.feature.systemui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 侧滑返回：按下即开始计时；松手时同时满足「幅度够 + 时长≥HOLD_MS」→ 上一个应用。
 * 两者都满足时，在灰色指示条内显示上一个应用图标。
 */
object EdgeBackHoldPreviousAppHook : FeatureHook {

    private const val HOOK_NAME = "EdgeBackHoldPrev"
    private const val FEATURE_KEY = "edge_back_hold_previous_app"
    private const val HOLD_MS = 1000L
    /** 相对系统 drag_threshold(16dp) 的倍数；2.0 ≈ 32dp，避免太容易触发 */
    private const val THRESHOLD_SCALE = 2.0f
    private const val EDGE_BACK_VIEW =
        "com.flyme.systemui.navigationbar.gestural.EdgeBackView"
    private const val HANDLER =
        "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
    private const val EDGE_PANEL_PARAMS =
        "com.android.systemui.navigationbar.gestural.EdgePanelParams"
    private const val ICON_SIZE_DP = 20f

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

    private val holdReadyRunnable = Runnable {
        if (armedAtElapsed <= 0L) return@Runnable
        val held = SystemClock.elapsedRealtime() - armedAtElapsed
        if (held < HOLD_MS) return@Runnable
        val ctx = resolveContext()
        preparePreviewIcon(ctx)
        showPreview = previewIcon != null && previewTaskId > 0
        Logger.i(
            HOOK_NAME,
            "hold ready show=$showPreview task=$previewTaskId pkg=$previewPkg icon=${previewIcon != null} ctx=${ctx != null}"
        )
        // 手指可能不再 move，持续 invalidate 才能看到图标
        pulseInvalidate()
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

    private fun postHoldReady() {
        try {
            mainHandler.removeCallbacks(holdReadyRunnable)
            mainHandler.postDelayed(holdReadyRunnable, HOLD_MS)
        } catch (t: Throwable) {
            Logger.e(HOOK_NAME, "postHoldReady failed", t)
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

        try {
            appContext = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentApplication"
            ) as? Context
        } catch (_: Throwable) {
        }

        hookFlymeEdgeBackView(lpparam)
        hookAospThreshold(lpparam)
        hookCallbackFromHandler(lpparam)

        Logger.i(HOOK_NAME, "Loaded hold=${HOLD_MS}ms thresholdScale=$THRESHOLD_SCALE + icon preview")
    }

    private fun hookFlymeEdgeBackView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewCl = XposedHelpers.findClass(EDGE_BACK_VIEW, lpparam.classLoader)

            for (ctor in viewCl.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            scaleField(param.thisObject, "mSwipeThreshold", THRESHOLD_SCALE)
                            scaleField(param.thisObject, "mMinDeltaForSwitch", THRESHOLD_SCALE)
                            edgeViewRef = WeakReference(param.thisObject as View)
                        }
                    })
                } catch (_: Throwable) {
                }
            }

            // 按下：立刻开始计时（与幅度并行）
            XposedHelpers.findAndHookMethod(
                viewCl,
                "resetOnDown",
                android.view.MotionEvent::class.java,
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

            // 移动：幅度够 + 时长够 → 显示图标；幅度不够只藏图标，计时不停
            XposedHelpers.findAndHookMethod(
                viewCl,
                "whetherTriggerBack",
                android.view.MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as View
                            edgeViewRef = WeakReference(view)
                            appContext = view.context.applicationContext ?: view.context
                            if (armedAtElapsed == 0L) {
                                // 兜底：若 resetOnDown 未走到，在首次 move 开表
                                startGestureTimer()
                            }
                            val trigger = XposedHelpers.getBooleanField(view, "mTriggerBack")
                            val held = SystemClock.elapsedRealtime() - armedAtElapsed
                            if (trigger && held >= HOLD_MS) {
                                if (!showPreview) {
                                    preparePreviewIcon(view.context)
                                    showPreview = previewIcon != null && previewTaskId > 0
                                    if (showPreview) {
                                        view.invalidate()
                                        pulseInvalidate()
                                    }
                                }
                            } else if (!trigger && showPreview) {
                                // 缩回幅度：仅隐藏预览，保留计时
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

            XposedHelpers.findAndHookMethod(
                viewCl,
                "triggerBack",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 已幅度够才会进 triggerBack；再卡时长（与幅度并行从按下起算）
                        val armed = armedAtElapsed
                        val held = if (armed > 0L) SystemClock.elapsedRealtime() - armed else 0L
                        val taskId = previewTaskId
                        clearArmedState()
                        if (held < HOLD_MS) return
                        val ok = if (taskId > 0) {
                            startFromRecents(taskId)
                        } else {
                            switchToPreviousApp()
                        }
                        if (!ok) return
                        try {
                            val cb = XposedHelpers.getObjectField(param.thisObject, "mBackCallback")
                            XposedHelpers.callMethod(cb, "cancelBack")
                        } catch (_: Throwable) {
                        }
                        param.result = null
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setVisibility", 8)
                        } catch (_: Throwable) {
                        }
                        vibrateConfirm()
                        Logger.i(HOOK_NAME, "EdgeBackView hold ${held}ms → previous app")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked EdgeBackView + icon preview")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook EdgeBackView failed", e)
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
        edgeViewRef?.get()?.invalidate()
    }

    /**
     * 画在灰色指示条鼓包内。
     * 左缘：鼓包尖端 x ≈ mScale * mMaxHeight；右缘对称。
     */
    private fun drawPreviewIcon(view: View, canvas: Canvas, icon: Drawable) {
        try {
            val density = view.resources.displayMetrics.density
            val size = (ICON_SIZE_DP * density).toInt().coerceAtLeast(1)
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
            // 鼓包最宽处大约在 scale * maxHeight，图标放在条带中部
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
            // 不裁切成过小圆角以免看不见
            d.draw(canvas)
            canvas.restoreToCount(save)
        } catch (t: Throwable) {
            Logger.e(HOOK_NAME, "drawPreviewIcon failed", t)
        }
    }

    /** 预加载上一任务图标与 taskId；context 优先用 EdgeBackView 的 */
    private fun preparePreviewIcon(hint: Context?) {
        try {
            val ctx = hint ?: resolveContext() ?: run {
                Logger.i(HOOK_NAME, "preparePreview: no context")
                return
            }
            appContext = ctx.applicationContext ?: ctx
            val tasks = getRecentTasks(8)
            Logger.i(HOOK_NAME, "preparePreview: tasks=${tasks.size}")
            if (tasks.isEmpty()) return
            val currentId = getRunningTaskId()
            var target: Any? = null
            for (task in tasks) {
                val id = getTaskId(task)
                if (id > 0 && (currentId <= 0 || id != currentId)) {
                    target = task
                    previewTaskId = id
                    break
                }
            }
            if (target == null && tasks.size >= 2) {
                target = tasks[1]
                previewTaskId = getTaskId(target)
            }
            if (target == null || previewTaskId <= 0) {
                Logger.i(HOOK_NAME, "preparePreview: no target current=$currentId")
                return
            }
            val pkg = resolveTaskPackage(target)
            previewPkg = pkg
            if (pkg.isNullOrEmpty()) {
                Logger.i(HOOK_NAME, "preparePreview: no package for task=$previewTaskId")
                return
            }
            previewIcon = try {
                val pm = ctx.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationIcon(ai).mutate()
            } catch (t: Throwable) {
                Logger.e(HOOK_NAME, "getApplicationIcon($pkg) failed", t)
                null
            }
            if (previewIcon == null) {
                Logger.i(HOOK_NAME, "preparePreview: icon null pkg=$pkg")
            }
        } catch (t: Throwable) {
            Logger.e(HOOK_NAME, "preparePreviewIcon failed", t)
        }
    }

    private fun resolveTaskPackage(task: Any): String? {
        // RecentTaskInfo.baseIntent / topActivity / realActivity
        try {
            val intent = XposedHelpers.getObjectField(task, "baseIntent") as? android.content.Intent
            val pkg = intent?.component?.packageName ?: intent?.`package`
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {
        }
        for (field in arrayOf("topActivity", "realActivity", "baseActivity")) {
            try {
                val comp = XposedHelpers.getObjectField(task, field) as? android.content.ComponentName
                val pkg = comp?.packageName
                if (!pkg.isNullOrEmpty()) return pkg
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun hookAospThreshold(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val paramsCl = XposedHelpers.findClass(EDGE_PANEL_PARAMS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                paramsCl,
                "getStaticTriggerThreshold",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.result as? Float ?: return
                        param.result = v * THRESHOLD_SCALE
                    }
                }
            )
            try {
                XposedHelpers.findAndHookMethod(
                    paramsCl,
                    "getReactivationTriggerThreshold",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.result as? Float ?: return
                            param.result = v * THRESHOLD_SCALE
                        }
                    }
                )
            } catch (_: Throwable) {
            }
            Logger.i(HOOK_NAME, "Hooked EdgePanelParams thresholds")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook EdgePanelParams failed", e)
        }
    }

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
                                val armed = armedAtElapsed
                                val held = if (armed > 0L) SystemClock.elapsedRealtime() - armed else 0L
                                val taskId = previewTaskId
                                clearArmedState()
                                if (held < HOLD_MS) return
                                val ok = if (taskId > 0) startFromRecents(taskId) else switchToPreviousApp()
                                if (!ok) return
                                p.result = null
                                vibrateConfirm()
                                Logger.i(HOOK_NAME, "callback hold ${held}ms → previous app")
                            }
                        })
                        Logger.i(HOOK_NAME, "Hooked callback ${cb.javaClass.name}")
                    } catch (t: Throwable) {
                        callbackHooked.set(false)
                        Logger.e(HOOK_NAME, "hook callback failed", t)
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
            Logger.e(HOOK_NAME, "hookCallbackFromHandler failed", e)
        }
    }

    private fun scaleField(obj: Any, name: String, scale: Float) {
        try {
            val f = XposedHelpers.getFloatField(obj, name)
            if (f > 0f) XposedHelpers.setFloatField(obj, name, f * scale)
        } catch (_: Throwable) {
            try {
                val f = XposedHelpers.getIntField(obj, name)
                if (f > 0) {
                    XposedHelpers.setIntField(obj, name, (f * scale).toInt().coerceAtLeast(1))
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun switchToPreviousApp(): Boolean {
        return try {
            val tasks = getRecentTasks(8)
            if (tasks.size < 2) return false
            val currentId = getRunningTaskId()
            var previousId = -1
            for (task in tasks) {
                val id = getTaskId(task)
                if (id > 0 && id != currentId) {
                    previousId = id
                    break
                }
            }
            if (previousId <= 0) return false
            startFromRecents(previousId)
        } catch (t: Throwable) {
            Logger.e(HOOK_NAME, "switchToPreviousApp failed", t)
            false
        }
    }

    private fun getRecentTasks(max: Int): List<Any> {
        val cl = classLoader ?: return emptyList()
        try {
            val atmCl = XposedHelpers.findClass("android.app.ActivityTaskManager", cl)
            val atm = XposedHelpers.callStaticMethod(atmCl, "getInstance")
            val list = try {
                XposedHelpers.callMethod(atm, "getRecentTasks", max, 0) as? List<*>
            } catch (_: Throwable) {
                val userId = try {
                    XposedHelpers.callStaticMethod(
                        ActivityManager::class.java,
                        "getCurrentUser"
                    ) as Int
                } catch (_: Throwable) {
                    0
                }
                XposedHelpers.callMethod(atm, "getRecentTasks", max, 0, userId) as? List<*>
            }
            if (list != null) return list.filterNotNull()
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
            Logger.e(HOOK_NAME, "startActivityFromRecents failed", t)
            try {
                val service = XposedHelpers.callStaticMethod(
                    ActivityManager::class.java,
                    "getService"
                )
                XposedHelpers.callMethod(service, "moveTaskToFront", taskId, 0)
                true
            } catch (t2: Throwable) {
                Logger.e(HOOK_NAME, "moveTaskToFront failed", t2)
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

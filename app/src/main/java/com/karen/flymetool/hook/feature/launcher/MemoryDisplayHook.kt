package com.karen.flymetool.hook.feature.launcher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Proxy
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object MemoryDisplayHook : FeatureHook {

    private const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
    private const val LAUNCHER_STATE_CLASS = "com.android.launcher3.LauncherState"
    private const val STATE_MANAGER_CLASS = "com.android.launcher3.statemanager.StateManager"
    private const val TAG = "MemoryDisplay"

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var memoryContainer: LinearLayout? = null
    private var memoryTextView: TextView? = null
    private var isRunning = false
    private var updateInterval: Long = 2000
    private var overviewState: Any? = null

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("memory_display")) return
        if (ctx.packageName != "com.meizu.flyme.launcher") return

        val interval = ctx.featureValue("memory_display", 2000).toLong()
        mount(ctx, interval)
    }

    private fun mount(ctx: HookContext, interval: Long) {
        updateInterval = if (interval > 0) interval else 2000

        try {
            val launcherStateClass = Reflect.findClass(LAUNCHER_STATE_CLASS, ctx.classLoader)
            overviewState = Reflect.getStaticObjectField(launcherStateClass, "OVERVIEW")
        } catch (e: Throwable) {
            Logger.e(TAG, "获取 OVERVIEW 状态失败", e)
        }

        hookLauncherOnCreate(ctx)

        Logger.i(TAG, "已挂载 Launcher 内存显示")
    }

    private fun hookLauncherOnCreate(ctx: HookContext) {
        try {
            val launcherClass = Reflect.findClass(LAUNCHER_CLASS, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                launcherClass,
                "onCreate",
                Bundle::class.java,
            ) { chain ->
                val result = chain.proceed()
                val launcher = chain.getThisObject()
                val context = Reflect.callMethod(ctx.api, launcher, "getApplicationContext") as Context

                val dragLayer = Reflect.callMethod(ctx.api, launcher, "getDragLayer") as? ViewGroup
                val deviceProfile = Reflect.callMethod(ctx.api, launcher, "getDeviceProfile")
                val stateManager = Reflect.callMethod(ctx.api, launcher, "getStateManager")

                if (dragLayer != null && deviceProfile != null) {
                    memoryContainer?.let {
                        try {
                            (it.parent as? ViewGroup)?.removeView(it)
                        } catch (e: Throwable) {
                        }
                    }
                    memoryContainer = null
                    memoryTextView = null

                    createMemoryView(dragLayer, context, deviceProfile)
                    Logger.i(TAG, "已在 DragLayer 创建内存视图")

                    if (stateManager != null) {
                        addStateListener(ctx, stateManager)
                        Logger.i(TAG, "已添加状态监听")
                    }
                } else {
                    Logger.w(TAG, "DragLayer 或 DeviceProfile 为空")
                }
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                launcherClass,
                "onResume",
            ) { chain ->
                val result = chain.proceed()
                val launcher = chain.getThisObject()
                val dragLayer = Reflect.callMethod(ctx.api, launcher, "getDragLayer") as? ViewGroup
                val deviceProfile = Reflect.callMethod(ctx.api, launcher, "getDeviceProfile")

                if (memoryContainer == null && dragLayer != null && deviceProfile != null) {
                    val context = Reflect.callMethod(ctx.api, launcher, "getApplicationContext") as Context
                    createMemoryView(dragLayer, context, deviceProfile)
                    Logger.i(TAG, "已在 onResume 重建内存视图")
                }
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Launcher.onCreate 失败", e)
        }
    }

    private fun addStateListener(ctx: HookContext, stateManager: Any) {
        try {
            val stateListenerClass = Reflect.findClass(
                "$STATE_MANAGER_CLASS\$StateListener",
                stateManager.javaClass.classLoader
            )

            val listener = Proxy.newProxyInstance(
                stateListenerClass.classLoader,
                arrayOf(stateListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onStateTransitionStart" -> {
                        if (args != null && args.isNotEmpty()) {
                            val toState = args[0]
                            if (toState != overviewState) {
                                hideMemoryView()
                            }
                        }
                    }
                    "onStateTransitionComplete" -> {
                        if (args != null && args.isNotEmpty()) {
                            val state = args[0]
                            if (state == overviewState) {
                                showMemoryView()
                            } else {
                                hideMemoryView()
                            }
                        }
                    }
                }
                null
            }

            Reflect.callMethod(ctx.api, stateManager, "addStateListener", listener)
        } catch (e: Throwable) {
            Logger.e(TAG, "添加状态监听失败", e)
        }
    }

    private fun createMemoryView(parent: ViewGroup, context: Context, deviceProfile: Any) {
        if (memoryContainer != null) return

        val density = context.resources.displayMetrics.density
        val marginEnd = (12 * density).toInt()

        val taskTopMargin = Reflect.getIntField(deviceProfile, "overviewTaskThumbnailTopMarginPx")
        val marginTop = (taskTopMargin * 0.1f).toInt()

        memoryContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginTop, 0, 0)
            }
            visibility = View.GONE
        }

        val leftSpace = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        }

        memoryTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, marginEnd, 0)
            }
            textSize = 12f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#60000000"))
            letterSpacing = 0.02f
            text = "-- 可用 | --"
        }

        memoryContainer?.addView(leftSpace)
        memoryContainer?.addView(memoryTextView)
        parent.addView(memoryContainer)

        Logger.i(TAG, "内存视图已创建 marginTop=$marginTop (taskTopMargin=$taskTopMargin)")
    }

    private fun showMemoryView() {
        memoryContainer?.visibility = View.VISIBLE
        if (!isRunning) {
            startMemoryUpdate()
        }
    }

    private fun hideMemoryView() {
        memoryContainer?.visibility = View.GONE
        stopMemoryUpdate()
    }

    private fun startMemoryUpdate() {
        if (isRunning) return
        isRunning = true

        handler.post(object : Runnable {
            override fun run() {
                if (!isRunning) return

                memoryContainer?.let { container ->
                    if (container.visibility == View.VISIBLE) {
                        updateMemoryDisplay(container.context)
                    }
                }
                handler.postDelayed(this, updateInterval)
            }
        })
    }

    private fun stopMemoryUpdate() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateMemoryDisplay(context: Context) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMem = memoryInfo.totalMem
            val availMem = memoryInfo.availMem

            val availGB = availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGB = totalMem / (1024.0 * 1024.0 * 1024.0)

            val availStr = if (availGB >= 1.0) {
                String.format("%.1fG", availGB)
            } else {
                String.format("%.0fM", availMem / (1024.0 * 1024.0))
            }

            val totalStr = if (totalGB >= 1.0) {
                String.format("%.1fG", totalGB)
            } else {
                String.format("%.0fM", totalMem / (1024.0 * 1024.0))
            }

            memoryTextView?.text = "$availStr 可用 | $totalStr"
        } catch (e: Throwable) {
            Logger.e(TAG, "更新内存显示失败", e)
        }
    }
}
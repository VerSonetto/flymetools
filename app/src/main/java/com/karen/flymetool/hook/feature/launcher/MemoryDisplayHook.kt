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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object MemoryDisplayHook : FeatureHook {

    private const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
    private const val LAUNCHER_STATE_CLASS = "com.android.launcher3.LauncherState"
    private const val STATE_MANAGER_CLASS = "com.android.launcher3.statemanager.StateManager"
    private const val HOOK_NAME = "MemoryDisplay"

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var memoryContainer: LinearLayout? = null
    private var memoryTextView: TextView? = null
    private var isRunning = false
    private var updateInterval: Long = 2000
    private var overviewState: Any? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "memory_display")) return
        if (lpparam.packageName != "com.meizu.flyme.launcher") return

        val interval = XposedPrefs.getFeatureValue(lpparam, packageName, "memory_display", 2000).toLong()
        mount(lpparam, interval)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam, interval: Long) {
        updateInterval = if (interval > 0) interval else 2000

        try {
            val launcherStateClass = XposedHelpers.findClass(LAUNCHER_STATE_CLASS, lpparam.classLoader)
            overviewState = XposedHelpers.getStaticObjectField(launcherStateClass, "OVERVIEW")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Get OVERVIEW state failed", e)
        }

        hookLauncherOnCreate(lpparam)

        Logger.i(HOOK_NAME, "Hooked Launcher for memory display")
    }

    private fun hookLauncherOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val launcherClass = XposedHelpers.findClass(LAUNCHER_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                launcherClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val launcher = param.thisObject
                        val context = XposedHelpers.callMethod(launcher, "getApplicationContext") as Context

                        val dragLayer = XposedHelpers.callMethod(launcher, "getDragLayer") as? ViewGroup
                        val deviceProfile = XposedHelpers.callMethod(launcher, "getDeviceProfile")
                        val stateManager = XposedHelpers.callMethod(launcher, "getStateManager")

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
                            Logger.i(HOOK_NAME, "Created memory view in DragLayer")

                            if (stateManager != null) {
                                addStateListener(stateManager)
                                Logger.i(HOOK_NAME, "Added state listener")
                            }
                        } else {
                            Logger.e(HOOK_NAME, "DragLayer or DeviceProfile is null")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                launcherClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val launcher = param.thisObject
                        val dragLayer = XposedHelpers.callMethod(launcher, "getDragLayer") as? ViewGroup
                        val deviceProfile = XposedHelpers.callMethod(launcher, "getDeviceProfile")

                        if (memoryContainer == null && dragLayer != null && deviceProfile != null) {
                            val context = XposedHelpers.callMethod(launcher, "getApplicationContext") as Context
                            createMemoryView(dragLayer, context, deviceProfile)
                            Logger.i(HOOK_NAME, "Recreated memory view in onResume")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook Launcher.onCreate failed", e)
        }
    }

    private fun addStateListener(stateManager: Any) {
        try {
            val stateListenerClass = XposedHelpers.findClass(
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

            XposedHelpers.callMethod(stateManager, "addStateListener", listener)
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Add state listener failed", e)
        }
    }

    private fun createMemoryView(parent: ViewGroup, context: Context, deviceProfile: Any) {
        if (memoryContainer != null) return

        val density = context.resources.displayMetrics.density
        val marginEnd = (12 * density).toInt()

        val taskTopMargin = XposedHelpers.getIntField(deviceProfile, "overviewTaskThumbnailTopMarginPx")
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

        Logger.i(HOOK_NAME, "Memory view created with marginTop=$marginTop (taskTopMargin=$taskTopMargin)")
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
            Logger.e(HOOK_NAME, "Update memory display failed", e)
        }
    }
}

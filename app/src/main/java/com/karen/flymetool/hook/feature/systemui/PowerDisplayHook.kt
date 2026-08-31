package com.karen.flymetool.hook.feature.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object PowerDisplayHook : FeatureHook {

    private const val PHONE_STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    private const val DARK_ICON_DISPATCHER = "com.android.systemui.plugins.DarkIconDispatcher"
    private const val TAG = "PowerDisplay"

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var powerTextView: TextView? = null
    private var isRunning = false
    private var lastVoltage = 0
    private var refreshInterval: Long = 1000

    /** 息屏（含 AOD）时状态栏不可见，停止轮询，亮屏再恢复 */
    private var screenOn = true

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("power_display")) return
        if (ctx.packageName != "com.android.systemui") return

        val interval = ctx.featureValue("power_display", 1000)
        mount(ctx, interval)
    }

    private fun mount(ctx: HookContext, interval: Int) {
        refreshInterval = interval.toLong().coerceIn(500, 5000)

        try {
            val clazz = Reflect.findClass(PHONE_STATUS_BAR_VIEW, ctx.classLoader)
            Reflect.hookMethodOn(ctx.api, clazz, "onFinishInflate") { chain ->
                val result = chain.proceed()
                val statusBarView = chain.getThisObject() as ViewGroup
                val context = statusBarView.context

                powerTextView = createPowerTextView(context)
                val systemIconsId = context.resources.getIdentifier("system_icons", "id", "com.android.systemui")
                val systemIcons = statusBarView.findViewById<LinearLayout>(systemIconsId)
                systemIcons?.addView(powerTextView, 0)

                registerDarkIconDispatcher(context, powerTextView, ctx)

                startPowerUpdate(context)
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun createPowerTextView(context: Context): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8
                marginEnd = 8
            }
            gravity = Gravity.CENTER_VERTICAL
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            text = "0.00W"
        }
    }

    private fun registerDarkIconDispatcher(context: Context, textView: TextView?, ctx: HookContext) {
        if (textView == null) return
        try {
            val darkIconDispatcherClass = Reflect.findClass(DARK_ICON_DISPATCHER, ctx.classLoader)
            val darkReceiverInterface = Reflect.findClass("$DARK_ICON_DISPATCHER\$DarkReceiver", ctx.classLoader)

            val darkReceiver = java.lang.reflect.Proxy.newProxyInstance(
                ctx.classLoader,
                arrayOf(darkReceiverInterface)
            ) { proxy, method, args ->
                when (method.name) {
                    "onDarkChanged" -> {
                        if (args != null && args.size >= 3) {
                            val tint = args[2] as? Int ?: -1
                            textView.setTextColor(tint)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "DarkReceiverProxy@${System.identityHashCode(proxy)}"
                    else -> {
                        if (method.returnType == Void.TYPE) {
                            null
                        } else {
                            throw UnsupportedOperationException("Unexpected method: ${method.name}")
                        }
                    }
                }
            }

            val dependencyClass = Reflect.findClass("com.android.systemui.Dependency", ctx.classLoader)
            val darkIconDispatcher = Reflect.callStaticMethod(ctx.api, dependencyClass, "get", darkIconDispatcherClass)
            Reflect.callMethod(ctx.api, darkIconDispatcher, "addDarkReceiver", darkReceiver)
        } catch (e: Throwable) {
            Logger.e(TAG, "注册 DarkIconDispatcher 失败", e)
            textView.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun startPowerUpdate(context: Context) {
        if (isRunning) return
        isRunning = true

        val updateRunnable = object : Runnable {
            @SuppressLint("DefaultLocale")
            override fun run() {
                val current = readCurrent(context)
                if (lastVoltage > 0 && current != 0) {
                    val power = (lastVoltage / 1000.0) * (abs(current) / 1_000_000.0)
                    powerTextView?.text = String.format("%.2fW", power)
                }
                // 息屏停止循环，避免空闲时持续 binder 轮询与状态栏重绘
                if (isRunning && screenOn) handler.postDelayed(this, refreshInterval)
            }
        }

        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        if (isRunning) {
                            handler.removeCallbacks(updateRunnable)
                            handler.post(updateRunnable)
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        handler.removeCallbacks(updateRunnable)
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        lastVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })

        handler.post(updateRunnable)
    }

    private fun readCurrent(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    }
}
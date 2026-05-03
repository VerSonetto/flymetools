package com.karen.flymetool.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.abs

object PowerDisplayHook {

    private const val PHONE_STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    private const val DARK_ICON_DISPATCHER = "com.android.systemui.plugins.DarkIconDispatcher"
    private const val HOOK_NAME = "PowerDisplay"

    private val handler = Handler(Looper.getMainLooper())
    private var powerTextView: TextView? = null
    private var isRunning = false
    private var lastVoltage = 0

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val clazz = XposedHelpers.findClass(PHONE_STATUS_BAR_VIEW, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(clazz, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val statusBarView = param.thisObject as ViewGroup
                    val context = statusBarView.context

                    powerTextView = createPowerTextView(context)
                    val systemIconsId = context.resources.getIdentifier("system_icons", "id", "com.android.systemui")
                    val systemIcons = statusBarView.findViewById<LinearLayout>(systemIconsId)
                    systemIcons?.addView(powerTextView, 0)

                    registerDarkIconDispatcher(context, powerTextView, lpparam.classLoader)

                    startPowerUpdate(context)
                }
            })
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
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

    private fun registerDarkIconDispatcher(context: Context, textView: TextView?, classLoader: ClassLoader) {
        if (textView == null) return
        try {
            val darkIconDispatcherClass = XposedHelpers.findClass(DARK_ICON_DISPATCHER, classLoader)
            val darkReceiverInterface = XposedHelpers.findClass("$DARK_ICON_DISPATCHER\$DarkReceiver", classLoader)

            val darkReceiver = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
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

            val dependencyClass = XposedHelpers.findClass("com.android.systemui.Dependency", classLoader)
            val darkIconDispatcher = XposedHelpers.callStaticMethod(dependencyClass, "get", darkIconDispatcherClass)
            XposedHelpers.callMethod(darkIconDispatcher, "addDarkReceiver", darkReceiver)
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Register DarkIconDispatcher failed", e)
            textView.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun startPowerUpdate(context: Context) {
        if (isRunning) return
        isRunning = true

        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                lastVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        handler.post(object : Runnable {
            @SuppressLint("DefaultLocale")
            override fun run() {
                val current = readCurrent(context)
                if (lastVoltage > 0 && current != 0) {
                    val power = (lastVoltage / 1000.0) * (abs(current) / 1_000_000.0)
                    powerTextView?.text = String.format("%.2fW", power)
                }
                if (isRunning) handler.postDelayed(this, 1000)
            }
        })
    }

    private fun readCurrent(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    }
}

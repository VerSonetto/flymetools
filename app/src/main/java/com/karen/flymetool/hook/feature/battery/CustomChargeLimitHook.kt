package com.karen.flymetool.hook.feature.battery

import android.content.Context
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.io.File

object CustomChargeLimitHook : FeatureHook {

    private const val TAG = "CustomChargeLimit"
    private const val PACKAGE_NAME = "com.meizu.battery"

    private var currentCtx: HookContext? = null
    private var fileObserver: FileObserver? = null

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("custom_charge_limit")) return

        currentCtx = ctx

        try {
            val activityClass = Reflect.findClass(
                "com.meizu.battery.app.batteryhealth.BatteryHealthActivity",
                ctx.classLoader
            )

            Reflect.hookMethodOn(
                ctx.api,
                activityClass,
                "onCreate",
                Bundle::class.java,
            ) { chain ->
                val result = chain.proceed()

                val activity = chain.getThisObject() ?: return@hookMethodOn result
                try {
                    applyCustomValue(activity)
                    startWatchingPrefs(activity)
                } catch (e: Throwable) {
                    Logger.e(TAG, "设置失败", e)
                }

                return@hookMethodOn result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun applyCustomValue(activity: Any) {
        val hctx = currentCtx ?: return
        val customValue = hctx.featureValue("custom_charge_limit", -1)
        if (customValue in 50..100) {
            val ctx = activity as? Context ?: return
            val current = try {
                Settings.System.getInt(ctx.contentResolver, "mz_charge_limit_percentage", 100)
            } catch (_: Exception) { 100 }
            if (current == customValue) return

            Settings.System.putInt(ctx.contentResolver, "mz_charge_limit_percentage", customValue)
            Settings.System.putInt(ctx.contentResolver, "mz_charge_optimization_switch", if (customValue == 100) 1 else 0)

            updateChargeLimitSummary(hctx, activity, customValue)
            updateChargeOptimizeState(hctx, activity, customValue)
        }
    }

    private fun updateChargeLimitSummary(ctx: HookContext, activity: Any, value: Int) {
        try {
            val pref = Reflect.callMethod(ctx.api, activity, "findPreference", "settings_pref_charge_limit_setting")
            if (pref != null) {
                Reflect.callMethod(ctx.api, pref, "setSummary", "$value%")
            }
        } catch (_: Exception) {}
    }

    private fun updateChargeOptimizeState(ctx: HookContext, activity: Any, value: Int) {
        try {
            val optimizePref = Reflect.callMethod(ctx.api, activity, "findPreference", "settings_pref_charge_optimize")
            if (optimizePref != null) {
                if (value != 100) {
                    Reflect.callMethod(ctx.api, optimizePref, "setChecked", false)
                    Reflect.callMethod(ctx.api, optimizePref, "setEnabled", false)
                } else {
                    Reflect.callMethod(ctx.api, optimizePref, "setEnabled", true)
                }
            }
        } catch (_: Exception) {}
    }

    private fun startWatchingPrefs(activity: Any) {
        val prefsFile = File("/data/data/com.karen.flymetool/shared_prefs/flymetool_prefs.xml")
        if (!prefsFile.exists()) return

        fileObserver?.stopWatching()
        val handler = Handler(Looper.getMainLooper())

        fileObserver = object : FileObserver(prefsFile, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (event != CLOSE_WRITE) return
                handler.postDelayed({
                    applyCustomValue(activity)
                }, 100)
            }
        }.apply { startWatching() }
    }
}
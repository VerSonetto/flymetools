package com.karen.flymetool.hook.feature.battery

import android.content.Context
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object CustomChargeLimitHook : FeatureHook {

    private const val TAG = "CustomChargeLimit"
    private const val PACKAGE_NAME = "com.meizu.battery"

    private var currentLpparam: XC_LoadPackage.LoadPackageParam? = null
    private var fileObserver: FileObserver? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "custom_charge_limit")) return

        currentLpparam = lpparam

        try {
            val activityClass = XposedHelpers.findClass(
                "com.meizu.battery.app.batteryhealth.BatteryHealthActivity",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                activityClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject ?: return
                        try {
                            applyCustomValue(activity)
                            startWatchingPrefs(activity)
                        } catch (e: Throwable) {
                            Logger.e(TAG, "setup failed", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "hook failed", e)
        }
    }

    private fun applyCustomValue(activity: Any) {
        val lp = currentLpparam ?: return
        val customValue = XposedPrefs.getFeatureValue(lp, PACKAGE_NAME, "custom_charge_limit", -1)
        if (customValue in 50..100) {
            val ctx = activity as? Context ?: return
            val current = try {
                Settings.System.getInt(ctx.contentResolver, "mz_charge_limit_percentage", 100)
            } catch (_: Exception) { 100 }
            if (current == customValue) return

            Settings.System.putInt(ctx.contentResolver, "mz_charge_limit_percentage", customValue)
            Settings.System.putInt(ctx.contentResolver, "mz_charge_optimization_switch", if (customValue == 100) 1 else 0)

            updateChargeLimitSummary(activity, customValue)
            updateChargeOptimizeState(activity, customValue)
        }
    }

    private fun updateChargeLimitSummary(activity: Any, value: Int) {
        try {
            val pref = XposedHelpers.callMethod(activity, "findPreference", "settings_pref_charge_limit_setting")
            if (pref != null) {
                XposedHelpers.callMethod(pref, "setSummary", "$value%")
            }
        } catch (_: Exception) {}
    }

    private fun updateChargeOptimizeState(activity: Any, value: Int) {
        try {
            val optimizePref = XposedHelpers.callMethod(activity, "findPreference", "settings_pref_charge_optimize")
            if (optimizePref != null) {
                if (value != 100) {
                    XposedHelpers.callMethod(optimizePref, "setChecked", false)
                    XposedHelpers.callMethod(optimizePref, "setEnabled", false)
                } else {
                    XposedHelpers.callMethod(optimizePref, "setEnabled", true)
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

package com.karen.flymetool.hook.feature.android

import android.content.ContentResolver
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object SuperStereoSoundHook {
    private const val TAG = "SuperStereoSound"
    private const val TARGET_PARAM = "stereo_sound_game_mode"
    private const val SETTING_KEY = "super_stereo_sound"

    private var mainSwitchEnabled = false
    private var contentResolver: ContentResolver? = null

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        hookSettingsSystem(classLoader)
        hookAudioSystem(classLoader)
    }

    private fun hookSettingsSystem(classLoader: ClassLoader) {
        try {
            val settingsSystemClass = XposedHelpers.findClass("android.provider.Settings.System", classLoader)

            XposedHelpers.findAndHookMethod(
                settingsSystemClass,
                "putIntForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key == SETTING_KEY) {
                            val value = param.args[2] as Int
                            mainSwitchEnabled = value == 1
                            Logger.d(TAG, "Main switch updated via Settings: $mainSwitchEnabled")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                settingsSystemClass,
                "getIntForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key == SETTING_KEY) {
                            val resolver = param.args[0] as ContentResolver
                            if (contentResolver == null) {
                                contentResolver = resolver
                                try {
                                    val currentValue = Settings.System.getInt(resolver, SETTING_KEY, 0)
                                    mainSwitchEnabled = currentValue == 1
                                    Logger.i(TAG, "Initial main switch state: $mainSwitchEnabled")
                                } catch (t: Throwable) {
                                    Logger.e(TAG, "Failed to read initial state: ${t.message}")
                                }
                            }
                        }
                    }
                }
            )

            Logger.i(TAG, "Hooked Settings.System")

        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to hook Settings.System: ${t.message}")
        }
    }

    private fun hookAudioSystem(classLoader: ClassLoader) {
        try {
            val audioSystemClass = XposedHelpers.findClass("android.media.AudioSystem", classLoader)

            XposedHelpers.findAndHookMethod(
                audioSystemClass,
                "setParameters",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val keyValue = param.args[0] as? String ?: return
                        
                        if (keyValue.contains(TARGET_PARAM) && mainSwitchEnabled) {
                            val newValue = keyValue.replace(
                                "$TARGET_PARAM=false",
                                "$TARGET_PARAM=true"
                            )
                            if (newValue != keyValue) {
                                param.args[0] = newValue
                                Logger.i(TAG, "Forced stereo_sound_game_mode=true")
                            }
                        }
                    }
                }
            )

            Logger.i(TAG, "SuperStereoSoundHook initialized")

        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to hook AudioSystem: ${t.message}")
        }
    }
}

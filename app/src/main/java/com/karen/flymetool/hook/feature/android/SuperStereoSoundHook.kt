package com.karen.flymetool.hook.feature.android

import android.content.ContentResolver
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object SuperStereoSoundHook : FeatureHook {
    private const val TAG = "SuperStereoSound"
    private const val TARGET_PARAM = "stereo_sound_game_mode"
    private const val SETTING_KEY = "super_stereo_sound"

    private var mainSwitchEnabled = false
    private var contentResolver: ContentResolver? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_super_stereo")) return
        if (lpparam.packageName != "android") return

        // 该功能仅适配 Flyme 10（原 android 作用域也仅对 Flyme 10 开放），
        // 放开作用域后需在运行时兜底，避免 Flyme 11/12 误挂载。
        if (!FlymeVersionUtils.isFlyme10()) {
            Logger.i(TAG, "Skip - 该功能仅适用于 Flyme 10")
            return
        }

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
                            Logger.d(TAG) { "主开关已通过 Settings 更新: $mainSwitchEnabled" }
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
                                    Logger.i(TAG, "初始主开关状态: $mainSwitchEnabled")
                                } catch (t: Throwable) {
                                    Logger.e(TAG, "读取初始状态失败", t)
                                }
                            }
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 Settings.System")

        } catch (t: Throwable) {
            Logger.e(TAG, "挂载 Settings.System 失败", t)
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
                                Logger.i(TAG, "已强制 stereo_sound_game_mode=true")
                            }
                        }
                    }
                }
            )

            Logger.i(TAG, "SuperStereoSoundHook 初始化完成")

        } catch (t: Throwable) {
            Logger.e(TAG, "挂载 AudioSystem 失败", t)
        }
    }
}

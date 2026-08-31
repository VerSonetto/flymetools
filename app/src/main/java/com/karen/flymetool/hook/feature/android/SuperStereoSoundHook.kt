package com.karen.flymetool.hook.feature.android

import android.content.ContentResolver
import android.provider.Settings
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object SuperStereoSoundHook : FeatureHook {
    private const val TAG = "SuperStereoSound"
    private const val TARGET_PARAM = "stereo_sound_game_mode"
    private const val SETTING_KEY = "super_stereo_sound"

    private var mainSwitchEnabled = false
    private var contentResolver: ContentResolver? = null

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("force_super_stereo")) return
        if (ctx.packageName != "android") return

        // 该功能仅适配 Flyme 10（原 android 作用域也仅对 Flyme 10 开放），
        // 放开作用域后需在运行时兜底，避免 Flyme 11/12 误挂载。
        if (!FlymeVersionUtils.isFlyme10()) {
            Logger.i(TAG, "Skip - 该功能仅适用于 Flyme 10")
            return
        }

        hookSettingsSystem(ctx)
        hookAudioSystem(ctx)
    }

    private fun hookSettingsSystem(ctx: HookContext) {
        try {
            val settingsSystemClass = Reflect.findClass("android.provider.Settings.System", ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                settingsSystemClass,
                "putIntForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.java,
                Int::class.java,
            ) { chain ->
                val key = chain.getArg(1) as? String ?: return@hookMethodOn chain.proceed()
                if (key == SETTING_KEY) {
                    val value = chain.getArg(2) as Int
                    mainSwitchEnabled = value == 1
                    Logger.d(TAG) { "主开关已通过 Settings 更新: $mainSwitchEnabled" }
                }
                return@hookMethodOn chain.proceed()
            }

            Reflect.hookMethodOn(
                ctx.api,
                settingsSystemClass,
                "getIntForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.java,
                Int::class.java,
            ) { chain ->
                val result = chain.proceed()

                val key = chain.getArg(1) as? String ?: return@hookMethodOn result
                if (key == SETTING_KEY) {
                    val resolver = chain.getArg(0) as ContentResolver
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
                return@hookMethodOn result
            }

            Logger.i(TAG, "已挂载 Settings.System")

        } catch (t: Throwable) {
            Logger.e(TAG, "挂载 Settings.System 失败", t)
        }
    }

    private fun hookAudioSystem(ctx: HookContext) {
        try {
            val audioSystemClass = Reflect.findClass("android.media.AudioSystem", ctx.classLoader)

            Reflect.hookMethodOn(ctx.api, audioSystemClass, "setParameters", String::class.java) { chain ->
                val keyValue = chain.getArg(0) as? String ?: return@hookMethodOn chain.proceed()

                if (keyValue.contains(TARGET_PARAM) && mainSwitchEnabled) {
                    val newValue = keyValue.replace(
                        "$TARGET_PARAM=false",
                        "$TARGET_PARAM=true"
                    )
                    if (newValue != keyValue) {
                        Logger.i(TAG, "已强制 stereo_sound_game_mode=true")
                        val newArgs = chain.getArgs().toMutableList().apply { set(0, newValue) }.toTypedArray()
                        return@hookMethodOn chain.proceed(newArgs)
                    }
                }
                return@hookMethodOn chain.proceed()
            }

            Logger.i(TAG, "SuperStereoSoundHook 初始化完成")

        } catch (t: Throwable) {
            Logger.e(TAG, "挂载 AudioSystem 失败", t)
        }
    }
}
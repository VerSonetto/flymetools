package com.karen.flymetool.hook.feature.launcher

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

/**
 * 自定义桌面页数上限。
 *
 * getMaxScreenCount() 的原生值是「9 个普通桌面页 + 整页助手/隐私空间的屏位」共 9/10/11，
 * 故按差值偏移、只改其中的 9，助手与隐私空间的占位照旧。
 */
object HomeScreenLimitHook : FeatureHook {

    private const val TAG = "HomeScreenLimit"
    private const val KEY = "home_screen_limit"
    private const val PROFILE_CLASS = "com.meizu.flyme.launcher.deviceprofile.FlymeDeviceProfile"
    private const val FIELD_MAX_SCREEN_COUNT = "mMaxScreenCount"

    private const val NATIVE_PAGE_COUNT = 9
    private const val MIN_PAGES = 1
    private const val MAX_PAGES = 30

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled(KEY)) return

        val targetPages = ctx.featureValue(KEY, NATIVE_PAGE_COUNT).coerceIn(MIN_PAGES, MAX_PAGES)
        if (targetPages == NATIVE_PAGE_COUNT) return

        val profileClass = try {
            Reflect.findClass(PROFILE_CLASS, ctx.classLoader)
        } catch (e: Throwable) {
            Logger.w(TAG, "未找到 $PROFILE_CLASS，桌面页数上限未改写", "error" to e)
            return
        }

        try {
            Reflect.hookMethodOn(ctx.api, profileClass, "getMaxScreenCount") { chain ->
                val native = try {
                    Reflect.getIntField(chain.getThisObject(), FIELD_MAX_SCREEN_COUNT)
                } catch (e: Throwable) {
                    Logger.once(
                        TAG, "noField",
                        "读取 $FIELD_MAX_SCREEN_COUNT 失败，桌面页数上限保持系统默认",
                        "error" to e
                    )
                    return@hookMethodOn chain.proceed()
                }
                (native + targetPages - NATIVE_PAGE_COUNT).coerceAtLeast(MIN_PAGES)
            }
            Logger.i(TAG, "桌面页数上限已改写为 $targetPages 页")
        } catch (e: NoSuchMethodException) {
            Logger.w(TAG, "未找到 getMaxScreenCount，桌面页数上限未改写", "error" to e)
        } catch (e: Throwable) {
            Logger.e(TAG, "桌面页数上限 Hook 失败", e)
        }
    }
}

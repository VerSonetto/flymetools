package com.karen.flymetool.hook.feature.launcher

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.hook.base.XposedPrefs

/**
 * 桌面：响应「唤起检索」请求，直接展开左右边缘上下滑动呼出的字母检索面板。
 *
 * 该面板由 slideappsearch 包实现，入口是触摸控制器：先在边缘按下（touchInEdge 记录方向），
 * 再由 disposeMotionEvent 展开。这里构造对应的合成触摸事件走同一条路径，
 * 避免另造一套展开/隐藏逻辑。相关类名与方法名均未被混淆，按名调用即可。
 *
 * 触发方式：系统界面工具的「唤起检索」条目发一个带 EXTRA 的 HOME Intent，
 * 桌面被拉到前台的 onNewIntent / onCreate 里识别该 EXTRA 后执行。
 */
object SlideSearchLauncherHook : FeatureHook {

    private const val TAG = "SlideSearchLauncher"
    private const val TARGET_PACKAGE = "com.meizu.flyme.launcher"

    /** 与系统界面工具侧约定的请求标记 */
    private const val EXTRA_SHOW_SLIDE_SEARCH = "com.karen.flymetool.extra.SHOW_SLIDE_SEARCH"

    /** 弧菜单从哪半屏展开：0=左、1=右 */
    private const val EXTRA_SIDE = "com.karen.flymetool.extra.SLIDE_SEARCH_SIDE"

    /** 开关只保留系统界面工具侧那一份，这里读同一份远程偏好 */
    private const val OWNER_PACKAGE = "com.flyme.systemuitools"
    private const val OWNER_FEATURE_KEY = "slide_gesture_search_entry"

    private const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"

    /** 原版触摸区宽度：MzSlideAppsSearchPhoneImpl 初始化时取 17dp */
    private const val EDGE_TOUCH_AREA_DP = 17f

    private const val RETRY_DELAY_MS = 250L
    private const val MAX_ATTEMPTS = 8

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != TARGET_PACKAGE) return
        if (!XposedPrefs.isFeatureEnabled(OWNER_PACKAGE, OWNER_FEATURE_KEY)) {
            Logger.i(TAG, "系统界面工具侧开关未开启，跳过")
            return
        }

        hookIntentEntry(ctx, "onCreate", android.os.Bundle::class.java)
        hookIntentEntry(ctx, "onNewIntent", Intent::class.java)
        hookResumeFallback(ctx)

        Logger.i(TAG, "已加载")
    }

    /**
     * 桌面主 Activity 是 singleTask，正常情况走 onNewIntent；
     * 进程被杀后重建则走 onCreate，两条都要接。
     */
    private fun hookIntentEntry(ctx: HookContext, name: String, vararg params: Class<*>?) {
        try {
            val clazz = Reflect.findClass(LAUNCHER_ACTIVITY, ctx.classLoader)
            Reflect.hookMethodOn(ctx.api, clazz, name, *params) { chain ->
                val result = chain.proceed()
                try {
                    val launcher = chain.getThisObject()
                    val intent = chain.getArgs().getOrNull(0) as? Intent
                    val source = intent ?: (launcher as? android.app.Activity)?.intent
                    if (source?.getBooleanExtra(EXTRA_SHOW_SLIDE_SEARCH, false) == true) {
                        val side = source.getIntExtra(EXTRA_SIDE, 0)
                        Logger.i(TAG, "收到唤起检索请求（$name，侧边=${if (side == 1) "右" else "左"}）")
                        scheduleShow(ctx, launcher, side, 1)
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "处理唤起检索请求失败", t)
                }
                result
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook $name 失败", t)
        }
    }

    /**
     * 兜底：singleTask 复用时若未走 onNewIntent，回到前台的 onResume 里再认一次。
     * 认完把 EXTRA 抹掉，避免后续每次回到桌面都重新弹出。
     */
    private fun hookResumeFallback(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(LAUNCHER_ACTIVITY, ctx.classLoader)
            Reflect.hookMethodOn(ctx.api, clazz, "onResume") { chain ->
                val result = chain.proceed()
                try {
                    val launcher = chain.getThisObject() as? android.app.Activity ?: return@hookMethodOn result
                    val intent = launcher.intent ?: return@hookMethodOn result
                    if (intent.getBooleanExtra(EXTRA_SHOW_SLIDE_SEARCH, false)) {
                        val side = intent.getIntExtra(EXTRA_SIDE, 0)
                        intent.removeExtra(EXTRA_SHOW_SLIDE_SEARCH)
                        Logger.i(TAG, "收到唤起检索请求（onResume 兜底，侧边=${if (side == 1) "右" else "左"}）")
                        scheduleShow(ctx, launcher, side, 1)
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "onResume 处理唤起检索请求失败", t)
                }
                result
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook onResume 失败", t)
        }
    }

    /** 桌面刚回到前台时状态机可能还在过渡，touchInEdge 会被拒，因此有限次重试。 */
    private fun scheduleShow(ctx: HookContext, launcher: Any?, side: Int, attempt: Int) {
        if (launcher == null) return
        Handler(Looper.getMainLooper()).postDelayed({
            val shown = try {
                showSlideSearch(ctx, launcher, side)
            } catch (t: Throwable) {
                Logger.e(TAG, "展开字母检索面板失败", t)
                true
            }
            if (shown) return@postDelayed
            if (attempt < MAX_ATTEMPTS) {
                scheduleShow(ctx, launcher, side, attempt + 1)
            } else {
                Logger.w(TAG, "多次重试仍未展开，可能不处于桌面正常状态")
            }
        }, RETRY_DELAY_MS)
    }

    private fun showSlideSearch(ctx: HookContext, launcher: Any, side: Int): Boolean {
        val search = Reflect.callMethod(ctx.api, launcher, "getSlideAppSearch") ?: return false
        if (Reflect.callMethod(ctx.api, search, "isShowed") == true) return true

        val dragLayer = Reflect.callMethod(ctx.api, launcher, "getDragLayer") as? View ?: return false
        val width = dragLayer.width
        val height = dragLayer.height
        if (width <= 0 || height <= 0) return false

        // 简易模式或用户在设置里关掉时触摸区为 0，这里按原版默认值兜底
        val touchArea = runCatching { Reflect.getIntField(search, "mTouchArea") }.getOrDefault(0)
        if (touchArea <= 0) {
            val edge = (EDGE_TOUCH_AREA_DP * dragLayer.resources.displayMetrics.density).toInt()
            runCatching { Reflect.setIntField(search, "mTouchArea", edge) }
        }

        val now = SystemClock.uptimeMillis()
        val centerY = height / 2f
        // 与弧菜单同侧展开：右侧取右边缘内 1px，左侧取左边缘内 1px
        val edgeX = if (side == 1) width - 1f else 1f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, edgeX, centerY, 0)
        val move = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, edgeX, centerY, 0)
        return try {
            if (Reflect.callMethod(ctx.api, search, "touchInEdge", down) != true) {
                Logger.d(TAG) { "touchInEdge 未接受本次按下" }
                return false
            }
            Reflect.callMethod(ctx.api, search, "disposeMotionEvent", move, 0)
            val shown = Reflect.callMethod(ctx.api, search, "isShowed") == true
            if (shown) Logger.i(TAG, "已展开桌面字母检索面板")
            shown
        } finally {
            down.recycle()
            move.recycle()
        }
    }
}

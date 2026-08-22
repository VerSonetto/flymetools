package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap

/**
 * 让经典时钟使用与原生四款景深时钟一致的抠图方式。
 *
 * 原生实现不是把主体 PNG 额外盖在锁屏根视图上，而是以 DST_OUT 将时钟中与主体重合的
 * 像素擦除，露出下方原始壁纸。因此主体始终只有一个，也完全跟随壁纸动画。
 */
object ClassicClockDepthOverlayHook : FeatureHook {

    private const val TAG = "ClassicClockDepth"
    private const val EDITOR_PACKAGE = "com.flyme.systemuieditor"
    private const val FEATURE_KEY = "classic_clock_depth"
    private const val DATE_CLOCK_SECTION =
        "com.flyme.systemui.keyguard.ui.view.layout.sections.DateClockSection"
    private const val SETTING_KEY = "flymetool_classic_clock_dof_data"

    private val cutouts = WeakHashMap<View, CutoutState>()
    private val watchedClocks = WeakHashMap<View, Unit>()
    private var configObserver: ContentObserver? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, EDITOR_PACKAGE, FEATURE_KEY)) return

        try {
            val sectionClass = XposedHelpers.findClass(DATE_CLOCK_SECTION, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                sectionClass,
                "addViews",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val host = param.args.firstOrNull() as? ViewGroup ?: return
                        val clock = findClassicClock(host) ?: return
                        watchedClocks[clock] = Unit
                        installConfigObserver(clock)
                        clock.post { installOrClear(host, clock) }
                    }
                },
            )
            Logger.i(TAG, "已挂载经典时钟景深挖空层")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载经典时钟景深挖空层失败", e)
        }
    }

    /** 通过公开资源 id 定位经典时钟根布局，而非混淆字段或方法。 */
    private fun findClassicClock(host: ViewGroup): View? {
        val id = host.resources.getIdentifier("time", "id", host.context.packageName)
        return if (id == 0) null else host.findViewById(id)
    }

    private fun installOrClear(host: ViewGroup, clock: View) {
        try {
            removeCutout(clock)
            val config = readConfig(clock) ?: return
            if (!config.optBoolean("enabled")) return
            val path = config.optString("mask_path").takeIf { it.isNotBlank() } ?: return
            val image = File(path)
            if (!image.isFile || !image.canRead()) {
                Logger.w(TAG, "景深抠图不可读: $path")
                return
            }
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap == null) {
                Logger.w(TAG, "无法解码景深抠图: $path")
                return
            }

            val cutout = ClassicDofCutoutView(host.context, host, clock, bitmap)
            // 锁屏蓝图在下拉通知中心时会用 ConstraintSet 克隆宿主；其所有直接子 View
            // 都必须带 id，否则 clone 会直接抛异常并导致 SystemUI 崩溃。
            cutout.id = View.generateViewId()
            val previousLayerType = host.layerType
            if (previousLayerType != View.LAYER_TYPE_HARDWARE) {
                host.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            host.addView(cutout, params)
            cutout.startTrackingClockGeometry()
            cutouts[clock] = CutoutState(host, cutout, previousLayerType)
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cutout.invalidate() }
            Logger.d(TAG) { "已更新经典时钟景深挖空层" }
        } catch (e: Throwable) {
            Logger.e(TAG, "更新经典时钟景深挖空层失败", e)
        }
    }

    private fun removeCutout(clock: View) {
        val state = cutouts.remove(clock) ?: return
        state.host.removeView(state.cutout)
        state.cutout.recycle()
        if (state.host.layerType != state.previousLayerType) {
            state.host.setLayerType(state.previousLayerType, null)
        }
    }

    /** 点击编辑器按钮后立即刷新已创建的经典时钟。 */
    private fun installConfigObserver(clock: View) {
        if (configObserver != null) return
        try {
            configObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    watchedClocks.keys.toList().forEach { watched ->
                        val host = watched.parent as? ViewGroup ?: return@forEach
                        watched.post { installOrClear(host, watched) }
                    }
                }
            }
            clock.context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_KEY),
                false,
                configObserver!!,
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "监听经典时钟景深配置失败", e)
        }
    }

    private fun readConfig(view: View): JSONObject? = try {
        Settings.Secure.getString(view.context.contentResolver, SETTING_KEY)
            ?.let(::JSONObject)
    } catch (e: Throwable) {
        Logger.w(TAG, "读取经典时钟景深配置失败: ${e.javaClass.simpleName}")
        null
    }

    private data class CutoutState(
        val host: ViewGroup,
        val cutout: ClassicDofCutoutView,
        val previousLayerType: Int,
    )

    /**
     * 在宿主画布上用原生抠图擦除时钟区域。抠图的位置使用壁纸同样的中心裁剪规则，
     * 因此露出的正好是底层锁屏壁纸中的主体，而不是一张额外移动的前景图。
     */
    private class ClassicDofCutoutView(
        context: Context,
        private val host: ViewGroup,
        private val clock: View,
        private val bitmap: Bitmap,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        private val hostLocation = IntArray(2)
        private val clockLocation = IntArray(2)
        private val destination = Rect()
        private var lastClockLeft = Int.MIN_VALUE
        private var lastClockTop = Int.MIN_VALUE
        private var lastClockWidth = Int.MIN_VALUE
        private var lastClockHeight = Int.MIN_VALUE
        private var lastClockShown = false
        private var trackingClockGeometry = false
        private val geometryTracker = ViewTreeObserver.OnPreDrawListener {
            if (!trackingClockGeometry) return@OnPreDrawListener true
            clock.getLocationOnScreen(clockLocation)
            val changed =
                lastClockLeft != clockLocation[0] ||
                    lastClockTop != clockLocation[1] ||
                    lastClockWidth != clock.width ||
                    lastClockHeight != clock.height ||
                    lastClockShown != clock.isShown
            if (changed) {
                lastClockLeft = clockLocation[0]
                lastClockTop = clockLocation[1]
                lastClockWidth = clock.width
                lastClockHeight = clock.height
                lastClockShown = clock.isShown
                // 下拉通知中心使用 translation/alpha 过渡，不一定触发布局回调；
                // 仅在几何实际变化时使硬件层失效，避免景深延后到下一次系统刷新。
                host.invalidate()
                invalidate()
            }
            true
        }

        init {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            if (bitmap.isRecycled || width <= 0 || height <= 0 || !clock.isShown) return
            host.getLocationOnScreen(hostLocation)
            clock.getLocationOnScreen(clockLocation)
            val root = host.rootView
            val screenWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val screenHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val scale = maxOf(
                screenWidth.toFloat() / bitmap.width,
                screenHeight.toFloat() / bitmap.height,
            )
            val imageWidth = (bitmap.width * scale).toInt()
            val imageHeight = (bitmap.height * scale).toInt()
            val imageLeft = (screenWidth - imageWidth) / 2 - hostLocation[0]
            val imageTop = (screenHeight - imageHeight) / 2 - hostLocation[1]
            destination.set(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)

            val saveCount = canvas.save()
            canvas.clipRect(
                clockLocation[0] - hostLocation[0],
                clockLocation[1] - hostLocation[1],
                clockLocation[0] - hostLocation[0] + clock.width,
                clockLocation[1] - hostLocation[1] + clock.height,
            )
            canvas.drawBitmap(bitmap, null, destination, paint)
            canvas.restoreToCount(saveCount)
        }

        fun startTrackingClockGeometry() {
            if (trackingClockGeometry) return
            trackingClockGeometry = true
            if (host.viewTreeObserver.isAlive) {
                host.viewTreeObserver.addOnPreDrawListener(geometryTracker)
            }
        }

        fun recycle() {
            trackingClockGeometry = false
            if (host.viewTreeObserver.isAlive) {
                host.viewTreeObserver.removeOnPreDrawListener(geometryTracker)
            }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

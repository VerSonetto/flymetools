package com.karen.flymetool.hook.feature.systemui

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.hook.base.XposedPrefs
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
    private const val KEYGUARD_DATE_CLOCK_VIEW = "com.flyme.keyguard.clock.KeyguardDateClockView"
    private const val SETTING_KEY = "flymetool_classic_clock_dof_data"
    private const val AOD_STYLE_SETTING_KEY = "aod_current_style"
    private const val ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED"
    private const val WALLPAPER_REFRESH_INTERVAL_MS = 500L
    private const val WALLPAPER_REFRESH_RETRIES = 12

    /**
     * 息屏样式切换的壁纸重绑宽限窗口。样式键写入在前、壁纸重绑在后，
     * 实测间隔为秒级，15 秒足以覆盖且把误宽限暴露面压到最低。
     */
    private const val STYLE_REBIND_WINDOW_MS = 15_000L

    private val cutouts = WeakHashMap<View, CutoutState>()
    private val watchedClocks = WeakHashMap<View, Unit>()
    /** AOD 会复用锁屏的 DateClockSection；息屏态绝不向该树注入挖空 View。 */
    private val dozingClocks = WeakHashMap<View, Unit>()
    /**
     * 已验证与抠图同源的壁纸 ID 集合（按时钟弱引用）。绑定成功时加入当前 ID；
     * 息屏样式切换的重绑会保留并追加新 ID；仅严格失配与功能关闭时清空。
     */
    private val trustedWallpaperIds = WeakHashMap<View, MutableSet<Int>>()
    /**
     * 预解码的抠图缓存。锁屏 view 树首次挂载时 decode 一次即可，
     * 后续 wallpaper 未变、bitmap 未失效就直接命中，避免进锁屏瞬间的卡顿。
     */
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    /**
     * 抠图专用单线程解码器：保证解码与文件 IO 互斥，
     * 不与主线程抢锁屏 view 树的构建节奏。
     */
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlymeToolClassicDofDecode").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var configObserver: ContentObserver? = null
    private var wallpaperChangedReceiver: BroadcastReceiver? = null
    private var observedContentResolver: ContentResolver? = null

    /** 最近一次息屏样式变更时间（uptimeMillis），0 表示当前无宽限。主线程读写。 */
    private var styleChangeAt = 0L
    /** 样式键最近一次的值，用于过滤重复写入。主线程读写。 */
    private var lastStyleValue: String? = null
    private var styleObserver: ContentObserver? = null
    private var observedStyleResolver: ContentResolver? = null

    override fun handle(ctx: HookContext) {
        if (!XposedPrefs.isFeatureEnabled(EDITOR_PACKAGE, FEATURE_KEY)) return

        try {
            val sectionClass = Reflect.findClass(DATE_CLOCK_SECTION, ctx.classLoader)
            Reflect.hookAllMethods(
                ctx.api,
                sectionClass,
                "addViews",
                excluded = { false }
            ) { chain ->
                val result = chain.proceed()
                val host = chain.getArgs().firstOrNull() as? ViewGroup ?: return@hookAllMethods result
                val clock = findClassicClock(host) ?: return@hookAllMethods result
                watchedClocks[clock] = Unit
                // 主动尝试一次预热：handle() 阶段已注册的 ContentObserver 此刻已就绪，
                // 锁屏 view 树一旦建好就能立即挂抠图，不再等下一次 addViews。
                primeBitmapFromConfig(clock.context.applicationContext)
                clock.post { installOrClear(host, clock) }
                result
            }
            Logger.i(TAG, "已挂载经典时钟景深挖空层")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载经典时钟景深挖空层失败", e)
        }
        hookDozingState(ctx)
        // SystemUI 进程起来就注册 ContentObserver + 提前预热当前壁纸的抠图到内存，
        // 重启后第一次进锁屏时不再走磁盘 IO，避免景深延迟一帧出现。
        primeBitmapFromConfig(null)
    }

    /**
     * DateClockSection 同时服务锁屏和跟随锁屏的 AOD。以时钟公开的 dozing 回调作为边界，
     * 而不是猜测父容器或依赖混淆后的 AOD 控制器。
     */
    private fun hookDozingState(ctx: HookContext) {
        try {
            val clockClass = Reflect.findClass(KEYGUARD_DATE_CLOCK_VIEW, ctx.classLoader)
            Reflect.hookMethodOn(
                ctx.api,
                clockClass,
                "setDozing",
                Boolean::class.javaPrimitiveType!!,
            ) { chain ->
                val result = chain.proceed()
                val clock = chain.getThisObject() as? View ?: return@hookMethodOn result
                if (!watchedClocks.containsKey(clock)) return@hookMethodOn result
                if (chain.getArgs().firstOrNull() as? Boolean == true) {
                    dozingClocks[clock] = Unit
                    removeCutout(clock)
                } else {
                    dozingClocks.remove(clock)
                    val host = clock.parent as? ViewGroup ?: return@hookMethodOn result
                    // AOD→锁屏过渡会连续触发多次 dozing 翻转。延迟一帧让 view 树
                    // 完成重建再判断，避免景深挖空层跟着 AOD 一起闪烁一帧。
                    clock.postDelayed({ installOrClear(host, clock) }, 80L)
                }
                result
            }
            Logger.i(TAG, "已挂载经典时钟 AOD 状态隔离")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载经典时钟 AOD 状态隔离失败", e)
        }
    }

    /** 通过公开资源 id 定位经典时钟根布局，而非混淆字段或方法。 */
    private fun findClassicClock(host: ViewGroup): View? {
        val id = host.resources.getIdentifier("time", "id", host.context.packageName)
        return if (id == 0) null else host.findViewById(id)
    }

    private fun installOrClear(host: ViewGroup, clock: View) {
        try {
            // 一进 view 树就注册 observer，让后续任何 Settings.Secure 变化立即生效。
            installConfigObserver(clock.context.applicationContext)
            installStyleChangeObserver(clock.context.applicationContext)
            installWallpaperChangedReceiver(clock.context.applicationContext)
            if (dozingClocks.containsKey(clock)) {
                removeCutout(clock)
                return
            }
            val config = readConfig(clock) ?: return
            if (!config.optBoolean("enabled")) {
                removeCutout(clock)
                trustedWallpaperIds.remove(clock)
                return
            }
            val path = config.optString("mask_path").takeIf { it.isNotBlank() } ?: run {
                removeCutout(clock)
                trustedWallpaperIds.remove(clock)
                return
            }
            val expectedWallpaperId = config.optInt("wallpaper_id", -1)
            val currentWallpaperId = currentLockscreenWallpaperId(clock.context)
            val idMatches = expectedWallpaperId >= 0 && expectedWallpaperId == currentWallpaperId
            if (idMatches) {
                trustedWallpaperIds.getOrPut(clock) { mutableSetOf() }.add(currentWallpaperId)
            } else {
                val trusted = trustedWallpaperIds[clock]
                val styleGrace = styleChangeAt > 0 &&
                    SystemClock.uptimeMillis() - styleChangeAt <= STYLE_REBIND_WINDOW_MS
                val keep = trusted != null && (currentWallpaperId in trusted || styleGrace)
                if (!keep) {
                    removeCutout(clock)
                    trustedWallpaperIds.remove(clock)
                    Logger.w(
                        TAG,
                        "景深抠图与当前锁屏壁纸不匹配，已跳过旧抠图",
                        "expected" to expectedWallpaperId,
                        "current" to currentWallpaperId,
                    )
                    return
                }
                if (currentWallpaperId !in trusted) {
                    // 息屏样式切换会让系统重绑同一张壁纸（ID 变内容不变）。样式键刚变更过
                    // 且绑定此前已验证时，信任新 ID 并持久记账，用户无需回编辑器重新开启。
                    // 宽限随即消费：窗口内后续的普通壁纸变更仍走严格校验。
                    trusted.add(currentWallpaperId)
                    styleChangeAt = 0
                    Logger.once(
                        TAG,
                        "dof-keep-$currentWallpaperId",
                        "息屏样式切换重绑壁纸，已保留经典时钟景深",
                        "expected" to expectedWallpaperId,
                        "current" to currentWallpaperId,
                    )
                }
                if (cutouts[clock]?.maskPath == path) return
            }
            if (
                cutouts[clock]?.let { it.wallpaperId == currentWallpaperId && it.maskPath == path } == true
            ) {
                return
            }
            val cached = bitmapCache[path]
            if (cached != null && !cached.isRecycled) {
                attachCutout(host, clock, cached, currentWallpaperId, path)
                return
            }
            // 缓存未命中：后台线程 decode，期间锁屏已显示也无需等待。
            // decode 完成后切回主线程挂载，下一帧 onDraw 即出图。
            val pendingHost = WeakReference(host)
            val pendingClock = WeakReference(clock)
            decodeExecutor.execute {
                val bitmap = try {
                    if (!File(path).isFile || !File(path).canRead()) {
                        Logger.w(TAG, "景深抠图不可读: $path")
                        null
                    } else {
                        BitmapFactory.decodeFile(path)?.also { bitmapCache[path] = it }
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "解码景深抠图失败: $path", e)
                    null
                }
                if (bitmap == null) return@execute
                mainHandler.post {
                    val h = pendingHost.get() ?: return@post
                    val c = pendingClock.get() ?: return@post
                    if (dozingClocks.containsKey(c)) return@post
                    if (cutouts[c]?.let { it.maskPath == path && it.wallpaperId == currentWallpaperId } == true) {
                        return@post
                    }
                    attachCutout(h, c, bitmap, currentWallpaperId, path)
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "更新经典时钟景深挖空层失败", e)
        }
    }

    /**
     * handle() 阶段提前预热当前生效的抠图到内存缓存。
     * 锁屏 view 树首次 addViews 触发时大概率已 decode 完毕，直接命中缓存。
     */
    private fun primeBitmapFromConfig(context: Context?) {
        try {
            val resolver = context?.contentResolver ?: run {
                // 进程首次 handle() 阶段 context 还没就绪：等下一次 addViews 触发即可，
                // 那时 installOrClear 会调用 installConfigObserver 注册 observer，
                // 并在缓存未命中时走后台 decode——这条快路径只是锦上添花。
                return
            }
            val config = readConfigViaResolver(resolver) ?: return
            if (!config.optBoolean("enabled")) return
            val path = config.optString("mask_path").takeIf { it.isNotBlank() } ?: return
            if (bitmapCache[path]?.takeIf { !it.isRecycled } != null) return
            decodeExecutor.execute {
                try {
                    if (!File(path).isFile || !File(path).canRead()) return@execute
                    val bitmap = BitmapFactory.decodeFile(path) ?: return@execute
                    bitmapCache[path] = bitmap
                    // 锁屏 view 树若已就绪，立即把已 watch 的 clock 全部刷新一次。
                    mainHandler.post { refreshWatchedClocks() }
                } catch (e: Throwable) {
                    Logger.e(TAG, "预热经典时钟景深抠图失败", e)
                }
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "提前预热抠图失败: ${e.javaClass.simpleName}")
        }
    }

    private fun attachCutout(
        host: ViewGroup,
        clock: View,
        bitmap: Bitmap,
        currentWallpaperId: Int,
        path: String,
    ) {
        try {
            removeCutout(clock)
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
            cutouts[clock] = CutoutState(host, cutout, previousLayerType, currentWallpaperId, path)
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cutout.invalidate() }
            Logger.d(TAG) { "已更新经典时钟景深挖空层" }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载经典时钟景深挖空层失败", e)
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

    /** 配置变更立即刷新已创建的所有经典时钟。 */
    private fun installConfigObserver(context: Context) {
        val resolver = context.contentResolver
        if (configObserver != null && observedContentResolver === resolver) return
        try {
            // 同一进程内切换宿主 Context 时，先解绑旧的 observer。
            configObserver?.let { observedContentResolver?.unregisterContentObserver(it) }
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    // 配置一旦变更，缓存里可能指向旧 mask_path，主动失效。
                    val resolver = context.applicationContext.contentResolver
                    val current = readConfigViaResolver(resolver)
                    evictStaleBitmaps(current?.optString("mask_path")?.takeIf { it.isNotBlank() })
                    primeBitmapFromConfig(context.applicationContext)
                    refreshWatchedClocks()
                }
            }
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_KEY),
                false,
                observer,
            )
            configObserver = observer
            observedContentResolver = resolver
        } catch (e: Throwable) {
            Logger.e(TAG, "监听经典时钟景深配置失败", e)
        }
    }

    /**
     * 监听息屏样式键（Settings.System）：样式切换会让系统随后重绑同一张壁纸
     * （ID 变内容不变）。宽限窗口内到达的壁纸 ID 失配按重绑处理而不是清除景深。
     */
    private fun installStyleChangeObserver(context: Context) {
        val resolver = context.contentResolver
        if (styleObserver != null && observedStyleResolver === resolver) return
        try {
            styleObserver?.let { observedStyleResolver?.unregisterContentObserver(it) }
            lastStyleValue = Settings.System.getString(resolver, AOD_STYLE_SETTING_KEY)
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val value = Settings.System.getString(resolver, AOD_STYLE_SETTING_KEY)
                    if (value != null && value != lastStyleValue) {
                        lastStyleValue = value
                        styleChangeAt = SystemClock.uptimeMillis()
                        Logger.d(TAG) { "检测到息屏样式变更，开启壁纸重绑宽限" }
                    }
                }
            }
            resolver.registerContentObserver(
                Settings.System.getUriFor(AOD_STYLE_SETTING_KEY),
                false,
                observer,
            )
            styleObserver = observer
            observedStyleResolver = resolver
        } catch (e: Throwable) {
            Logger.e(TAG, "监听息屏样式变更失败", e)
        }
    }

    /**
     * 壁纸先完成切换、编辑器随后才完成抠图写入。连续几次轻量刷新可覆盖这段间隔，
     * 使已创建的时钟层无需息屏重建也能换成新 Bitmap。
     */
    private fun installWallpaperChangedReceiver(context: Context?) {
        if (context == null || wallpaperChangedReceiver != null) return
        try {
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == ACTION_WALLPAPER_CHANGED) {
                        refreshAfterWallpaperChanged(0)
                    }
                }
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter(ACTION_WALLPAPER_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            wallpaperChangedReceiver = receiver
            Logger.i(TAG, "已监听经典时钟壁纸刷新")
        } catch (e: Throwable) {
            Logger.e(TAG, "监听经典时钟壁纸刷新失败", e)
        }
    }

    private fun refreshAfterWallpaperChanged(retry: Int) {
        mainHandler.postDelayed({
            refreshWatchedClocks()
            if (retry < WALLPAPER_REFRESH_RETRIES && watchedClocks.isNotEmpty()) {
                refreshAfterWallpaperChanged(retry + 1)
            }
        }, WALLPAPER_REFRESH_INTERVAL_MS)
    }

    private fun refreshWatchedClocks() {
        watchedClocks.keys.toList().forEach { watched ->
            val host = watched.parent as? ViewGroup ?: return@forEach
            watched.post { installOrClear(host, watched) }
        }
    }

    /** 壁纸变更或配置变更时主动把已废弃的抠图 bitmap 从缓存里淘汰。 */
    private fun evictStaleBitmaps(currentPath: String?) {
        if (currentPath == null) {
            bitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
            bitmapCache.clear()
            return
        }
        val stale = bitmapCache.keys.filter { it != currentPath }
        stale.forEach { path ->
            bitmapCache.remove(path)?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun readConfig(view: View): JSONObject? = readConfigViaResolver(view.context.contentResolver)

    private fun readConfigViaResolver(resolver: ContentResolver): JSONObject? = try {
        Settings.Secure.getString(resolver, SETTING_KEY)
            ?.let(::JSONObject)
    } catch (e: Throwable) {
        Logger.w(TAG, "读取经典时钟景深配置失败: ${e.javaClass.simpleName}")
        null
    }

    /**
     * 部分息屏样式下系统不保留锁屏专属壁纸条目（锁屏复用系统壁纸条目，此时
     * getWallpaperId(FLAG_LOCK) 返回 -1）。锁屏显示的正是该系统壁纸，退回其 ID
     * 才能与编辑器侧得到一致的校验基准，否则景深在该类样式下永远无法通过校验。
     */
    private fun currentLockscreenWallpaperId(context: Context): Int = try {
        val wallpaperManager = context.getSystemService(WallpaperManager::class.java)
            ?: return -1
        wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
            .takeIf { it >= 0 }
            ?: wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (e: Throwable) {
        Logger.e(TAG, "读取当前锁屏壁纸 ID 失败", e)
        -1
    }

    private data class CutoutState(
        val host: ViewGroup,
        val cutout: ClassicDofCutoutView,
        val previousLayerType: Int,
        val wallpaperId: Int,
        val maskPath: String,
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
package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import com.karen.flymetool.util.NotificationCardBlurMath
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * 通知中心媒体播放器卡片紧凑布局。
 *
 * 版式：左侧大封面（垂直居中），右侧从上到下为 标题 / 歌手 / 进度条（两侧时间）/
 * 底部按钮行；按钮按语义分两组，各组下方铺一个独立的胶囊背景 View（方案 B）。
 * 投屏键与标题垂直居中对齐；进度条去掉拖动圆点（改透明 thumb，保留可拖动）。
 *
 * 原理：卡片根布局是 MediaCarouseTransitionLayout(extends TransitionLayout)，
 * 每个子 View 的位置与尺寸都由 MediaViewController 里的 expandedLayout(ConstraintSet)
 * 经 TransitionLayout 计算并摆位，直接改 LayoutParams/margin 会被覆盖。
 * 因此重排卡片必须改 ConstraintSet，新增的胶囊 View 同样被 initFromLayout 自动纳管。
 *
 * 特征定位：仅使用未混淆的框架类名与方法名，控件走 Resources.getIdentifier 取 id。
 */
object MediaCardCompactHook : FeatureHook {

    private const val TAG = "MediaCardCompact"
    private const val SYSTEMUI = "com.android.systemui"
    private const val KEY = "media_card_compact"

    /** 与“通知卡片模糊”共用同一组调节参数，让胶囊也跟随强度/不透明度/曲线联动。 */
    private const val BLUR_FEATURE_KEY = "notification_card_blur"
    private const val BLUR_OPACITY_SUFFIX = "opacity"
    private const val BLUR_BEAUTIFY_SUFFIX = "beautify"

    private const val CLS_VIEW_CONTROLLER =
        "com.android.systemui.media.controls.ui.controller.MediaViewController"
    private const val CLS_CONTROL_PANEL =
        "com.android.systemui.media.controls.ui.controller.MediaControlPanel"
    private const val CLS_CAROUSEL =
        "com.android.systemui.media.controls.ui.controller.MediaCarouselController"
    private const val CLS_CAROUSE_LAYOUT =
        "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout"
    private const val CLS_TRANSITION_LAYOUT =
        "com.android.systemui.util.animation.TransitionLayout"
    private const val CLS_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val CLS_WALLPAPER_BLUR_MANAGER =
        "com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager"

    // androidx.constraintlayout.widget.ConstraintSet 常量（不能直接引用模块自带的 androidx 类）
    private const val PARENT_ID = 0
    private const val LEFT = 1
    private const val RIGHT = 2
    private const val TOP = 3
    private const val BOTTOM = 4
    private const val START = 6
    private const val END = 7
    private const val MATCH_CONSTRAINT = 0
    private const val WRAP_CONTENT = -2
    private const val GONE = 8
    private const val CHAIN_SPREAD = 0

    private val ANCHORS = intArrayOf(LEFT, RIGHT, TOP, BOTTOM, START, END)

    // 版式（dp）：CARD_H = PAD_V * 2 + ALBUM
    private const val CARD_H = 152
    private const val ALBUM = 116
    private const val PAD_V = 18
    private const val PAD_H = 16
    private const val GAP = 12
    private const val GROUP_GAP = 8
    private const val BTN_H = 38
    private const val SEEK_H = 18
    // 时间文本固定宽度 + 紧凑间距：宽度不能小于常见时间文本宽度，否则 TransitionLayout
    // 会按 widgetState.width 裁剪秒位；固定 34dp 在 Flyme 10dp 时间字号下足够显示 1:00:00。
    private const val TIME_W = 34
    private const val TIME_START_GAP = 10
    private const val TIME_END_MARGIN = 12
    private const val TIME_GAP = 4
    private const val ROW_GAP = 6

    private val PREV_WORDS = listOf("上一", "prev", "pre", "previous", "rewind")
    private val NEXT_WORDS = listOf("下一", "next", "forward")
    private val PLAY_WORDS = listOf("播放", "暂停", "play", "pause", "toggle")

    private val idCache = HashMap<String, Int>()

    /** 每张卡片注入的两个胶囊背景 View 的 id（[左组, 右组]） */
    private val pillIdCache = WeakHashMap<View, IntArray>()

    /** 胶囊 View 自身索引，供“通知卡片模糊”Hook 识别并一起调节。 */
    private val pillViews = WeakHashMap<View, Boolean>()

    /** 音乐壁纸状态（key = MediaViewController）：壁纸态下约束集归壁纸版式接管，不得套紧凑约束。 */
    private val wallpaperState = WeakHashMap<Any, Boolean>()

    /** 退出壁纸后待执行的紧凑版式补套任务（key = MediaControlPanel）。 */
    private val pendingReapply = WeakHashMap<Any, Runnable>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    /** 供 [NotificationCardBlurHook] 判断某个 View 是否为紧凑布局注入的胶囊背景。 */
    internal fun isPillView(view: View): Boolean {
        if (pillViews.containsKey(view)) return true
        val parent = view.parent as? ViewGroup ?: return false
        val ids = pillIdCache[parent] ?: return false
        return view.id == ids[0] || view.id == ids[1]
    }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, KEY)) return
        if (lpparam.packageName != SYSTEMUI) return
        if (!FlymeVersionUtils.isFlyme12()) {
            Logger.w(TAG, "仅适配 Flyme 12 的媒体卡片结构，已跳过")
            return
        }
        loadParam = lpparam

        hookLayoutConstraints(lpparam)
        hookBindPlayer(lpparam)
        hookTransitionTimeClip(lpparam)
        hookMusicWallpaperTransition(lpparam)
        hookCardHeight(lpparam)
        hookPillBlurAlpha(lpparam)
        hookPillModeRefresh(lpparam)
    }

    /** 约束集载入后立刻重排一次（attach 时调用），避免首帧闪原版布局。 */
    private fun hookLayoutConstraints(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_VIEW_CONTROLLER, lpparam.classLoader)
            val hooked = XposedBridge.hookAllMethods(
                clazz,
                "loadLayoutConstraints",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 壁纸大卡片期间约束集由壁纸版式接管，套紧凑约束会打崩展开态
                            if (wallpaperState[param.thisObject] == true) return
                            val ctx = XposedHelpers.getObjectField(param.thisObject, "context")
                                as? Context ?: return
                            val set = XposedHelpers.callMethod(param.thisObject, "getExpandedLayout")
                                ?: return
                            applyLayout(ctx, set, null, null)
                            XposedHelpers.callMethod(param.thisObject, "refreshState")
                        } catch (e: Throwable) {
                            Logger.e(TAG, "重排约束集失败", e)
                        }
                    }
                }
            )
            if (hooked.isEmpty()) {
                Logger.w(TAG, "未找到 MediaViewController#loadLayoutConstraints，仅依赖 bindPlayer 兜底")
            } else {
                Logger.i(TAG, "已挂载 MediaViewController#loadLayoutConstraints")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MediaViewController 失败", e)
        }
    }

    /**
     * 每次绑定后重排 + 注入胶囊 + 去掉进度条圆点。
     * bindPlayer 末尾会依次调用 setProgressBarStyle / setPlayerButtonStyle / setTextSize
     * 回写约束集与控件样式，所以必须在它之后再套一遍我们的版式。
     */
    private fun hookBindPlayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_CONTROL_PANEL, lpparam.classLoader)
            val hooked = XposedBridge.hookAllMethods(
                clazz,
                "bindPlayer",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            restyle(param.thisObject)
                        } catch (e: Throwable) {
                            Logger.e(TAG, "绑定后重排失败", e)
                        }
                    }
                }
            )
            if (hooked.isEmpty()) {
                Logger.w(TAG, "未找到 MediaControlPanel#bindPlayer")
            } else {
                Logger.i(TAG, "已挂载 MediaControlPanel#bindPlayer")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MediaControlPanel 失败", e)
        }
    }

    /**
     * TransitionLayout 对 TextView 会按 widgetState.width 做 clipBounds 裁剪，
     * 动画中间态宽度小于文本测量宽度时，右侧秒位会被裁掉。
     * 此处清掉裁剪，文本始终完整显示。
     */
    private fun hookTransitionTimeClip(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_TRANSITION_LAYOUT, lpparam.classLoader)
            val hooked = XposedBridge.hookAllMethods(
                clazz,
                "applyCurrentState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val layout = param.thisObject as? ViewGroup ?: return
                            val ctx = layout.context ?: return
                            val rootId = id(ctx, "qs_media_controls")
                            if (rootId != 0 && layout.id != rootId) return
                            val elapsed = id(ctx, "media_scrubbing_elapsed_time")
                            val total = id(ctx, "media_scrubbing_total_time")
                            if (elapsed == 0 && total == 0) return
                            if (elapsed != 0) {
                                (layout.findViewById<View>(elapsed) as? TextView)?.clipBounds = null
                            }
                            if (total != 0) {
                                (layout.findViewById<View>(total) as? TextView)?.clipBounds = null
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            if (hooked.isEmpty()) {
                Logger.w(TAG, "未找到 TransitionLayout#applyCurrentState，时间文本可能仍会动画裁字")
            } else {
                Logger.i(TAG, "已挂载 TransitionLayout#applyCurrentState 时间文本防裁剪")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 TransitionLayout#applyCurrentState 失败", e)
        }
    }

    /**
     * 音乐壁纸退出补套。
     *
     * 退出时系统会把约束集回写成原版：收起动画（HEIGHT_ANIMATION_DURATION=1500ms）每帧
     * setAlbumArtSize()，结束时封面尺寸与卡片 minHeight 均为原版值；setExpandedAlphaAnimator
     * 起始就同步把 media_seamless_text / icon 翻回 VISIBLE（原版约束位置）。而 bindPlayer 与
     * loadLayoutConstraints 都不会重跑，紧凑版式无人恢复。
     * 故在 refreshMusicWallpaperState 收到 toOpen=false 后补套：动画路径等收起动画结束后补
     * （对齐系统自身 settle 时机 +300ms）；无动画路径当帧补。
     */
    private fun hookMusicWallpaperTransition(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_CONTROL_PANEL, lpparam.classLoader)
            val hooked = XposedBridge.hookAllMethods(
                clazz,
                "refreshMusicWallpaperState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val panel = param.thisObject ?: return
                            val inWallpaper = try {
                                XposedHelpers.getBooleanField(panel, "mIsUseMediaBackground")
                            } catch (_: Throwable) {
                                return
                            }
                            markWallpaper(panel, inWallpaper)
                            if (inWallpaper) {
                                pendingReapply.remove(panel)?.let { mainHandler.removeCallbacks(it) }
                            } else {
                                // 第 5 参 z5 = animate
                                val animate = param.args.getOrNull(4) as? Boolean ?: true
                                scheduleCompactReapply(panel, animate)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            if (hooked.isEmpty()) {
                Logger.w(TAG, "未找到 MediaControlPanel#refreshMusicWallpaperState，退出壁纸后紧凑版式可能错位")
            } else {
                Logger.i(TAG, "已挂载 MediaControlPanel#refreshMusicWallpaperState")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载音乐壁纸退出补套失败", e)
        }
    }

    private fun scheduleCompactReapply(panel: Any, animate: Boolean) {
        pendingReapply.remove(panel)?.let { mainHandler.removeCallbacks(it) }
        val reapply = Runnable {
            try {
                val inWallpaper = XposedHelpers.callMethod(panel, "isUseMediaBackground")
                    as? Boolean ?: false
                if (!inWallpaper) {
                    restyle(panel)
                    Logger.d(TAG) { "退出音乐壁纸后已补套紧凑版式" }
                }
            } catch (_: Throwable) {
            }
        }
        pendingReapply[panel] = reapply
        if (!animate) {
            mainHandler.post(reapply)
            return
        }
        val duration = try {
            (XposedHelpers.getStaticObjectField(
                panel.javaClass, "HEIGHT_ANIMATION_DURATION"
            ) as? Number)?.toLong() ?: 1500L
        } catch (_: Throwable) {
            1500L
        }
        mainHandler.postDelayed(reapply, duration + 300)
        mainHandler.postDelayed(reapply, duration + 800)
    }

    private fun markWallpaper(panel: Any, inWallpaper: Boolean) {
        try {
            XposedHelpers.getObjectField(panel, "mMediaViewController")?.let {
                wallpaperState[it] = inWallpaper
            }
        } catch (_: Throwable) {
        }
    }

    /** 轮播容器高度：原版 qs_media_container_height(188dp) -> 紧凑高度。 */
    private fun hookCardHeight(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_CAROUSEL, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                clazz,
                "getMediaCardHeight",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val cur = param.result as? Int ?: return
                            val ctx = XposedHelpers.getObjectField(param.thisObject, "context")
                                as? Context ?: return
                            val res = ctx.resources
                            val dimenId =
                                res.getIdentifier("qs_media_container_height", "dimen", SYSTEMUI)
                            if (dimenId == 0) return
                            // 只改常态高度，音乐壁纸大卡片高度保持原样
                            if (cur != res.getDimensionPixelSize(dimenId)) return
                            param.result = dp(ctx, CARD_H)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 MediaCarouselController#getMediaCardHeight")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MediaCarouselController 失败", e)
        }
    }

    private fun restyle(panel: Any) {
        val holder = XposedHelpers.getObjectField(panel, "mMediaViewHolder") ?: return
        // 音乐壁纸大卡片是另一套元素，不接管
        val useMediaBg = try {
            XposedHelpers.callMethod(panel, "isUseMediaBackground") as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
        if (useMediaBg) {
            markWallpaper(panel, true)
            return
        }
        markWallpaper(panel, false)

        val viewController = XposedHelpers.getObjectField(panel, "mMediaViewController") ?: return
        val set = XposedHelpers.callMethod(viewController, "getExpandedLayout") ?: return
        val player = XposedHelpers.callMethod(holder, "getPlayer") as? View ?: return
        val ctx = player.context ?: return

        val pills = ensurePillViews(player)
        val ordered = computeButtonOrder(ctx, holder, set)
        val timeWidthPx = measureTimeWidthPx(ctx, holder)
        applyLayout(ctx, set, ordered, pills, timeWidthPx)
        applyPillBackgrounds(player, holder, ordered, pills)
        hideSeekThumb(holder)

        val h = dp(ctx, CARD_H)
        if (player.minimumHeight != h) player.minimumHeight = h
        try {
            XposedHelpers.callMethod(player, "setMinHeight", h)
        } catch (_: Throwable) {
        }

        XposedHelpers.callMethod(viewController, "refreshState")
    }

    /**
     * 用当前系统字体/字号实测时间文本最大宽度，再作为固定宽度写入约束集。
     * 这样不同用户字体大小、不同字体族下都不会裁字，同时避免无脑给死宽度导致进度条过短。
     */
    private fun measureTimeWidthPx(ctx: Context, holder: Any): Int? {
        val elapsed = try {
            XposedHelpers.callMethod(holder, "getScrubbingElapsedTimeView") as? TextView
        } catch (_: Throwable) {
            null
        }
        val total = try {
            XposedHelpers.callMethod(holder, "getScrubbingTotalTimeView") as? TextView
        } catch (_: Throwable) {
            null
        }
        val views = listOfNotNull(elapsed, total)
        if (views.isEmpty()) return null

        // 优先用总时长文本：它是一首歌里最长会显示的时间（如 3:45 / 59:59 / 1:00:00）。
        var maxText = elapsed?.text?.toString().orEmpty()
        total?.text?.toString()?.takeIf { it.isNotBlank() }?.let { totalText ->
            if (totalText.length > maxText.length) maxText = totalText
        }
        if (maxText.isBlank()) {
            val seek = try {
                XposedHelpers.callMethod(holder, "getSeekBar") as? SeekBar
            } catch (_: Throwable) {
                null
            }
            maxText = seek?.max?.takeIf { it > 0 }?.let {
                DateUtils.formatElapsedTime((it / 1000L).coerceAtLeast(0L)).toString()
            } ?: "0:00"
        }

        val paint = total?.paint ?: elapsed?.paint ?: return null
        val maxTextWidth = try {
            paint.measureText(maxText)
        } catch (_: Throwable) {
            0f
        }
        val withPadding = maxTextWidth + dp(ctx, 2)
        val measured = Math.ceil(withPadding.toDouble()).toInt()
        return maxOf(dp(ctx, TIME_W), measured)
    }

    /** 隐藏进度条拖动圆点：不能置 null（bindPlayer 会 getThumb().setTint()，NPE），换成透明 ColorDrawable 保留拖动手感。 */
    private fun hideSeekThumb(holder: Any) {
        val seek = try {
            XposedHelpers.callMethod(holder, "getSeekBar") as? SeekBar
        } catch (_: Throwable) {
            null
        } ?: return
        if (seek.thumb is ColorDrawable) return
        try {
            seek.thumb = ColorDrawable(Color.TRANSPARENT)
            seek.thumbOffset = 0
            seek.splitTrack = false
            Logger.once(TAG, "thumb_hidden", "已隐藏进度条拖动圆点")
        } catch (e: Throwable) {
            Logger.once(TAG, "thumb_fail", "隐藏进度条圆点失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 向卡片注入两个纯背景 View 当胶囊。插到索引 0/1 使其位于按钮之下。
     */
    private fun ensurePillViews(player: View): IntArray? {
        if (player !is ViewGroup) return null
        pillIdCache[player]?.let { ids ->
            if (player.findViewById<View>(ids[0]) != null &&
                player.findViewById<View>(ids[1]) != null
            ) {
                return ids
            }
        }
        return try {
            val left = View(player.context).apply { id = View.generateViewId() }
            val right = View(player.context).apply { id = View.generateViewId() }
            player.addView(left, 0)
            player.addView(right, 1)
            val ids = intArrayOf(left.id, right.id)
            pillIdCache[player] = ids
            pillViews[left] = true
            pillViews[right] = true
            Logger.once(TAG, "pill_created", "已注入胶囊背景 View")
            ids
        } catch (e: Throwable) {
            Logger.e(TAG, "注入胶囊背景 View 失败", e)
            null
        }
    }

    /**
     * 计算按钮排列顺序。卡片底部按钮槽位与语义无关，按 contentDescription
     * 认出上一曲/播放/下一曲，其余归左组。
     */
    private fun computeButtonOrder(ctx: Context, holder: Any, set: Any): Pair<List<Int>, Int> {
        val names = listOf(
            "action0", "action1", "action2", "action3", "action4",
            "actionPrev", "actionPlayPause", "actionNext"
        )
        val visible = ArrayList<Int>()
        for (name in names) {
            val vid = id(ctx, name)
            if (vid == 0) continue
            val visibility = try {
                XposedHelpers.callMethod(set, "getVisibility", vid) as? Int ?: View.VISIBLE
            } catch (_: Throwable) {
                View.VISIBLE
            }
            if (visibility == View.VISIBLE) visible.add(vid)
        }
        if (visible.isEmpty()) return emptyList<Int>() to 0

        var prev = 0
        var play = 0
        var next = 0
        val extras = ArrayList<Int>()
        for (vid in visible) {
            val desc = descriptionOf(holder, vid)
            when {
                desc == null -> extras.add(vid)
                matches(desc, PREV_WORDS) && prev == 0 -> prev = vid
                matches(desc, NEXT_WORDS) && next == 0 -> next = vid
                matches(desc, PLAY_WORDS) && play == 0 -> play = vid
                else -> extras.add(vid)
            }
        }
        val transport = listOf(prev, play, next).filter { it != 0 }
        // 认不出传输键就保持原顺序、不分组，避免把按钮排乱
        if (transport.size < 2) return visible to 0
        return (extras + transport) to extras.size
    }

    private fun descriptionOf(holder: Any, viewId: Int): String? {
        val view = try {
            XposedHelpers.callMethod(holder, "getAction", viewId) as? View
        } catch (_: Throwable) {
            null
        } ?: return null
        return view.contentDescription?.toString()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    private fun matches(desc: String, words: List<String>): Boolean =
        words.any { desc.contains(it) }

    /** 重写 expandedLayout：这是整张卡片版式的唯一入口。 */
    private fun applyLayout(
        ctx: Context,
        set: Any,
        ordered: Pair<List<Int>, Int>?,
        pills: IntArray?,
        timeWidthPx: Int? = null
    ) {
        val album = id(ctx, "album_art")
        val title = id(ctx, "header_title")
        val artist = id(ctx, "header_artist")
        val bar = id(ctx, "media_progress_bar")
        if (album == 0 || title == 0 || artist == 0 || bar == 0) {
            Logger.once(TAG, "no_ids", "媒体卡片控件 id 解析失败，已跳过重排")
            return
        }
        val seamless = id(ctx, "media_seamless")
        val seamlessText = id(ctx, "media_seamless_text")
        val appIcon = id(ctx, "icon")
        val elapsed = id(ctx, "media_scrubbing_elapsed_time")
        val total = id(ctx, "media_scrubbing_total_time")

        val padH = dp(ctx, PAD_H)
        val padV = dp(ctx, PAD_V)
        val gap = dp(ctx, GAP)
        val rowGap = dp(ctx, ROW_GAP)
        val timeW = timeWidthPx ?: dp(ctx, TIME_W)

        // 封面：左侧，垂直居中，决定卡片内容高度
        reset(set, album)
        call(set, "constrainWidth", album, dp(ctx, ALBUM))
        call(set, "constrainHeight", album, dp(ctx, ALBUM))
        call(set, "connect", album, START, PARENT_ID, START, padH)
        call(set, "connect", album, TOP, PARENT_ID, TOP, padV)
        call(set, "connect", album, BOTTOM, PARENT_ID, BOTTOM, padV)

        // 设备名与封面上的应用角标：紧凑版式里不显示
        if (seamlessText != 0) call(set, "setVisibility", seamlessText, GONE)
        if (appIcon != 0) call(set, "setVisibility", appIcon, GONE)

        // 标题：封面右侧顶部
        reset(set, title)
        call(set, "constrainWidth", title, MATCH_CONSTRAINT)
        call(set, "constrainHeight", title, WRAP_CONTENT)
        call(set, "constrainedWidth", title, true)
        call(set, "connect", title, START, album, END, gap)
        if (seamless != 0) {
            call(set, "connect", title, END, seamless, START, dp(ctx, 8))
        } else {
            call(set, "connect", title, END, PARENT_ID, END, padH)
        }
        call(set, "connect", title, TOP, album, TOP, 0)
        call(set, "setHorizontalBias", title, 0f)

        // 投屏键：右上角，与标题垂直居中对齐
        if (seamless != 0) {
            reset(set, seamless)
            call(set, "connect", seamless, END, PARENT_ID, END, padH)
            call(set, "connect", seamless, TOP, title, TOP, 0)
            call(set, "connect", seamless, BOTTOM, title, BOTTOM, 0)
        }

        // 歌手：标题下方
        reset(set, artist)
        call(set, "constrainWidth", artist, MATCH_CONSTRAINT)
        call(set, "constrainHeight", artist, WRAP_CONTENT)
        call(set, "constrainedWidth", artist, true)
        call(set, "connect", artist, START, album, END, gap)
        call(set, "connect", artist, END, PARENT_ID, END, padH)
        call(set, "connect", artist, TOP, title, BOTTOM, dp(ctx, 2))
        call(set, "setHorizontalBias", artist, 0f)

        // 底部按钮行：一条水平链，靠封面底边对齐
        val buttons = ordered?.first ?: defaultButtons(ctx)
        val extraCount = ordered?.second ?: 0
        val btnH = dp(ctx, BTN_H)
        val groupGap = dp(ctx, GROUP_GAP)
        for ((index, vid) in buttons.withIndex()) {
            reset(set, vid)
            call(set, "constrainWidth", vid, MATCH_CONSTRAINT)
            call(set, "constrainHeight", vid, btnH)
            call(set, "connect", vid, BOTTOM, album, BOTTOM, 0)
            val startMargin = when {
                index == 0 -> gap
                extraCount in 1 until buttons.size && index == extraCount -> groupGap
                else -> 0
            }
            if (index == 0) {
                call(set, "connect", vid, START, album, END, startMargin)
            } else {
                call(set, "connect", vid, START, buttons[index - 1], END, startMargin)
            }
            if (index == buttons.lastIndex) {
                call(set, "connect", vid, END, PARENT_ID, END, padH)
            } else {
                call(set, "connect", vid, END, buttons[index + 1], START, 0)
            }
        }
        if (buttons.isNotEmpty()) {
            call(set, "setHorizontalChainStyle", buttons[0], CHAIN_SPREAD)
        }

        // 进度条 + 两侧时间：夹在歌手与按钮行之间，上下留等距
        reset(set, bar)
        call(set, "constrainWidth", bar, MATCH_CONSTRAINT)
        call(set, "constrainHeight", bar, dp(ctx, SEEK_H))
        call(set, "connect", bar, TOP, artist, BOTTOM, rowGap)
        if (buttons.isNotEmpty()) {
            call(set, "connect", bar, BOTTOM, buttons[0], TOP, rowGap)
        } else {
            call(set, "connect", bar, BOTTOM, album, BOTTOM, 0)
        }
        if (elapsed != 0) {
            reset(set, elapsed)
            call(set, "constrainWidth", elapsed, timeW)
            call(set, "constrainHeight", elapsed, WRAP_CONTENT)
            call(set, "connect", elapsed, START, album, END, dp(ctx, TIME_START_GAP))
            call(set, "connect", elapsed, TOP, bar, TOP, 0)
            call(set, "connect", elapsed, BOTTOM, bar, BOTTOM, 0)
            call(set, "connect", bar, START, elapsed, END, dp(ctx, TIME_GAP))
        } else {
            call(set, "connect", bar, START, album, END, dp(ctx, TIME_START_GAP))
        }
        if (total != 0) {
            reset(set, total)
            call(set, "constrainWidth", total, timeW)
            call(set, "constrainHeight", total, WRAP_CONTENT)
            call(set, "connect", total, END, PARENT_ID, END, dp(ctx, TIME_END_MARGIN))
            call(set, "connect", total, TOP, bar, TOP, 0)
            call(set, "connect", total, BOTTOM, bar, BOTTOM, 0)
            call(set, "connect", bar, END, total, START, dp(ctx, TIME_GAP))
        } else {
            call(set, "connect", bar, END, PARENT_ID, END, padH)
        }

        // 胶囊背景：与各组按钮范围完全重合
        if (pills != null && buttons.isNotEmpty()) {
            if (extraCount in 1 until buttons.size) {
                constrainPill(set, pills[0], buttons[0], buttons[extraCount - 1])
                constrainPill(set, pills[1], buttons[extraCount], buttons.last())
            } else {
                constrainPill(set, pills[0], buttons[0], buttons.last())
                call(set, "setVisibility", pills[1], GONE)
            }
        }
    }

    private fun constrainPill(set: Any, pillId: Int, firstBtn: Int, lastBtn: Int) {
        reset(set, pillId)
        call(set, "constrainWidth", pillId, MATCH_CONSTRAINT)
        call(set, "constrainHeight", pillId, MATCH_CONSTRAINT)
        call(set, "connect", pillId, START, firstBtn, START, 0)
        call(set, "connect", pillId, END, lastBtn, END, 0)
        call(set, "connect", pillId, TOP, firstBtn, TOP, 0)
        call(set, "connect", pillId, BOTTOM, firstBtn, BOTTOM, 0)
        call(set, "setVisibility", pillId, View.VISIBLE)
    }

    private fun defaultButtons(ctx: Context): List<Int> =
        listOf("action0", "action1", "action2", "action3", "action4")
            .map { id(ctx, it) }
            .filter { it != 0 }

    /**
     * 胶囊背景，按钮自身背景清空（原本是 qs_media_light_source 圆形高亮）。
     *
     * 胶囊复用卡片同一套模糊实现（MzBlurUtils / WallpaperBlurDrawableManager），
     * 前景色比卡片略白一档，形成"同质感、更亮一层"的层次；不支持模糊时退回半透明白。
     */
    private fun applyPillBackgrounds(
        player: View,
        holder: Any,
        ordered: Pair<List<Int>, Int>,
        pills: IntArray?
    ) {
        val buttons = ordered.first
        if (buttons.isEmpty()) return

        for (vid in buttons) {
            val view = try {
                XposedHelpers.callMethod(holder, "getAction", vid) as? View
            } catch (_: Throwable) {
                null
            } ?: continue
            if (view.background != null) view.background = null
        }

        if (pills == null || player !is ViewGroup) return
        refreshPillBackgrounds(player)
    }

    /**
     * 按当前深浅色模式与“通知卡片模糊”参数给胶囊套背景。
     * tag 记录已应用的模式+参数，避免重复 new 出模糊 drawable。
     */
    internal fun refreshPillBackgrounds(player: View) {
        if (player !is ViewGroup) return
        val pills = pillIdCache[player] ?: return
        val ctx = player.context ?: return
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val radius = BTN_H / 2f * ctx.resources.displayMetrics.density
        val stateTag = "pill:${if (night) "night" else "day"}:${blurSettingsKey()}"
        val cl = player.javaClass.classLoader

        for (pillId in pills) {
            val pill = player.findViewById<View>(pillId) ?: continue
            if (pill.tag == stateTag && pill.background != null) continue
            val ok = applyPillBlur(pill, player, radius, night, cl)
            if (!ok) {
                val color = if (night) 0x33FFFFFF else 0x59FFFFFF
                pill.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(color)
                    cornerRadius = radius
                }
            }
            pill.tag = stateTag
        }
    }

    /** 读取“通知卡片模糊”的当前参数，拼进胶囊 tag；未启用时返回 off。 */
    private fun blurSettingsKey(): String {
        val lp = loadParam ?: return "off"
        if (!XposedPrefs.isFeatureEnabled(lp, SYSTEMUI, BLUR_FEATURE_KEY)) return "off"
        val intensity = XposedPrefs.getFeatureValue(
            lp, SYSTEMUI, BLUR_FEATURE_KEY, NotificationCardBlurMath.DEFAULT_INTENSITY
        ).coerceIn(0, NotificationCardBlurMath.MAX_INTENSITY)
        val opacity = XposedPrefs.getFeatureExtraValue(
            lp, SYSTEMUI, BLUR_FEATURE_KEY, BLUR_OPACITY_SUFFIX,
            NotificationCardBlurMath.DEFAULT_OPACITY
        ).coerceIn(0, 100)
        val beautify = XposedPrefs.getFeatureExtraValue(
            lp, SYSTEMUI, BLUR_FEATURE_KEY, BLUR_BEAUTIFY_SUFFIX, 0
        )
        val stackCap = IosNotificationStackHook.stackBlurSoftCapActive
        return "blur:$intensity:$opacity:$beautify:${if (stackCap) "cap" else "nocap"}"
    }

    /** 胶囊前景色：比卡片(70% 白 / 70% 深灰)略白一档。 */
    private fun pillForegroundColor(night: Boolean): Int =
        if (night) 0x4DFFFFFF else 0xD9FFFFFF.toInt()

    /**
     * 给胶囊套上和卡片同源的模糊背景。
     * 卡片自身走 MediaCarouseTransitionLayout.setBlurBgForLive()：
     * MzBlurUtils.setBackgroundBlurDrawable(view, 180, -1f, 圆角, 前景色, debug, alpha)。
     */
    private fun applyPillBlur(
        pill: View,
        player: View,
        radius: Float,
        night: Boolean,
        cl: ClassLoader?
    ): Boolean {
        val color = pillForegroundColor(night)
        return try {
            val utils = blurUtils(cl) ?: return false
            val supported = XposedHelpers.callMethod(utils, "isSupportNotificationBlur")
                as? Boolean ?: false
            if (!supported) return false

            val live = XposedHelpers.callMethod(utils, "hasLiveBlur") as? Boolean ?: false
            if (live) {
                val alpha = try {
                    XposedHelpers.callMethod(utils, "getBackgroundBlurDrawableAlpha", player, false)
                        as? Int ?: 255
                } catch (_: Throwable) {
                    255
                }
                XposedHelpers.callMethod(
                    utils, "setBackgroundBlurDrawable",
                    pill, 180, -1.0f, radius, color, false, if (alpha <= 0) 255 else alpha
                )
            } else {
                val mgrCls = XposedHelpers.findClass(CLS_WALLPAPER_BLUR_MANAGER, cl)
                val mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance", pill.context)
                XposedHelpers.callMethod(mgr, "setAllCornerRadius", pill, radius, radius, radius, radius)
                XposedHelpers.callMethod(mgr, "setAllForegroundColor", pill, color)
                val drawable = XposedHelpers.callMethod(mgr, "addBlurDrawableTo", pill, color, radius)
                    as? android.graphics.drawable.Drawable ?: return false
                pill.background = drawable
            }
            Logger.once(TAG, "pill_blur", "胶囊已套用卡片同源模糊背景 live=$live")
            true
        } catch (e: Throwable) {
            Logger.once(TAG, "pill_blur_fail", "胶囊模糊失败，退回半透明底：${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 卡片自己重算背景时（深浅色模式切换、实况通知状态变化、配置变化都会走
     * MediaCarouseTransitionLayout.setBackground()）顺带刷新胶囊，
     * 否则切到深色模式后胶囊仍是浅色模式的底。
     */
    private fun hookPillModeRefresh(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_CAROUSE_LAYOUT, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                clazz,
                "setBackground",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 只处理 Flyme 自己的无参 setBackground()，不碰 View.setBackground(Drawable)
                            if (param.args.isNotEmpty()) return
                            val player = param.thisObject as? View ?: return
                            refreshPillBackgrounds(player)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 MediaCarouseTransitionLayout#setBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载胶囊模式刷新失败", e)
        }
    }

    /** 跟随卡片的模糊透明度，避免下拉过程中胶囊不跟着淡入淡出。 */
    private fun hookPillBlurAlpha(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLS_CAROUSE_LAYOUT, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                clazz,
                "setBackgroundBlurAlpha",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val player = param.thisObject as? View ?: return
                            val ids = pillIdCache[player] ?: return
                            val alpha = param.args.getOrNull(0) as? Int ?: return
                            val utils = blurUtils(player.javaClass.classLoader) ?: return
                            for (pillId in ids) {
                                val pill = (player as? ViewGroup)?.findViewById<View>(pillId)
                                    ?: continue
                                XposedHelpers.callMethod(
                                    utils, "setBackgroundBlurDrawableAlpha", pill, alpha, false
                                )
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 MediaCarouseTransitionLayout#setBackgroundBlurAlpha")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载胶囊模糊透明度同步失败", e)
        }
    }

    private fun blurUtils(cl: ClassLoader?): Any? = try {
        XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(CLS_BLUR_UTILS, cl), "INSTANCE"
        )
    } catch (_: Throwable) {
        null
    }

    private fun reset(set: Any, viewId: Int) {
        for (anchor in ANCHORS) {
            try {
                XposedHelpers.callMethod(set, "clear", viewId, anchor)
            } catch (_: Throwable) {
            }
        }
    }

    private fun call(set: Any, method: String, vararg args: Any?) {
        try {
            XposedHelpers.callMethod(set, method, *args)
        } catch (e: Throwable) {
            Logger.once(TAG, "cs_$method", "ConstraintSet.$method 调用失败：${e.javaClass.simpleName}")
        }
    }

    private fun id(ctx: Context, name: String): Int = idCache.getOrPut(name) {
        try {
            ctx.resources.getIdentifier(name, "id", SYSTEMUI)
        } catch (_: Throwable) {
            0
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

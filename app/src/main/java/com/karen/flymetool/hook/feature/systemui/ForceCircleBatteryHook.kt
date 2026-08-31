package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

object ForceCircleBatteryHook : FeatureHook {

    private const val FEATURE_FORCE = "force_camera_circle_battery"
    private const val FEATURE_REPLACE_STATUS_BAR = "circle_battery_status_bar_icon"
    private const val TAG = "ForceCircleBattery"

    private const val CAMERA_STATE_CONTROLLER = "com.flyme.systemui.camera.CameraStateController"
    private const val FLYME_BATTERY_METER_VIEW = "com.flyme.statusbar.battery.FlymeBatteryMeterView"

    private const val COLOR_CHARGING = -14626797
    private const val COLOR_LOW_POWER = -21466
    private const val COLOR_CRITICAL = -638961
    private const val COLOR_WHITE = -1
    private const val COLOR_BLACK = -16777216

    private const val TEXT_MODE_NONE = 0
    private const val TEXT_MODE_INSIDE = 1
    private const val TEXT_MODE_SIDE = 2

    /** 相对前摄黑圈尺寸的缩放百分比，100 = 与黑圈同大 */
    private const val DEFAULT_SIZE_SCALE = 100
    private const val DEFAULT_OFFSET_DP = 0
    private const val EXTRA_CUSTOM_LAYOUT = "custom_layout"

    /** 系统默认圆环相对黑圈窗口的内缩比例（rect≈61px / black≈90px@3x） */
    private const val RING_INSET_RATIO = 0.68f

    @Volatile
    private var replaceStatusBarIcon = false

    @Volatile
    private var textMode = TEXT_MODE_NONE

    /** 额外开启项：自定义大小/位置，默认关闭 */
    @Volatile
    private var customLayoutEnabled = false

    @Volatile
    private var sizeScale = DEFAULT_SIZE_SCALE

    @Volatile
    private var offsetXDp = DEFAULT_OFFSET_DP

    @Volatile
    private var offsetYDp = DEFAULT_OFFSET_DP

    private var loadParam: HookContext? = null
    private var prefsPackage: String = "com.android.systemui"

    private val targetBatteryViews: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    private val targetScenes = setOf(
        "StatusBar",
        "KeyguardStatusBar",
        "control_center"
    )

    // 禁止在 object 属性初始化时 new Paint()。
    // Flyme 的 Paint 构造会走 setFlymeTypeface -> FlymeFontsHelper，
    // 在 SystemUI 早期 / 字体未就绪时 Typeface 为 null，直接 NPE，
    // 进而 ExceptionInInitializerError 导致整个 XposedInit 加载失败。
    private val progressPaint by lazy { createStrokePaint() }
    private val backgroundPaint by lazy { createStrokePaint() }
    private val textPaint by lazy { createFillPaint() }

    private fun createStrokePaint(): Paint {
        return createPaintSafe().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun createFillPaint(): Paint {
        return createPaintSafe().apply {
            style = Paint.Style.FILL
        }
    }

    /**
     * Flyme Paint 构造期可能因字体未就绪 NPE。
     * 仅在真正绘制时创建；失败则向上抛，由调用方降级跳过自定义绘制。
     */
    private fun createPaintSafe(): Paint {
        val paint = try {
            Paint(Paint.ANTI_ALIAS_FLAG)
        } catch (t: Throwable) {
            Logger.w(TAG, "Paint(ANTI_ALIAS) 失败，已回退裸构造")
            Paint()
        }
        try {
            paint.isAntiAlias = true
        } catch (_: Throwable) {
        }
        trySetTypefaceSafe(paint, Typeface.DEFAULT)
        return paint
    }

    private fun trySetTypefaceSafe(paint: Paint, typeface: Typeface?) {
        try {
            paint.typeface = typeface ?: Typeface.DEFAULT
        } catch (t: Throwable) {
            Logger.w(TAG, "setTypeface 失败（可忽略）")
        }
    }

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!ctx.featureEnabled(FEATURE_FORCE)) return

        loadParam = ctx
        prefsPackage = ctx.packageName
        replaceStatusBarIcon = ctx.featureEnabled(FEATURE_REPLACE_STATUS_BAR)
        textMode = ctx.featureValue(
            FEATURE_REPLACE_STATUS_BAR,
            TEXT_MODE_NONE
        ).coerceIn(TEXT_MODE_NONE, TEXT_MODE_SIDE)
        refreshLayoutPrefs()

        hookCameraStateController(ctx)
        hookBatteryMeterView(ctx)
    }

    private fun refreshLayoutPrefs() {
        val lp = loadParam ?: return
        customLayoutEnabled = lp.featureExtraValue(
            FEATURE_FORCE, EXTRA_CUSTOM_LAYOUT, 0
        ) == 1
        sizeScale = lp.featureValue(
            FEATURE_FORCE, DEFAULT_SIZE_SCALE
        ).coerceIn(50, 150)
        offsetXDp = lp.featureExtraValue(
            FEATURE_FORCE, "offset_x_dp", DEFAULT_OFFSET_DP
        ).coerceIn(-24, 24)
        offsetYDp = lp.featureExtraValue(
            FEATURE_FORCE, "offset_y_dp", DEFAULT_OFFSET_DP
        ).coerceIn(-24, 24)
    }

    private fun hookCameraStateController(ctx: HookContext) {
        val clazz = findClass(ctx.classLoader, CAMERA_STATE_CONTROLLER) ?: return

        hookBooleanNoArg(ctx.api, clazz, "isUserRequestCircleBattery") {
            !replaceStatusBarIcon
        }

        if (replaceStatusBarIcon) {
            hookBooleanOneBooleanArg(ctx.api, clazz, "shouldShowCircleBatteryView") { false }
            hookBlockShowCircleWindow(ctx.api, clazz)
            hookForceOriginalBatteryVisible(ctx.api, clazz)
        } else {
            hookAlignCircleBatteryWindow(ctx.api, clazz)
            hookApplyLayoutOnShowCircle(ctx.api, clazz)
        }

        try {
            Reflect.hookAllConstructors(ctx.api, clazz) { chain ->
                val result = chain.proceed()
                applyCircleBatteryMode(ctx.api, chain.getThisObject())
                result
            }
            Logger.i(TAG, "CameraStateController Hook 完成, replace=$replaceStatusBarIcon")
        } catch (e: Throwable) {
            Logger.e(TAG, "CameraStateController 构造 Hook 失败", e)
        }
    }

    private fun hookBatteryMeterView(ctx: HookContext) {
        val clazz = findClass(ctx.classLoader, FLYME_BATTERY_METER_VIEW) ?: return

        hookBooleanNoArg(ctx.api, clazz, "isShowingCircleBattery") {
            !replaceStatusBarIcon
        }

        if (replaceStatusBarIcon) {
            // 标记与强制可见只服务「替换状态栏电池图标」模式；
            // 前摄孔位环（replace=false）下状态栏电池显隐交给系统 updateBatteryViewVisibility 管理
            hookMarkTargetBatteryView(ctx.api, clazz)
            hookDrawStatusBarCircle(ctx.api, clazz)
            hookMeasureStatusBarCircle(ctx.api, clazz)
            hookKeepStatusBarCircleVisible(ctx.api, clazz)
        }

        Logger.i(TAG, "FlymeBatteryMeterView Hook 完成, replace=$replaceStatusBarIcon")
    }

    private fun hookBooleanNoArg(
        api: XposedInterface,
        clazz: Class<*>,
        methodName: String,
        resultProvider: () -> Boolean
    ) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == methodName &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.isEmpty()
        } ?: run {
            Logger.w(TAG, "未找到 $methodName()")
            return
        }
        method.isAccessible = true
        Reflect.hookMethod(api, method) { chain ->
            resultProvider()
        }
    }

    private fun hookBooleanOneBooleanArg(
        api: XposedInterface,
        clazz: Class<*>,
        methodName: String,
        resultProvider: () -> Boolean
    ) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == methodName &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: run {
            Logger.w(TAG, "未找到 $methodName(Boolean)")
            return
        }
        method.isAccessible = true
        Reflect.hookMethod(api, method) { chain ->
            resultProvider()
        }
    }

    private fun hookBlockShowCircleWindow(api: XposedInterface, clazz: Class<*>) {
        val method = findNoArgVoidMethod(clazz, "showCircleBatteryIfNecessary") ?: run {
            Logger.w(TAG, "未找到 showCircleBatteryIfNecessary()")
            return
        }
        Reflect.hookMethod(api, method) { chain ->
            hideCircleWindow(api, chain.getThisObject())
            null
        }
    }

    private fun hookAlignCircleBatteryWindow(api: XposedInterface, clazz: Class<*>) {
        val method = findNoArgMethod(clazz, "initBatteryWindowLp") ?: run {
            Logger.w(TAG, "未找到 initBatteryWindowLp()")
            return
        }
        Reflect.hookMethod(api, method) { chain ->
            val result = chain.proceed()
            applyCustomCircleBatteryLayout(api, chain.getThisObject())
            result
        }
    }

    /** 窗口可能已缓存 LayoutParams，展示时再应用一次自定义布局 */
    private fun hookApplyLayoutOnShowCircle(api: XposedInterface, clazz: Class<*>) {
        val method = findNoArgVoidMethod(clazz, "showCircleBatteryIfNecessary") ?: run {
            Logger.w(TAG, "未找到 showCircleBatteryIfNecessary()（布局刷新）")
            return
        }
        Reflect.hookMethod(api, method) { chain ->
            val result = chain.proceed()
            applyCustomCircleBatteryLayout(api, chain.getThisObject())
            result
        }
    }

    private fun hookForceOriginalBatteryVisible(api: XposedInterface, clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == "updateBatteryViewVisibility" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: run {
            Logger.w(TAG, "未找到 updateBatteryViewVisibility(Boolean)")
            return
        }
        method.isAccessible = true
        Reflect.hookMethod(api, method) { chain ->
            val result = chain.proceed()
            forceOriginalBatteryViewsVisible(chain.getThisObject())
            result
        }
    }

    private fun hookMarkTargetBatteryView(api: XposedInterface, clazz: Class<*>) {
        try {
            Reflect.hookMethodOn(
                api,
                clazz,
                "setBatteryPercentView",
                TextView::class.java,
                String::class.java,
            ) { chain ->
                val result = chain.proceed()
                val scene = chain.getArgs().getOrNull(1) as? String ?: return@hookMethodOn result
                if (scene !in targetScenes) return@hookMethodOn result
                targetBatteryViews[chain.getThisObject()] = true
                (chain.getThisObject() as? View)?.let { view ->
                    view.visibility = View.VISIBLE
                    applyTextMode(view)
                    view.requestLayout()
                    view.invalidate()
                }
                Logger.once(TAG, "circle_mark_$scene", "已标记 $scene 电池图标为环形绘制目标")
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "setBatteryPercentView Hook 失败", e)
        }
    }

    private fun hookDrawStatusBarCircle(api: XposedInterface, clazz: Class<*>) {
        try {
            Reflect.hookMethodOn(api, clazz, "onDraw", Canvas::class.java) { chain ->
                val view = chain.getThisObject() as? View ?: return@hookMethodOn chain.proceed()
                if (!isTargetBatteryView(view)) return@hookMethodOn chain.proceed()
                val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookMethodOn chain.proceed()
                drawCircleBattery(view, canvas)
                null
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "onDraw Hook 失败", e)
        }
    }

    private fun hookMeasureStatusBarCircle(api: XposedInterface, clazz: Class<*>) {
        try {
            Reflect.hookMethodOn(
                api,
                clazz,
                "onMeasure",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ) { chain ->
                val result = chain.proceed()
                val view = chain.getThisObject() as? View ?: return@hookMethodOn result
                if (!isTargetBatteryView(view)) return@hookMethodOn result
                val size = getStatusBarCircleSize(view)
                val width = if (textMode == TEXT_MODE_SIDE) {
                    size + getSideTextWidth(view) + getSideTextGap(view)
                } else {
                    size
                }
                Reflect.callMethod(api, view, "setMeasuredDimension", width, size)
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "onMeasure Hook 失败", e)
        }
    }

    private fun hookKeepStatusBarCircleVisible(api: XposedInterface, clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == "apply" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: run {
            Logger.w(TAG, "未找到 apply(Boolean)")
            return
        }
        method.isAccessible = true
        Reflect.hookMethod(api, method) { chain ->
            val result = chain.proceed()
            val view = chain.getThisObject() as? View ?: return@hookMethod result
            if (!isTargetBatteryView(view)) return@hookMethod result
            view.visibility = View.VISIBLE
            applyTextMode(view)
            view.requestLayout()
            view.invalidate()
            result
        }
    }

    private fun applyCircleBatteryMode(api: XposedInterface, controller: Any) {
        try {
            Reflect.setBooleanField(controller, "mShowCircleBattery", !replaceStatusBarIcon)
        } catch (_: Throwable) {
        }

        if (replaceStatusBarIcon) {
            hideCircleWindow(api, controller)
            forceOriginalBatteryViewsVisible(controller)
        } else {
            try {
                Reflect.callMethod(api, controller, "updateCircleBatteryWindowVisibility")
            } catch (e: Throwable) {
                Logger.w(TAG, "刷新环形电量窗口失败")
            }
        }
    }

    private fun hideCircleWindow(api: XposedInterface, controller: Any) {
        try {
            Reflect.setBooleanField(controller, "mShowCircleBattery", false)
        } catch (_: Throwable) {
        }
        try {
            Reflect.callMethod(api, controller, "hideCircleBatteryIfNecessary")
        } catch (_: Throwable) {
        }
    }

    /**
     * 前摄环形电量窗口布局：
     * - 默认：仅复用黑圈 gravity/x/y（保持系统窗口与圆环 dimen 尺寸）
     * - 开启「自定义大小与位置」后：按黑圈几何中心对齐，并套用缩放与 dp 偏移
     */
    private fun applyCustomCircleBatteryLayout(api: XposedInterface, controller: Any) {
        if (replaceStatusBarIcon) return
        try {
            refreshLayoutPrefs()
            val batteryLp = ensureBatteryWindowLayoutParams(controller) ?: return
            val blackLp = ensureBlackWindowLayoutParams(api, controller) ?: return

            if (!customLayoutEnabled) {
                applyLegacyAlign(batteryLp, blackLp)
                updateCircleBatteryWindowIfAttached(controller, batteryLp)
                Logger.once(TAG, "align_legacy", "已按黑圈坐标对齐（系统默认大小）")
                return
            }

            val density = resolveDensity(controller)
            val baseSize = minOf(blackLp.width, blackLp.height).coerceAtLeast(1)
            val sizePx = (baseSize * sizeScale / 100f).roundToInt().coerceAtLeast(1)
            val offsetXPx = (offsetXDp * density).roundToInt()
            val offsetYPx = (offsetYDp * density).roundToInt()

            // START / END gravity 下，x 均为「沿重力轴的边距」；同轴缩放用同一公式保持圆心
            batteryLp.gravity = blackLp.gravity
            batteryLp.width = sizePx
            batteryLp.height = sizePx
            batteryLp.x = blackLp.x + (blackLp.width - sizePx) / 2 + offsetXPx
            batteryLp.y = blackLp.y + (blackLp.height - sizePx) / 2 + offsetYPx

            val ringSize = (sizePx * RING_INSET_RATIO).roundToInt().coerceAtLeast(1)
            applyCircleBatteryViewSize(controller, ringSize)
            updateCircleBatteryWindowIfAttached(controller, batteryLp)

            Logger.once(
                TAG,
                "align_custom_layout",
                "已按黑圈对齐环形电量 size=${sizePx}px scale=$sizeScale% offset=(${offsetXDp}dp,${offsetYDp}dp)"
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "环形电量布局对齐失败", e)
        }
    }

    /** 未开启自定义时：只抄黑圈坐标，不改宽高与圆环绘制尺寸 */
    private fun applyLegacyAlign(
        batteryLp: WindowManager.LayoutParams,
        blackLp: WindowManager.LayoutParams
    ) {
        batteryLp.gravity = blackLp.gravity
        batteryLp.x = blackLp.x
        batteryLp.y = blackLp.y
    }

    /**
     * 仅复用系统自身维护的前摄黑圈窗口坐标（含 Cutout 计算），拿不到就放弃干预。
     */
    private fun ensureBlackWindowLayoutParams(api: XposedInterface, controller: Any): WindowManager.LayoutParams? {
        return try {
            (Reflect.getObjectField(controller, "mBlackLpChanged") as? WindowManager.LayoutParams)
                ?: (Reflect.callMethod(api, controller, "initBlackWindowLp") as? WindowManager.LayoutParams)
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureBatteryWindowLayoutParams(controller: Any): WindowManager.LayoutParams? {
        return try {
            Reflect.getObjectField(controller, "mBatteryLpChanged") as? WindowManager.LayoutParams
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveDensity(controller: Any): Float {
        return try {
            val context = Reflect.getObjectField(controller, "mContext") as? Context
            context?.resources?.displayMetrics?.density ?: 3f
        } catch (_: Throwable) {
            3f
        }
    }

    /**
     * 同步缩放 CircleBatteryLottieAnimationView 的布局与绘制矩形（系统在 init 时写死 dimen）。
     */
    private fun applyCircleBatteryViewSize(controller: Any, ringSizePx: Int) {
        val circleView = try {
            Reflect.getObjectField(controller, "mCircleBatteryView") as? View
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val lp = circleView.layoutParams
            if (lp != null) {
                lp.width = ringSizePx
                lp.height = ringSizePx
                circleView.layoutParams = lp
            } else {
                circleView.layoutParams = ViewGroup.LayoutParams(ringSizePx, ringSizePx)
            }
        } catch (_: Throwable) {
            Logger.w(TAG, "调整圆环 View 布局失败")
        }

        val stroke = max(2f, ringSizePx * (6f / 61f))
        try {
            Reflect.setFloatField(circleView, "mWidth", ringSizePx.toFloat())
            Reflect.setFloatField(circleView, "mHeight", ringSizePx.toFloat())
            Reflect.setFloatField(circleView, "mStrokeWidth", stroke)
            Reflect.setObjectField(
                circleView,
                "mRectF",
                RectF(
                    stroke / 2f,
                    stroke / 2f,
                    ringSizePx - stroke / 2f,
                    ringSizePx - stroke / 2f
                )
            )
            (Reflect.getObjectField(circleView, "mPaint") as? Paint)?.strokeWidth = stroke
            (Reflect.getObjectField(circleView, "mBgPaint") as? Paint)?.strokeWidth = stroke
            circleView.requestLayout()
            circleView.invalidate()
        } catch (_: Throwable) {
            Logger.w(TAG, "调整圆环绘制尺寸失败")
        }
    }

    private fun updateCircleBatteryWindowIfAttached(
        controller: Any,
        batteryLp: WindowManager.LayoutParams
    ) {
        try {
            val attached = Reflect.getBooleanField(controller, "mBatteryAttachToWindow")
            if (!attached) return
            val root = Reflect.getObjectField(controller, "mBlackCircleView") as? View ?: return
            if (root.parent == null) return
            val wm = Reflect.getObjectField(controller, "mWindowManager") as? WindowManager
                ?: return
            wm.updateViewLayout(root, batteryLp)
        } catch (_: Throwable) {
            Logger.w(TAG, "updateViewLayout 环形电量窗口失败")
        }
    }

    private fun forceOriginalBatteryViewsVisible(controller: Any) {
        for (fieldName in listOf("mBatteryView", "mKeyguardBatteryView", "mKeyguardBouncerBatteryView")) {
            try {
                (Reflect.getObjectField(controller, fieldName) as? View)?.visibility = View.VISIBLE
            } catch (_: Throwable) {
            }
        }
    }

    private fun drawCircleBattery(view: View, canvas: Canvas) {
        val width = (view.width.takeIf { it > 0 } ?: view.measuredWidth).coerceAtLeast(1)
        val height = (view.height.takeIf { it > 0 } ?: view.measuredHeight).coerceAtLeast(1)
        val circleSize = getStatusBarCircleSize(view).coerceAtMost(height).coerceAtLeast(1)
        val strokeWidth = max(2f, circleSize * 0.12f)
        val halfStroke = strokeWidth / 2f
        val progressColor = getProgressColor(view)
        val level = readIntField(view, "mLastLevel", 0).coerceIn(0, 100)
        val drawLevel = when {
            level > 92 && level < 100 -> 93f
            else -> level.toFloat()
        }

        val circleLeft = if (textMode == TEXT_MODE_SIDE) {
            0f
        } else {
            (width - circleSize) / 2f
        }
        val circleTop = (height - circleSize) / 2f
        val rect = RectF(
            circleLeft + halfStroke,
            circleTop + halfStroke,
            circleLeft + circleSize - halfStroke,
            circleTop + circleSize - halfStroke
        )

        backgroundPaint.strokeWidth = strokeWidth
        backgroundPaint.color = getBackgroundColor(progressColor)
        backgroundPaint.alpha = 48

        progressPaint.strokeWidth = strokeWidth
        progressPaint.color = progressColor
        progressPaint.alpha = 255

        canvas.drawArc(rect, -90f, 360f, false, backgroundPaint)
        canvas.drawArc(rect, -90f, drawLevel * 3.6f, false, progressPaint)

        when (textMode) {
            TEXT_MODE_INSIDE -> drawInsideBatteryText(canvas, view, rect, level, progressColor)
            TEXT_MODE_SIDE -> drawSideBatteryText(canvas, view, circleSize, level, progressColor)
        }
    }

    private fun drawInsideBatteryText(canvas: Canvas, view: View, rect: RectF, level: Int, color: Int) {
        val text = level.toString()
        textPaint.textAlign = Paint.Align.CENTER
        trySetTypefaceSafe(textPaint, getBatteryTextTypeface(view))
        textPaint.textSize = getInsideTextSize(view, rect, text)
        textPaint.color = color
        textPaint.alpha = 255
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    private fun drawSideBatteryText(canvas: Canvas, view: View, circleSize: Int, level: Int, color: Int) {
        val text = level.toString()
        textPaint.textAlign = Paint.Align.LEFT
        trySetTypefaceSafe(textPaint, getBatteryTextTypeface(view))
        textPaint.textSize = max(8f, circleSize * 0.68f)
        textPaint.color = color
        textPaint.alpha = 255
        val x = circleSize + getSideTextGap(view).toFloat()
        val centerY = (view.height.takeIf { it > 0 } ?: view.measuredHeight).coerceAtLeast(circleSize) / 2f
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun applyTextMode(view: View) {
        val percentView = getBatteryPercentView(view) ?: return
        when (textMode) {
            TEXT_MODE_NONE, TEXT_MODE_INSIDE -> percentView.visibility = View.GONE
            TEXT_MODE_SIDE -> percentView.visibility = View.GONE
        }
    }

    private fun getBatteryPercentView(view: View): TextView? {
        return try {
            Reflect.getObjectField(view, "mBatteryPercentView") as? TextView
        } catch (_: Throwable) {
            null
        }
    }

    private fun getProgressColor(view: View): Int {
        val level = readIntField(view, "mLastLevel", 0)
        val charging = readBooleanField(view, "mCharging", false)
        val plugged = readBooleanField(view, "mLastPlugged", false)
        val lowPowerMode = readBooleanField(view, "mLowPowerMode", false)
        if (charging || plugged) return COLOR_CHARGING
        if (lowPowerMode) return COLOR_LOW_POWER
        if (level in 0..9) return COLOR_CRITICAL

        val tint = readIntField(view, "mFilterColor", COLOR_WHITE)
        return if (tint == 0) COLOR_WHITE else tint
    }

    private fun getBackgroundColor(progressColor: Int): Int {
        return if (progressColor == COLOR_WHITE) COLOR_WHITE else COLOR_BLACK
    }

    private fun getStatusBarCircleSize(view: View): Int {
        val res = view.resources
        val dimenId = res.getIdentifier(
            "status_bar_battery_icon_height",
            "dimen",
            "com.android.systemui"
        )
        val fromResource = if (dimenId != 0) {
            runCatching { res.getDimensionPixelSize(dimenId) }.getOrDefault(0)
        } else {
            0
        }
        val densityFallback = (14f * res.displayMetrics.density + 0.5f).toInt()
        return max(fromResource, densityFallback).coerceAtLeast(1)
    }

    private fun getSideTextWidth(view: View): Int {
        val circleSize = getStatusBarCircleSize(view)
        val level = readIntField(view, "mLastLevel", 100).coerceIn(0, 100)
        trySetTypefaceSafe(textPaint, getBatteryTextTypeface(view))
        textPaint.textSize = max(8f, circleSize * 0.68f)
        val width = textPaint.measureText(level.toString()).toInt()
        return width.coerceAtLeast((circleSize * 0.75f).toInt())
    }

    private fun getInsideTextSize(view: View, rect: RectF, text: String): Float {
        val percentTextSize = getBatteryPercentView(view)?.textSize ?: 0f
        val twoDigitSize = max(max(8f, rect.height() * 0.50f), percentTextSize)
            .coerceAtMost(rect.height() * 0.58f)
        if (text.length <= 2) return twoDigitSize

        textPaint.textSize = twoDigitSize
        val maxTextWidth = rect.width() * 0.78f
        val measuredWidth = textPaint.measureText(text).coerceAtLeast(1f)
        return (twoDigitSize * maxTextWidth / measuredWidth)
            .coerceAtLeast(6f)
            .coerceAtMost(twoDigitSize)
    }

    private fun getBatteryTextTypeface(view: View): Typeface {
        return getBatteryPercentView(view)?.typeface ?: Typeface.DEFAULT
    }

    private fun getSideTextGap(view: View): Int {
        return max(1, (getStatusBarCircleSize(view) * 0.22f).toInt())
    }

    private fun isTargetBatteryView(view: View): Boolean {
        return targetBatteryViews[view] == true
    }

    private fun readIntField(instance: Any, fieldName: String, defaultValue: Int): Int {
        return try {
            Reflect.getIntField(instance, fieldName)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun readBooleanField(instance: Any, fieldName: String, defaultValue: Boolean): Boolean {
        return try {
            Reflect.getBooleanField(instance, fieldName)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun findNoArgVoidMethod(clazz: Class<*>, methodName: String): Method? {
        return clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == methodName &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }
    }

    private fun findNoArgMethod(clazz: Class<*>, methodName: String): Method? {
        return clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == methodName &&
                method.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }
    }

    private fun findClass(classLoader: ClassLoader, className: String): Class<*>? {
        return try {
            Reflect.findClass(className, classLoader)
        } catch (e: Throwable) {
            Logger.w(TAG, "未找到类 $className")
            null
        }
    }
}
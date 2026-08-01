package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

object ForceCircleBatteryHook : FeatureHook {

    private const val FEATURE_FORCE = "force_camera_circle_battery"
    private const val FEATURE_REPLACE_STATUS_BAR = "circle_battery_status_bar_icon"
    private const val HOOK_NAME = "ForceCircleBattery"

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

    @Volatile
    private var replaceStatusBarIcon = false

    @Volatile
    private var textMode = TEXT_MODE_NONE

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
            Logger.w(HOOK_NAME, "Paint(ANTI_ALIAS) failed, retry bare: ${t.message}")
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
            Logger.w(HOOK_NAME, "setTypeface failed: ${t.message}")
        }
    }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_FORCE)) return

        replaceStatusBarIcon = XposedPrefs.isFeatureEnabled(
            lpparam,
            packageName,
            FEATURE_REPLACE_STATUS_BAR
        )
        textMode = XposedPrefs.getFeatureValue(
            lpparam,
            packageName,
            FEATURE_REPLACE_STATUS_BAR,
            TEXT_MODE_NONE
        ).coerceIn(TEXT_MODE_NONE, TEXT_MODE_SIDE)

        hookCameraStateController(lpparam)
        hookBatteryMeterView(lpparam)
    }

    private fun hookCameraStateController(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = findClass(lpparam.classLoader, CAMERA_STATE_CONTROLLER) ?: return

        hookBooleanNoArg(clazz, "isUserRequestCircleBattery") {
            !replaceStatusBarIcon
        }

        if (replaceStatusBarIcon) {
            hookBooleanOneBooleanArg(clazz, "shouldShowCircleBatteryView") { false }
            hookBlockShowCircleWindow(clazz)
            hookForceOriginalBatteryVisible(clazz)
        } else {
            hookAlignCircleBatteryWindow(clazz)
        }

        try {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyCircleBatteryMode(param.thisObject)
                }
            })
            Logger.i(HOOK_NAME, "CameraStateController Hook 完成, replace=$replaceStatusBarIcon")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "CameraStateController constructor Hook 失败", e)
        }
    }

    private fun hookBatteryMeterView(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = findClass(lpparam.classLoader, FLYME_BATTERY_METER_VIEW) ?: return

        hookBooleanNoArg(clazz, "isShowingCircleBattery") {
            !replaceStatusBarIcon
        }
        hookMarkTargetBatteryView(clazz)

        if (replaceStatusBarIcon) {
            hookDrawStatusBarCircle(clazz)
            hookMeasureStatusBarCircle(clazz)
            hookKeepStatusBarCircleVisible(clazz)
        }

        Logger.i(HOOK_NAME, "FlymeBatteryMeterView Hook 完成, replace=$replaceStatusBarIcon")
    }

    private fun hookBooleanNoArg(
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
            Logger.w(HOOK_NAME, "未找到 $methodName()")
            return
        }
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = resultProvider()
            }
        })
    }

    private fun hookBooleanOneBooleanArg(
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
            Logger.w(HOOK_NAME, "未找到 $methodName(Boolean)")
            return
        }
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = resultProvider()
            }
        })
    }

    private fun hookBlockShowCircleWindow(clazz: Class<*>) {
        val method = findNoArgVoidMethod(clazz, "showCircleBatteryIfNecessary") ?: run {
            Logger.w(HOOK_NAME, "未找到 showCircleBatteryIfNecessary()")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                hideCircleWindow(param.thisObject)
                param.result = null
            }
        })
    }

    private fun hookAlignCircleBatteryWindow(clazz: Class<*>) {
        val method = findNoArgMethod(clazz, "initBatteryWindowLp") ?: run {
            Logger.w(HOOK_NAME, "未找到 initBatteryWindowLp()")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val lp = param.result as? WindowManager.LayoutParams ?: return
                alignCircleBatteryWindow(param.thisObject, lp)
            }
        })
    }

    private fun hookForceOriginalBatteryVisible(clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == "updateBatteryViewVisibility" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: run {
            Logger.w(HOOK_NAME, "未找到 updateBatteryViewVisibility(Boolean)")
            return
        }
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                forceOriginalBatteryViewsVisible(param.thisObject)
            }
        })
    }

    private fun hookMarkTargetBatteryView(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "setBatteryPercentView",
                TextView::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val scene = param.args.getOrNull(1) as? String ?: return
                        if (scene !in targetScenes) return
                        targetBatteryViews[param.thisObject] = true
                        (param.thisObject as? View)?.let { view ->
                            view.visibility = View.VISIBLE
                            applyTextMode(view)
                            view.requestLayout()
                            view.invalidate()
                        }
                        Logger.once(HOOK_NAME, "已标记 $scene 电池图标为环形绘制目标")
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "setBatteryPercentView Hook 失败", e)
        }
    }

    private fun hookDrawStatusBarCircle(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onDraw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isTargetBatteryView(view)) return
                        val canvas = param.args.getOrNull(0) as? Canvas ?: return
                        drawCircleBattery(view, canvas)
                        param.result = null
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "onDraw Hook 失败", e)
        }
    }

    private fun hookMeasureStatusBarCircle(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isTargetBatteryView(view)) return
                        val size = getStatusBarCircleSize(view)
                        val width = if (textMode == TEXT_MODE_SIDE) {
                            size + getSideTextWidth(view) + getSideTextGap(view)
                        } else {
                            size
                        }
                        XposedHelpers.callMethod(view, "setMeasuredDimension", width, size)
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "onMeasure Hook 失败", e)
        }
    }

    private fun hookKeepStatusBarCircleVisible(clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull { method ->
            !method.isSynthetic &&
                method.name == "apply" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        } ?: run {
            Logger.w(HOOK_NAME, "未找到 apply(Boolean)")
            return
        }
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!isTargetBatteryView(view)) return
                view.visibility = View.VISIBLE
                applyTextMode(view)
                view.requestLayout()
                view.invalidate()
            }
        })
    }

    private fun applyCircleBatteryMode(controller: Any) {
        try {
            XposedHelpers.setBooleanField(controller, "mShowCircleBattery", !replaceStatusBarIcon)
        } catch (_: Throwable) {
        }

        if (replaceStatusBarIcon) {
            hideCircleWindow(controller)
            forceOriginalBatteryViewsVisible(controller)
        } else {
            try {
                XposedHelpers.callMethod(controller, "updateCircleBatteryWindowVisibility")
            } catch (e: Throwable) {
                Logger.w(HOOK_NAME, "刷新环形电量窗口失败: ${e.message}")
            }
        }
    }

    private fun hideCircleWindow(controller: Any) {
        try {
            XposedHelpers.setBooleanField(controller, "mShowCircleBattery", false)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(controller, "hideCircleBatteryIfNecessary")
        } catch (_: Throwable) {
        }
    }

    private fun alignCircleBatteryWindow(controller: Any, batteryLp: WindowManager.LayoutParams) {
        try {
            val blackLp = ensureBlackWindowLayoutParams(controller) ?: return
            batteryLp.gravity = blackLp.gravity
            batteryLp.x = blackLp.x
            batteryLp.y = blackLp.y
            Logger.once(HOOK_NAME, "已复用前摄黑圈坐标对齐环形电量")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "环形电量坐标对齐失败", e)
        }
    }

    private fun ensureBlackWindowLayoutParams(controller: Any): WindowManager.LayoutParams? {
        return try {
            (XposedHelpers.getObjectField(controller, "mBlackLpChanged") as? WindowManager.LayoutParams)
                ?: (XposedHelpers.callMethod(controller, "initBlackWindowLp") as? WindowManager.LayoutParams)
        } catch (_: Throwable) {
            buildCutoutAlignedLayoutParams(controller)
        }
    }

    private fun buildCutoutAlignedLayoutParams(controller: Any): WindowManager.LayoutParams? {
        val context = getControllerContext(controller) ?: return null
        val res = context.resources
        val width = getDimen(res, "black_circle_width", "battery_circle_width") ?: return null
        val height = getDimen(res, "black_circle_height", "battery_circle_height") ?: width
        val lp = WindowManager.LayoutParams(width, height, 2006, 824, -3)
        lp.flags = lp.flags or 16777216
        lp.gravity = 8388659
        lp.setTitle("CameraCircle")

        val cutoutRect = runCatching {
            val cutout = context.display?.cutout ?: return@runCatching null
            val path = cutout.cutoutPath ?: return@runCatching null
            RectF().also { path.computeBounds(it, false) }
        }.getOrNull()

        if (cutoutRect != null && isValidCenterCutout(cutoutRect)) {
            lp.x = ((cutoutRect.left + cutoutRect.right) / 2f).toInt() - width / 2
            lp.y = ((cutoutRect.top + cutoutRect.bottom) / 2f).toInt() - height / 2
        } else {
            val screenWidth = res.displayMetrics.widthPixels
            lp.x = screenWidth / 2 - width / 2
            lp.y = getDimen(res, "black_circle_y") ?: 0
        }
        return lp
    }

    private fun isValidCenterCutout(rect: RectF): Boolean {
        return rect.left > 0f &&
            rect.right > 0f &&
            rect.top > 0f &&
            rect.bottom > 0f &&
            rect.right > rect.left &&
            rect.bottom > rect.top &&
            kotlin.math.abs((rect.right - rect.left) - (rect.bottom - rect.top)) < 1f
    }

    private fun getControllerContext(controller: Any): Context? {
        return try {
            XposedHelpers.getObjectField(controller, "mContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun getDimen(res: android.content.res.Resources, vararg names: String): Int? {
        for (name in names) {
            val id = res.getIdentifier(name, "dimen", "com.android.systemui")
            if (id == 0) continue
            val value = runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0)
            if (value > 0) return value
        }
        return null
    }

    private fun forceOriginalBatteryViewsVisible(controller: Any) {
        for (fieldName in listOf("mBatteryView", "mKeyguardBatteryView", "mKeyguardBouncerBatteryView")) {
            try {
                (XposedHelpers.getObjectField(controller, fieldName) as? View)?.visibility = View.VISIBLE
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
            XposedHelpers.getObjectField(view, "mBatteryPercentView") as? TextView
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
            XposedHelpers.getIntField(instance, fieldName)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun readBooleanField(instance: Any, fieldName: String, defaultValue: Boolean): Boolean {
        return try {
            XposedHelpers.getBooleanField(instance, fieldName)
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
            XposedHelpers.findClass(className, classLoader)
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "未找到类 $className", e)
            null
        }
    }
}

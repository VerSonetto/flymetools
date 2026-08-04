package com.karen.flymetool.ui.component.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 前摄环形电量自定义布局的悬浮预览。
 * 以 DisplayCutout / SystemUI black_circle dimen 估算孔位，拖动滑条时实时更新。
 */
class CircleBatteryPreviewOverlay(private val context: Context) {

    companion object {
        private const val RING_INSET_RATIO = 0.68f
        private const val COLOR_RING = -14626797 // 充电绿，预览辨识度高
        private const val COLOR_BG = -1
        private const val PREVIEW_LEVEL = 75f
    }

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var previewView: RingPreviewView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun show(sizeScale: Int, offsetXDp: Int, offsetYDp: Int) {
        if (!canDrawOverlays()) return
        ensureWindow()
        update(sizeScale, offsetXDp, offsetYDp)
    }

    fun update(sizeScale: Int, offsetXDp: Int, offsetYDp: Int) {
        val view = previewView ?: return
        val lp = layoutParams ?: return
        val metrics = resolveAnchorMetrics()
        val density = context.resources.displayMetrics.density
        val baseSize = metrics.baseSizePx.coerceAtLeast(1)
        val windowSize = (baseSize * sizeScale.coerceIn(50, 150) / 100f).roundToInt().coerceAtLeast(1)
        val ringSize = (windowSize * RING_INSET_RATIO).roundToInt().coerceAtLeast(1)
        val offsetXPx = (offsetXDp * density).roundToInt()
        val offsetYPx = (offsetYDp * density).roundToInt()

        lp.width = windowSize
        lp.height = windowSize
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = metrics.centerX - windowSize / 2 + offsetXPx
        lp.y = metrics.centerY - windowSize / 2 + offsetYPx

        view.setRingSize(ringSize)
        try {
            if (view.parent != null) {
                windowManager.updateViewLayout(view, lp)
            } else {
                windowManager.addView(view, lp)
            }
        } catch (_: Throwable) {
            dismiss()
        }
    }

    fun dismiss() {
        val view = previewView ?: return
        try {
            if (view.parent != null) {
                windowManager.removeViewImmediate(view)
            }
        } catch (_: Throwable) {
        }
        previewView = null
        layoutParams = null
    }

    @SuppressLint("WrongConstant")
    private fun ensureWindow() {
        if (previewView != null && layoutParams != null) return
        val view = RingPreviewView(context.applicationContext)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "FlymeToolCircleBatteryPreview"
            gravity = Gravity.TOP or Gravity.START
        }
        previewView = view
        layoutParams = lp
    }

    private data class AnchorMetrics(val centerX: Int, val centerY: Int, val baseSizePx: Int)

    private fun resolveAnchorMetrics(): AnchorMetrics {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val blackFromSysUi = readSystemUiBlackCircle()
        val cutout = resolveCutoutAnchor(blackFromSysUi?.sizePx)
        if (cutout != null) return cutout

        val base = blackFromSysUi?.sizePx
            ?: (30f * density + 0.5f).toInt()
        val y = blackFromSysUi?.yPx
            ?: (0.9f * density + 0.5f).toInt()
        return AnchorMetrics(
            centerX = screenWidth / 2,
            centerY = y + base / 2,
            baseSizePx = base
        )
    }

    private data class BlackCircleDimen(val sizePx: Int, val yPx: Int)

    private fun readSystemUiBlackCircle(): BlackCircleDimen? {
        return try {
            val res = context.packageManager.getResourcesForApplication("com.android.systemui")
            val widthId = res.getIdentifier("black_circle_width", "dimen", "com.android.systemui")
            val yId = res.getIdentifier("black_circle_y", "dimen", "com.android.systemui")
            if (widthId == 0) return null
            val size = res.getDimensionPixelSize(widthId)
            val y = if (yId != 0) res.getDimensionPixelSize(yId) else 0
            BlackCircleDimen(size, y)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 对齐系统 setBlackCircleXAndY：取近似正方形的 Cutout 边界中心。
     */
    private fun resolveCutoutAnchor(preferredSize: Int?): AnchorMetrics? {
        return try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return null
            val cutout = display.cutout ?: return null
            val path = cutout.cutoutPath ?: return null
            val bounds = RectF()
            path.computeBounds(bounds, false)
            if (bounds.left <= 0f || bounds.right <= 0f ||
                bounds.top <= 0f || bounds.bottom <= 0f ||
                bounds.right <= bounds.left || bounds.bottom <= bounds.top
            ) {
                return null
            }
            val w = bounds.width()
            val h = bounds.height()
            if (abs(w - h) >= 1f) return null
            val size = preferredSize ?: max(w, h).roundToInt().coerceAtLeast(1)
            AnchorMetrics(
                centerX = ((bounds.left + bounds.right) / 2f).roundToInt(),
                centerY = ((bounds.top + bounds.bottom) / 2f).roundToInt(),
                baseSizePx = size
            )
        } catch (_: Throwable) {
            null
        }
    }

    private class RingPreviewView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = COLOR_BG
            alpha = 48
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = COLOR_RING
            alpha = 255
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_RING
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val rect = RectF()
        private var ringSize = 1

        fun setRingSize(sizePx: Int) {
            if (ringSize == sizePx) return
            ringSize = sizePx.coerceAtLeast(1)
            val stroke = max(2f, ringSize * (6f / 61f))
            bgPaint.strokeWidth = stroke
            ringPaint.strokeWidth = stroke
            textPaint.textSize = max(8f, ringSize * 0.42f)
            invalidate()
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> ringSize
                else -> MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            }
            val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> ringSize
                else -> MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            }
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = bgPaint.strokeWidth
            val half = stroke / 2f
            val left = (width - ringSize) / 2f + half
            val top = (height - ringSize) / 2f + half
            rect.set(left, top, left + ringSize - stroke, top + ringSize - stroke)
            canvas.drawArc(rect, -90f, 360f, false, bgPaint)
            canvas.drawArc(rect, -90f, PREVIEW_LEVEL * 3.6f, false, ringPaint)
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(PREVIEW_LEVEL.toInt().toString(), rect.centerX(), baseline, textPaint)
        }
    }
}

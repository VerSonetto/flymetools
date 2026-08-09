package com.karen.flymetool.ui.component.feature

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.karen.flymetool.util.NotificationCardBlurMath
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 通知 / 媒体卡片模糊实时预览。
 *
 * 优先反射 ViewRootImpl.createBackgroundBlurDrawable（与 SystemUI 同路径）；
 * 失败再软件模糊壁纸底图 + 罩色。
 */
@Composable
fun NotificationCardBlurPreview(
    intensity: Int,
    opacity: Int,
    beautify: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val night = isSystemInDarkTheme()
    val cornerPx = with(density) { 16.dp.toPx() }
    val host = remember { NotificationBlurPreviewHost(context.applicationContext) }
    var usedSystemBlur by remember { mutableStateOf<Boolean?>(null) }

    val radius = NotificationCardBlurMath.previewRadiusPx(intensity, beautify)
    val mask = NotificationCardBlurMath.previewMaskColor(night, intensity, opacity, beautify)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "实时预览（半径 $radius · 与系统 Live 同算法）",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        AndroidView(
            factory = { host },
            update = { view ->
                val ok = view.applyParams(
                    night = night,
                    blurRadius = radius,
                    cornerRadiusPx = cornerPx,
                    maskColor = mask
                )
                // update 在 composition 阶段，post 出去再改 state，避免重组抖动
                if (usedSystemBlur != ok) {
                    view.post {
                        if (usedSystemBlur != ok) usedSystemBlur = ok
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val status = when (usedSystemBlur) {
            true -> "当前：系统 BackgroundBlurDrawable（跨窗口真实模糊）"
            false -> "当前：软件模糊兜底（跨窗口模糊不可用时）"
            null -> "当前：初始化中…"
        }
        Text(
            text = status,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }
}

/**
 * 结构：壁纸层 + 两张圆角毛玻璃卡（通知 / 媒体示意）。
 */
internal class NotificationBlurPreviewHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val wallpaperView = View(context).also {
        it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val cardsColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }

    private val notificationCard = BlurCardView(context).apply {
        setLabel("测试", "测试 · 预览效果仅供参考。")
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).also { it.bottomMargin = dp(10) }
    }

    private val mediaCard = BlurCardView(context).apply {
        setLabel("效果预览", "预览效果仅供参考，实际使用基本上和预览效果相差很大")
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    private var wallpaperBitmap: Bitmap? = null
    private var lastNight: Boolean? = null
    private var lastRadius = -1
    private var lastCorner = -1f
    private var lastMask = 0
    private var lastSystemOk: Boolean? = null
    private var pendingAttachApply = false
    private var pendingNight = false
    private var pendingRadius = 0
    private var pendingCorner = 0f
    private var pendingMask = 0

    private val attachListener = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (pendingAttachApply) {
                pendingAttachApply = false
                applyParams(pendingNight, pendingRadius, pendingCorner, pendingMask)
            }
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    }

    init {
        clipToOutline = true
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xFF1A1A1E.toInt())
        }
        addView(wallpaperView)
        cardsColumn.addView(notificationCard)
        cardsColumn.addView(mediaCard)
        addView(cardsColumn)
        addOnAttachStateChangeListener(attachListener)
    }

    /**
     * @return true = 系统跨窗口模糊生效
     */
    fun applyParams(
        night: Boolean,
        blurRadius: Int,
        cornerRadiusPx: Float,
        maskColor: Int
    ): Boolean {
        if (!isAttachedToWindow) {
            pendingAttachApply = true
            pendingNight = night
            pendingRadius = blurRadius
            pendingCorner = cornerRadiusPx
            pendingMask = maskColor
            return lastSystemOk ?: false
        }

        if (lastNight != night) {
            lastNight = night
            ensureWallpaper(night)
        }

        if (lastRadius == blurRadius &&
            lastCorner == cornerRadiusPx &&
            lastMask == maskColor &&
            lastSystemOk != null
        ) {
            return lastSystemOk!!
        }

        lastRadius = blurRadius
        lastCorner = cornerRadiusPx
        lastMask = maskColor

        val sysNotif = notificationCard.applySystemBlur(blurRadius, cornerRadiusPx, maskColor)
        val sysMedia = mediaCard.applySystemBlur(blurRadius, cornerRadiusPx, maskColor)
        val systemOk = sysNotif && sysMedia
        lastSystemOk = systemOk

        if (systemOk) {
            wallpaperView.setRenderEffect(null)
            wallpaperView.alpha = 1f
            notificationCard.clearSoftwareFallback()
            mediaCard.clearSoftwareFallback()
        } else {
            applySoftwareFallback(blurRadius, cornerRadiusPx, maskColor)
        }
        invalidate()
        return systemOk
    }

    private fun applySoftwareFallback(
        blurRadius: Int,
        cornerRadiusPx: Float,
        maskColor: Int
    ) {
        SystemBackgroundBlur.clear(notificationCard)
        SystemBackgroundBlur.clear(mediaCard)
        // RenderEffect 与 SF BackgroundBlur 刻度不同：
        // 系统默认 180 ≈ 预览中等糊，系数经验标定，拖滑条仍单调可辨。
        val effectRadius = when {
            blurRadius <= 0 -> 0f
            else -> (8f + blurRadius * 0.42f).coerceIn(0f, 160f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && effectRadius > 0.5f) {
            wallpaperView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    effectRadius,
                    effectRadius,
                    Shader.TileMode.CLAMP
                )
            )
        } else {
            wallpaperView.setRenderEffect(null)
        }
        // 软件路径：卡本身半透明罩色，透过罩色看被糊的壁纸层
        notificationCard.applySoftwareMask(cornerRadiusPx, maskColor)
        mediaCard.applySoftwareMask(cornerRadiusPx, maskColor)
    }

    private fun ensureWallpaper(night: Boolean) {
        val w = (resources.displayMetrics.widthPixels * 0.9f).roundToInt().coerceIn(480, 1080)
        val h = (w * 0.55f).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c0 = if (night) 0xFF1B2430.toInt() else 0xFF6FA8FF.toInt()
        val c1 = if (night) 0xFF3A2A4A.toInt() else 0xFFFFB4A2.toInt()
        val c2 = if (night) 0xFF0E151C.toInt() else 0xFFE8F1FF.toInt()
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = if (night) 0x66FF8A65.toInt() else 0x88FFFFFF.toInt()
        canvas.drawCircle(w * 0.22f, h * 0.35f, w * 0.18f, paint)
        paint.color = if (night) 0x664FC3F7.toInt() else 0x66FF7043.toInt()
        canvas.drawCircle(w * 0.78f, h * 0.62f, w * 0.22f, paint)
        paint.color = if (night) 0x55CE93D8.toInt() else 0x554CAF50.toInt()
        canvas.drawRoundRect(
            w * 0.35f, h * 0.15f, w * 0.95f, h * 0.48f,
            dp(20).toFloat(), dp(20).toFloat(), paint
        )
        wallpaperBitmap?.recycle()
        wallpaperBitmap = bmp
        wallpaperView.background = BitmapDrawable(resources, bmp)
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        SystemBackgroundBlur.clear(notificationCard)
        SystemBackgroundBlur.clear(mediaCard)
        wallpaperView.setRenderEffect(null)
        // 允许再次 attach 后重建
        lastSystemOk = null
        lastRadius = -1
    }
}

/** 单张示意卡 */
private class BlurCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView: TextView
    private val bodyView: TextView
    private var softwareMask: GradientDrawable? = null

    init {
        val padH = dp(14)
        val padV = dp(12)
        setPadding(padH, padV, padH, padV)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            paint.isFakeBoldText = true
        }
        bodyView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(titleView)
        col.addView(bodyView)
        addView(col)
        minimumHeight = dp(64)
    }

    fun setLabel(title: String, body: String) {
        titleView.text = title
        bodyView.text = body
        refreshTextColors()
    }

    fun applySystemBlur(blurRadius: Int, cornerRadiusPx: Float, maskColor: Int): Boolean {
        val ok = SystemBackgroundBlur.apply(
            view = this,
            blurRadius = blurRadius,
            cornerRadiusPx = cornerRadiusPx,
            color = maskColor,
            alpha = 255
        )
        if (ok) {
            softwareMask = null
            refreshTextColors(maskColor)
        }
        return ok
    }

    fun applySoftwareMask(cornerRadiusPx: Float, maskColor: Int) {
        val gd = softwareMask ?: GradientDrawable().also { softwareMask = it }
        gd.cornerRadius = cornerRadiusPx
        gd.setColor(maskColor)
        background = gd
        refreshTextColors(maskColor)
    }

    fun clearSoftwareFallback() {
        softwareMask = null
    }

    private fun refreshTextColors(maskColor: Int? = null) {
        val nightUi = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val lightText = if (maskColor != null) {
            val r = AndroidColor.red(maskColor)
            val g = AndroidColor.green(maskColor)
            val b = AndroidColor.blue(maskColor)
            (r * 299 + g * 587 + b * 114) / 1000 < 140
        } else {
            nightUi
        }
        val primary = if (lightText) 0xF0FFFFFF.toInt() else 0xF0121212.toInt()
        val secondary = if (lightText) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
        titleView.setTextColor(primary)
        bodyView.setTextColor(secondary)
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }
}

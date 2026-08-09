package com.karen.flymetool.ui.component.feature

import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Method

/**
 * 反射调用 ViewRootImpl.createBackgroundBlurDrawable，与 Flyme SystemUI / frost 同路径。
 * 失败返回 false，由调用方走软件模糊兜底。
 */
internal object SystemBackgroundBlur {

    @Volatile
    private var createResolved = false

    @Volatile
    private var createMethod: Method? = null

    @Volatile
    private var setBlurRadius: Method? = null

    @Volatile
    private var setCornerRadius: Method? = null

    @Volatile
    private var setClipCornerRadius: Method? = null

    @Volatile
    private var setClipCornerRadius4: Method? = null

    @Volatile
    private var setColor: Method? = null

    @Volatile
    private var setAlpha: Method? = null

    @Volatile
    private var drawableClassName: String? = null

    fun isBackgroundBlurDrawable(drawable: Drawable?): Boolean {
        val name = drawable?.javaClass?.name ?: return false
        return name.endsWith("BackgroundBlurDrawable") || name == drawableClassName
    }

    /**
     * 在 view 已 attach 时创建或更新跨窗口模糊背景。
     * @param cornerRadiusPx 圆角；优先 clipCorner（与通知 Live 一致）
     */
    fun apply(
        view: View,
        blurRadius: Int,
        cornerRadiusPx: Float,
        color: Int,
        alpha: Int = 255
    ): Boolean {
        if (!view.isAttachedToWindow) return false
        return try {
            val existing = view.background
            if (isBackgroundBlurDrawable(existing)) {
                configure(existing!!, blurRadius, cornerRadiusPx, color, alpha)
                existing.invalidateSelf()
                view.invalidate()
                true
            } else {
                val created = create(view) ?: return false
                configure(created, blurRadius, cornerRadiusPx, color, alpha)
                view.background = created
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun clear(view: View) {
        try {
            if (isBackgroundBlurDrawable(view.background)) {
                view.background = null
            }
        } catch (_: Throwable) {
        }
    }

    private fun create(view: View): Drawable? {
        val root = getViewRootImpl(view) ?: return null
        val creator = resolveCreateMethod(root.javaClass) ?: return null
        val drawable = creator.invoke(root) as? Drawable ?: return null
        drawableClassName = drawable.javaClass.name
        cacheDrawableMethods(drawable.javaClass)
        return drawable
    }

    private fun getViewRootImpl(view: View): Any? {
        return try {
            val m = View::class.java.getDeclaredMethod("getViewRootImpl")
            m.isAccessible = true
            m.invoke(view)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCreateMethod(viewRootClass: Class<*>): Method? {
        if (createResolved) return createMethod
        synchronized(this) {
            if (createResolved) return createMethod
            createMethod = try {
                viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable").also {
                    it.isAccessible = true
                }
            } catch (_: Throwable) {
                null
            }
            createResolved = true
            return createMethod
        }
    }

    private fun cacheDrawableMethods(clazz: Class<*>) {
        if (setBlurRadius != null) return
        val intT = Int::class.javaPrimitiveType!!
        val floatT = Float::class.javaPrimitiveType!!
        setBlurRadius = clazz.methodOrNull("setBlurRadius", intT)
        setCornerRadius = clazz.methodOrNull("setCornerRadius", floatT)
        setClipCornerRadius = clazz.methodOrNull("setClipCornerRadius", floatT)
        setClipCornerRadius4 = clazz.methodOrNull(
            "setClipCornerRadius",
            floatT,
            floatT,
            floatT,
            floatT
        )
        setColor = clazz.methodOrNull("setColor", intT)
        setAlpha = clazz.methodOrNull("setAlpha", intT)
    }

    private fun configure(
        drawable: Drawable,
        blurRadius: Int,
        cornerRadiusPx: Float,
        color: Int,
        alpha: Int
    ) {
        cacheDrawableMethods(drawable.javaClass)
        setBlurRadius?.invoke(drawable, blurRadius.coerceAtLeast(0))
        if (cornerRadiusPx >= 0f) {
            // 通知 Live：corner=-1，clipCorner=radius
            when {
                setClipCornerRadius != null ->
                    setClipCornerRadius!!.invoke(drawable, cornerRadiusPx)
                setClipCornerRadius4 != null ->
                    setClipCornerRadius4!!.invoke(
                        drawable,
                        cornerRadiusPx,
                        cornerRadiusPx,
                        cornerRadiusPx,
                        cornerRadiusPx
                    )
                else -> setCornerRadius?.invoke(drawable, cornerRadiusPx)
            }
        }
        setColor?.invoke(drawable, color)
        setAlpha?.invoke(drawable, alpha.coerceIn(0, 255))
    }

    private fun Class<*>.methodOrNull(name: String, vararg types: Class<*>): Method? {
        return try {
            getDeclaredMethod(name, *types).also { it.isAccessible = true }
        } catch (_: Throwable) {
            try {
                getMethod(name, *types)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

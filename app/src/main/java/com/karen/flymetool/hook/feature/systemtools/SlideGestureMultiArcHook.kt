package com.karen.flymetool.hook.feature.systemtools

import android.content.res.Resources
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 系统界面工具：斜滑小窗快捷菜单多圆弧。
 *
 * 原版 AppLauncherWindow 只取 6 个快捷项并额外追加「更多应用」。这里保持第一条圆弧
 * 的原版语义：6 个快捷项 + 更多应用；新增的第 2~4 条圆弧只承载额外快捷项，并向
 * 屏幕底角内侧收拢。
 */
object SlideGestureMultiArcHook : FeatureHook {

    private const val TAG = "SlideGestureMultiArc"
    private const val FEATURE_KEY = "slide_gesture_multi_arc"
    private const val TARGET_PACKAGE = "com.flyme.systemuitools"

    private const val APP_LAUNCHER_WINDOW =
        "com.flyme.systemuitools.windowmode.views.AppLauncherWindow"
    private const val GESTURE_APP_LAUNCHER =
        "com.flyme.systemuitools.windowmode.widget.GestureAppLauncher"

    /** 每条圆弧可放的快捷应用数；第一条另有 1 个「更多应用」入口。 */
    private val APP_CAPACITY_PER_ARC = intArrayOf(6, 5, 4, 3)
    /** 实际 View 数容量；第一条 = 6 个快捷应用 + 更多应用。 */
    private val VIEW_CAPACITY_PER_ARC = intArrayOf(7, 5, 4, 3)

    private var prefsPackage: String = TARGET_PACKAGE
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null
    private var launchItemConstructor: Constructor<*>? = null
    private var layoutParamFields: ArcLayoutFields? = null

    private data class ArcLayoutFields(
        val angle: Field,
        val radiusMin: Field,
        val radiusMax: Field,
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        prefsPackage = packageName
        loadParam = lpparam

        hookLauncherItems(lpparam)
        hookArcLayout(lpparam)

        Logger.i(TAG, "Loaded, arcCount=${readArcCount()}")
    }

    private fun hookLauncherItems(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(APP_LAUNCHER_WINDOW, lpparam.classLoader)
            val method = clazz.declaredMethods.firstOrNull { method ->
                List::class.java.isAssignableFrom(method.returnType) &&
                    method.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.declaredAnnotations.isEmpty()
            }

            if (method == null) {
                Logger.w(TAG, "未找到快捷项构建方法")
                return
            }
            method.isAccessible = true

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val arcCount = readArcCount()
                        if (arcCount <= 1) return

                        val source = param.args.getOrNull(0) as? List<*> ?: return
                        val original = param.result as? List<*> ?: return
                        val expanded = buildExpandedLaunchItems(param.thisObject, source, original, arcCount)
                        if (expanded.size > original.size) {
                            param.result = expanded
                            Logger.d(TAG, "expanded launch items ${original.size} -> ${expanded.size}")
                        }
                    } catch (t: Throwable) {
                        Logger.e(TAG, "扩展快捷项失败", t)
                    }
                }
            })

            Logger.i(TAG, "Hooked launch item builder")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 快捷项构建失败", t)
        }
    }

    private fun buildExpandedLaunchItems(
        window: Any,
        sourceApps: List<*>,
        originalLaunchItems: List<*>,
        arcCount: Int,
    ): ArrayList<Any> {
        if (originalLaunchItems.isEmpty()) return ArrayList()

        val targetAppCount = min(sourceApps.size, APP_CAPACITY_PER_ARC.take(arcCount).sum())
        val currentAppCount = min(max(originalLaunchItems.size - 1, 0), targetAppCount)
        if (targetAppCount <= currentAppCount && originalLaunchItems.size == targetAppCount + 1) {
            return ArrayList(originalLaunchItems.filterNotNull())
        }

        val result = ArrayList<Any>(targetAppCount + 1)
        for (index in 0 until currentAppCount) {
            originalLaunchItems[index]?.let { result.add(it) }
        }

        // 原版最后一个就是「更多应用」，先插在第 1 条后面，后续 item 继续追加到内层圆弧。
        originalLaunchItems.lastOrNull()?.let { result.add(it) }

        for (index in currentAppCount until targetAppCount) {
            val app = sourceApps[index] ?: continue
            val item = newLaunchItem(window, app) ?: continue
            result.add(item)
        }
        return result
    }

    private fun newLaunchItem(window: Any, app: Any): Any? {
        return try {
            val ctor = launchItemConstructor ?: resolveLaunchItemConstructor(window.javaClass, app.javaClass)
                ?.also { launchItemConstructor = it }
                ?: return null
            ctor.newInstance(window, app)
        } catch (t: Throwable) {
            Logger.e(TAG, "创建快捷项包装失败", t)
            null
        }
    }

    private fun resolveLaunchItemConstructor(windowClass: Class<*>, appClass: Class<*>): Constructor<*>? {
        return windowClass.declaredClasses.firstNotNullOfOrNull { inner ->
            val hasLaunchMethod = inner.declaredMethods.any { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (!hasLaunchMethod) return@firstNotNullOfOrNull null

            inner.declaredConstructors.firstOrNull { ctor ->
                val params = ctor.parameterTypes
                params.size == 2 &&
                    params[0].isAssignableFrom(windowClass) &&
                    params[1].isAssignableFrom(appClass)
            }?.also { it.isAccessible = true }
        }
    }

    private fun hookArcLayout(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(GESTURE_APP_LAUNCHER, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val arcCount = readArcCount()
                            if (arcCount <= 1) return
                            val launcher = param.thisObject as? ViewGroup ?: return
                            applyMultiArcLayout(launcher, arcCount)
                        } catch (t: Throwable) {
                            Logger.e(TAG, "多圆弧布局失败", t)
                        }
                    }
                }
            )
            Logger.i(TAG, "Hooked GestureAppLauncher.onLayout")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 圆弧布局失败", t)
        }
    }

    private fun applyMultiArcLayout(launcher: ViewGroup, configuredArcCount: Int) {
        val childCount = launcher.childCount
        if (childCount <= VIEW_CAPACITY_PER_ARC[0]) return
        val fields = layoutParamFields ?: resolveLayoutParamFields(launcher)
            ?.also { layoutParamFields = it }
            ?: return

        val ringCounts = computeRingCounts(childCount, configuredArcCount)
        val activeRingCount = ringCounts.count { it > 0 }
        if (activeRingCount <= 1) return

        val width = launcher.width.toFloat()
        val centerY = launcher.height.toFloat()
        val sideRight = inferRightSide(launcher)
        val outerRadius = inferOuterRadius(launcher, sideRight, centerY)
        if (outerRadius <= 0f) return

        val maxChildSize = maxMeasuredChildSize(launcher)
        val ringGap = max(maxChildSize + dp(8f), outerRadius * 0.18f)
        val touchSlop = ViewConfiguration.get(launcher.context).scaledTouchSlop.toFloat()
        val safeDegrees = inferSafeDegrees(launcher.resources, outerRadius)

        var childIndex = 0
        for (ring in 0 until activeRingCount) {
            val countInRing = ringCounts[ring]
            if (countInRing <= 0) continue
            val radius = max(outerRadius - ringGap * ring, maxChildSize * 1.35f)

            for (positionInRing in 0 until countInRing) {
                if (childIndex >= childCount) return
                val child = launcher.getChildAt(childIndex)
                val angle = angleFor(positionInRing, countInRing, safeDegrees)
                layoutChildOnArc(
                    launcherWidth = width,
                    centerY = centerY,
                    sideRight = sideRight,
                    child = child,
                    radius = radius,
                    angle = angle,
                )
                updateSelectionBounds(child, fields, angle, radius, touchSlop)
                childIndex++
            }
        }
    }

    private fun computeRingCounts(childCount: Int, configuredArcCount: Int): IntArray {
        val arcCount = configuredArcCount.coerceIn(1, VIEW_CAPACITY_PER_ARC.size)
        val counts = IntArray(arcCount)
        if (childCount <= 0) return counts

        // item 顺序固定为：前 6 个应用 + 更多应用 + 额外应用。
        // 因此第一条按 7 个 View 计算，后面每条只吃额外应用容量。
        counts[0] = min(childCount, VIEW_CAPACITY_PER_ARC[0])
        var remaining = childCount - counts[0]
        for (ring in 1 until arcCount) {
            if (remaining <= 0) break
            val count = min(remaining, VIEW_CAPACITY_PER_ARC[ring])
            counts[ring] = count
            remaining -= count
        }
        return counts
    }

    private fun layoutChildOnArc(
        launcherWidth: Float,
        centerY: Float,
        sideRight: Boolean,
        child: View,
        radius: Float,
        angle: Float,
    ) {
        val radians = Math.toRadians(angle.toDouble())
        val centerX = if (sideRight) {
            launcherWidth - (sin(radians) * radius).toFloat()
        } else {
            (sin(radians) * radius).toFloat()
        }
        val childCenterY = centerY - (cos(radians) * radius).toFloat()
        val left = (centerX - child.measuredWidth / 2f).roundToInt()
        val top = (childCenterY - child.measuredHeight / 2f).roundToInt()
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    private fun updateSelectionBounds(
        child: View,
        fields: ArcLayoutFields,
        angle: Float,
        radius: Float,
        touchSlop: Float,
    ) {
        val lp = child.layoutParams ?: return
        val halfSize = max(child.measuredWidth, child.measuredHeight) / 2f
        fields.angle.setFloat(lp, angle)
        fields.radiusMin.setFloat(lp, max(0f, radius - halfSize - touchSlop))
        fields.radiusMax.setFloat(lp, radius + halfSize + touchSlop)
    }

    private fun resolveLayoutParamFields(launcher: ViewGroup): ArcLayoutFields? {
        if (launcher.childCount <= 0) return null
        val lp = launcher.getChildAt(0).layoutParams ?: return null
        val fields = lp.javaClass.declaredFields
            .filter { it.type == Float::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
        if (fields.size < 3) return null

        val values = fields.associateWith { field ->
            runCatching { field.getFloat(lp) }.getOrDefault(Float.NaN)
        }
        val angle = values.entries
            .filter { (_, value) -> value.isFinite() && value >= 0f && value <= 90f }
            .minByOrNull { (_, value) -> value }
            ?.key
            ?: return null

        val radiusFields = values.entries
            .filter { (field, value) -> field != angle && value.isFinite() }
            .sortedBy { (_, value) -> value }
            .map { it.key }
        if (radiusFields.size < 2) return null

        return ArcLayoutFields(
            angle = angle,
            radiusMin = radiusFields.first(),
            radiusMax = radiusFields.last(),
        )
    }

    private fun inferRightSide(launcher: ViewGroup): Boolean {
        if (launcher.childCount <= 0) return true
        val first = launcher.getChildAt(0)
        val centerX = (first.left + first.right) / 2f
        return centerX > launcher.width / 2f
    }

    private fun inferOuterRadius(launcher: ViewGroup, sideRight: Boolean, centerY: Float): Float {
        if (launcher.childCount <= 0) return fallbackOuterRadius(launcher.resources)
        val first = launcher.getChildAt(0)
        val childCenterX = (first.left + first.right) / 2f
        val childCenterY = (first.top + first.bottom) / 2f
        val originX = if (sideRight) launcher.width.toFloat() else 0f
        val inferred = hypot(abs(childCenterX - originX), abs(centerY - childCenterY))
        return if (inferred > 0f) inferred else fallbackOuterRadius(launcher.resources)
    }

    private fun fallbackOuterRadius(resources: Resources): Float {
        return getDimen(resources, "slide_gesture_launcher_item_radius")
            ?: getDimen(resources, "slide_gesture_launcher_item_radius_no_nav_bar")
            ?: dp(277f)
    }

    private fun inferSafeDegrees(resources: Resources, outerRadius: Float): Int {
        val normal = getDimen(resources, "slide_gesture_launcher_item_radius")
        val noNav = getDimen(resources, "slide_gesture_launcher_item_radius_no_nav_bar")
        return if (normal != null && noNav != null && abs(outerRadius - noNav) < abs(outerRadius - normal)) {
            3
        } else {
            0
        }
    }

    private fun getDimen(resources: Resources, name: String): Float? {
        val id = resources.getIdentifier(name, "dimen", TARGET_PACKAGE)
        if (id <= 0) return null
        return runCatching { resources.getDimensionPixelSize(id).toFloat() }.getOrNull()
    }

    private fun angleFor(index: Int, count: Int, safeDegrees: Int): Float {
        if (count <= 0) return 0f
        if (safeDegrees <= 0) {
            return (90f / (count + 1f)) * (index + 1f)
        }
        val step = (90f - safeDegrees * 2f) / count
        return index * step + step * 0.5f + safeDegrees
    }

    private fun maxMeasuredChildSize(launcher: ViewGroup): Float {
        var size = 0
        for (index in 0 until launcher.childCount) {
            val child = launcher.getChildAt(index)
            size = max(size, max(child.measuredWidth, child.measuredHeight))
        }
        return if (size > 0) size.toFloat() else dp(50f)
    }

    private fun readArcCount(): Int {
        val lp = loadParam ?: return 1
        return XposedPrefs.getFeatureValue(lp, prefsPackage, FEATURE_KEY, 1).coerceIn(1, 4)
    }

    private fun dp(value: Float): Float {
        return value * Resources.getSystem().displayMetrics.density
    }
}

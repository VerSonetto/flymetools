package com.karen.flymetool.hook.feature.launcher

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

/**
 * iOS 风格最近任务堆叠卡片。
 *
 * 等价于 Reverse-FlymeLauncher 半成品项目给 TaskView 加 curveTransX/Y 字段 + 修改
 * updateCurveProperties 用样条曲线计算变换。由于 Xposed 无法加字段，改为用 View.setTag
 * 存曲线值。
 *
 * 通过 after-hook applyTranslationX/Y/applyScale 叠加曲线值，而非直接覆盖 translationX/Y。
 * 这样 dismiss 动画的 dismissTranslationX/Y 被保留，曲线效果叠加在上面。
 *
 * 关键：hook setDismissTranslationX/Y，每帧触发 applyStack 重算曲线，并将 dismissTranslation
 * 加入 dist 计算，使曲线值随 dismiss 动画位置平滑变化，避免动画结束时跳变。
 *
 * 特征定位：RecentsView.updateCurveProperties / dispatchScrollChanged /
 * updatePageOffsetsForFlyme（无参 public 方法名），TaskView.applyScale / applyTranslationX /
 * applyTranslationY / setDismissTranslationX / setDismissTranslationY（按方法名+参数特征定位）。
 */
object IosStackedRecentsHook : FeatureHook {

    private const val TAG = "IosStackedRecents"
    private const val FEATURE_KEY = "ios_stacked_recents"
    private const val RECENTS_VIEW = "com.android.quickstep.views.RecentsView"
    private const val TASK_VIEW = "com.android.quickstep.views.TaskView"

    // ponytail: 用 View.setTag 存曲线值，等价于半成品的 curveTransX/Y 字段
    private val TAG_SCALE = Int.MAX_VALUE - 701
    private val TAG_TX = Int.MAX_VALUE - 702
    private val TAG_TY = Int.MAX_VALUE - 703
    private val TAG_ALPHA = Int.MAX_VALUE - 704
    private val TAG_ROT_Y = Int.MAX_VALUE - 705
    private val TAG_TZ = Int.MAX_VALUE - 706
    private val TAG_ACTIVE = Int.MAX_VALUE - 707
    // dismiss 动画期间的平移值（由 setDismissTranslationX/Y before-hook 捕获）
    private val TAG_DISMISS_TX = Int.MAX_VALUE - 708
    private val TAG_DISMISS_TY = Int.MAX_VALUE - 709

    private val math = IosRecentsMath()

    // 缓存 TaskView private apply 方法
    private var applyScaleMethod: Method? = null
    private var applyTranslationXMethod: Method? = null
    private var applyTranslationYMethod: Method? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.meizu.flyme.launcher") return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        mount(lpparam)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam) {
        val recentsCl = XposedHelpers.findClass(RECENTS_VIEW, lpparam.classLoader)
        val taskCl = XposedHelpers.findClass(TASK_VIEW, lpparam.classLoader)

        applyScaleMethod = taskCl.getDeclaredMethod("applyScale").also { it.isAccessible = true }
        applyTranslationXMethod = taskCl.getDeclaredMethod("applyTranslationX").also { it.isAccessible = true }
        applyTranslationYMethod = taskCl.getDeclaredMethod("applyTranslationY").also { it.isAccessible = true }

        hookApplyTransforms(taskCl)
        hookDismissTranslation(taskCl)
        hookUpdateCurve(recentsCl, taskCl)
        hookDispatchScroll(recentsCl, taskCl)
        hookDisableDefaultOffsets(recentsCl)
        Logger.i(TAG, "iOS 堆叠后台 Hook 完成")
    }

    /**
     * after-hook applyTranslationX/Y/applyScale：在系统计算的组合值上叠加曲线值。
     *
     * 系统的 applyTranslationX 设置 translationX = dismissTranslationX + taskOffsetTranslationX + ...
     * after-hook 再加上 curveTx，结果 = dismissTranslationX + ... + curveTx。
     */
    private fun hookApplyTransforms(taskCl: Class<*>) {
        val scaleM = applyScaleMethod ?: return
        XposedBridge.hookMethod(scaleM, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (view.getTag(TAG_ACTIVE) != true) return
                val scale = view.getTag(TAG_SCALE) as? Float ?: return
                view.scaleX *= scale
                view.scaleY *= scale
            }
        })

        val txM = applyTranslationXMethod ?: return
        XposedBridge.hookMethod(txM, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (view.getTag(TAG_ACTIVE) != true) return
                val tx = view.getTag(TAG_TX) as? Float ?: return
                view.translationX += tx
            }
        })

        val tyM = applyTranslationYMethod ?: return
        XposedBridge.hookMethod(tyM, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (view.getTag(TAG_ACTIVE) != true) return
                val ty = view.getTag(TAG_TY) as? Float ?: return
                view.translationY += ty
            }
        })
    }

    /**
     * Hook setDismissTranslationX/Y：dismiss 动画每帧调用它们来平移剩余卡片。
     *
     * before：把 dismissTranslation 值存入 tag，供 applyStack 读取。
     * after：触发 applyStack 重算曲线——使 curveTx 随 dismiss 动画位置平滑变化，
     * 而非用上次 applyStack 的过时值。
     */
    private fun hookDismissTranslation(taskCl: Class<*>) {
        val floatType = Float::class.javaPrimitiveType
        for ((name, tagId) in arrayOf(
            "setDismissTranslationX" to TAG_DISMISS_TX,
            "setDismissTranslationY" to TAG_DISMISS_TY,
        )) {
            val m = taskCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == floatType
            } ?: continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.thisObject as View).setTag(tagId, param.args[0] as Float)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val recents = view.parent as? ViewGroup ?: return
                    applyStack(recents, taskCl)
                }
            })
        }
    }

    /** 更新曲线变换（等价于半成品 updateCurveProperties 里的 spline 计算） */
    private fun hookUpdateCurve(recentsCl: Class<*>, taskCl: Class<*>) {
        val m = recentsCl.declaredMethods.firstOrNull {
            it.name == "updateCurveProperties" && it.parameterTypes.isEmpty()
        } ?: run { Logger.w(TAG, "updateCurveProperties 未找到"); return }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyStack(param.thisObject as ViewGroup, taskCl)
            }
        })
    }

    /** 滚动时重新应用（Flyme 滚动不触发 updateCurveProperties，需补） */
    private fun hookDispatchScroll(recentsCl: Class<*>, taskCl: Class<*>) {
        val m = recentsCl.declaredMethods.firstOrNull {
            it.name == "dispatchScrollChanged" && it.parameterTypes.isEmpty()
        } ?: return
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyStack(param.thisObject as ViewGroup, taskCl)
            }
        })
    }

    /** 禁用 Flyme 默认页偏移，避免与曲线 translation 冲突（等价于半成品 updatePageOffsets return） */
    private fun hookDisableDefaultOffsets(recentsCl: Class<*>) {
        for (name in arrayOf("updatePageOffsetsForFlyme", "updatePageOffsets")) {
            val m = recentsCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!shouldStack(param.thisObject as ViewGroup)) return
                    param.result = null
                }
            })
        }
    }

    private fun shouldStack(recents: ViewGroup): Boolean {
        return try {
            if (XposedHelpers.callMethod(recents, "showAsGrid") as? Boolean == true) return false
            (XposedHelpers.callMethod(recents, "getTaskViewCount") as? Int ?: 0) > 0
        } catch (_: Throwable) { false }
    }

    /** 核心：计算每个 TaskView 的曲线变换并写入 tag，再触发 apply 刷新 */
    private fun applyStack(recents: ViewGroup, taskCl: Class<*>) {
        try {
            if (!shouldStack(recents)) { clearStack(recents, taskCl); return }
            val pageCount = XposedHelpers.callMethod(recents, "getPageCount") as? Int ?: return
            if (pageCount == 0) return
            val first = XposedHelpers.callMethod(recents, "getPageAt", 0) as? View ?: return
            if (first.measuredWidth == 0) return

            val isLandscape = try {
                val handler = XposedHelpers.callMethod(recents, "getPagedOrientationHandler")
                handler::class.java.name.contains("Landscape")
            } catch (_: Throwable) { false }
            // 滚动方向：竖屏 X（PagedView 默认），横屏 Y（LandscapePagedViewHandler）
            val scroll = if (isLandscape) recents.scrollY else recents.scrollX
            val screenPrimary = if (isLandscape) recents.measuredHeight else recents.measuredWidth
            val screenSecondary = if (isLandscape) recents.measuredWidth else recents.measuredHeight
            val taskPrimarySize = taskSizeInScrollDir(recents, isLandscape).coerceAtLeast(1)

            val centerScale = math.getValue(IosRecentsMath.SPLINE_SCALE, 3.0).toFloat()
            val centerY = math.getValue(IosRecentsMath.SPLINE_Y_COORD, 3.0).toFloat()
            val centerX = math.getValue(IosRecentsMath.SPLINE_X_COORD, 3.0).toFloat()

            var taskIndex = 0
            for (i in 0 until recents.childCount) {
                val child = recents.getChildAt(i) ?: continue
                if (!taskCl.isInstance(child)) continue

                // 读取 dismiss 动画平移值，加入 childCenter 计算
                // 使曲线随 dismiss 动画位置平滑变化，而非用布局位置（动画期间不变）
                val dismissTx = child.getTag(TAG_DISMISS_TX) as? Float ?: 0f
                val dismissTy = child.getTag(TAG_DISMISS_TY) as? Float ?: 0f
                val dismissPrimary = if (isLandscape) dismissTy else dismissTx

                val childCenter = if (isLandscape)
                    child.top + child.measuredHeight / 2 + dismissPrimary
                else
                    child.left + child.measuredWidth / 2 + dismissPrimary
                val screenCenter = scroll + screenPrimary / 2
                val dist = (childCenter - screenCenter).toFloat()
                val f2 = 3.0 + dist / taskPrimarySize

                val rawScale = math.getValue(IosRecentsMath.SPLINE_SCALE, f2).toFloat()
                val rawAlpha = math.getValue(IosRecentsMath.SPLINE_ALPHA, f2).toFloat().coerceIn(0f, 1f)
                // 主方向（滚动方向）始终用 SPLINE_X_COORD，次方向用 SPLINE_Y_COORD
                // 横屏下滚动方向是 Y，需交换到 translationY；竖屏滚动方向是 X，用 translationX
                val rawPrimary = math.getValue(IosRecentsMath.SPLINE_X_COORD, f2).toFloat()
                val rawSecondary = math.getValue(IosRecentsMath.SPLINE_Y_COORD, f2).toFloat()
                val rotationY = math.getValue(IosRecentsMath.SPLINE_ROTATION_Y, f2).toFloat()

                val scale = if (centerScale != 0f) rawScale / centerScale else 1f
                val curvePrimary = (rawPrimary - centerX) * screenPrimary - dist
                val curveSecondary = (rawSecondary - centerY) * screenSecondary
                val curveTx = if (isLandscape) curveSecondary else curvePrimary
                val curveTy = if (isLandscape) curvePrimary else curveSecondary

                child.setTag(TAG_ACTIVE, true)
                child.setTag(TAG_SCALE, scale)
                child.setTag(TAG_TX, curveTx)
                child.setTag(TAG_TY, curveTy)
                child.setTag(TAG_ALPHA, rawAlpha)
                child.setTag(TAG_ROT_Y, rotationY)
                child.setTag(TAG_TZ, -taskIndex.toFloat())

                // 直接设 alpha/rotationY/translationZ（无 apply* 会覆盖它们）
                child.alpha = rawAlpha
                child.rotationY = rotationY
                child.translationZ = -taskIndex.toFloat()

                // 触发 apply 方法，after-hook 叠加曲线值（保留 dismissTranslation 等基础平移）
                applyScaleMethod?.invoke(child)
                applyTranslationXMethod?.invoke(child)
                applyTranslationYMethod?.invoke(child)
                taskIndex++
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "applyStack 失败", e)
        }
    }

    private fun clearStack(recents: ViewGroup, taskCl: Class<*>) {
        for (i in 0 until recents.childCount) {
            val child = recents.getChildAt(i) ?: continue
            if (!taskCl.isInstance(child)) continue
            if (child.getTag(TAG_ACTIVE) != true) continue
            // 先清 TAG_ACTIVE，使 after-hook 不再叠加曲线值
            child.setTag(TAG_ACTIVE, false)
            child.setTag(TAG_SCALE, null)
            child.setTag(TAG_TX, null)
            child.setTag(TAG_TY, null)
            child.setTag(TAG_ALPHA, null)
            child.setTag(TAG_ROT_Y, null)
            child.setTag(TAG_TZ, null)
            child.setTag(TAG_DISMISS_TX, null)
            child.setTag(TAG_DISMISS_TY, null)
            child.rotationY = 0f
            child.alpha = 1f
            child.translationZ = 0f
            // 恢复基础变换（after-hook 看到 TAG_ACTIVE=false，不叠加曲线）
            applyScaleMethod?.invoke(child)
            applyTranslationXMethod?.invoke(child)
            applyTranslationYMethod?.invoke(child)
        }
    }

    private fun taskSizeInScrollDir(recents: ViewGroup, isLandscape: Boolean): Int {
        return try {
            val size = XposedHelpers.callMethod(recents, "getLastComputedTaskSize") as? Rect
            val primary = if (isLandscape) size?.height() else size?.width()
            primary?.takeIf { it > 0 } ?: (if (isLandscape) recents.measuredHeight else recents.measuredWidth) / 2
        } catch (_: Throwable) { recents.measuredWidth / 2 }
    }

    // --- cubic spline math (from Reverse-FlymeLauncher IosRecentsMath) ---
    private class IosRecentsMath {
        companion object {
            const val SPLINE_X_COORD = 0
            const val SPLINE_Y_COORD = 1
            const val SPLINE_ALPHA = 2
            const val SPLINE_SCALE = 3
            const val SPLINE_ROTATION_Y = 13
            private const val DATA =
                "-20,-20,-15,0,50,125,17,17,17,17,17,17,5,80,110,120,130,140,112,114,116,118,120,122," +
                    "0,60,100,100,100,100,0,0,0,100,0,0,80,85,90,90,90,95,100,100,100,100,100,100," +
                    "-10,-10,-10,-10,-10,-10,0,0,0,0,0,0,2,2,2,2,2,2,0,0,0,0,0,0,0,0,0,0,0,0," +
                    "0,0,0,0,0,0,0,0,0,0,0,0,100,100,100,100,100,100"
        }

        private val splines = ArrayList<SplineHelper>()

        init {
            val values = DATA.split(",")
            for (i in 0 until 16) {
                val y = DoubleArray(6)
                for (j in 0 until 6) {
                    y[j] = (values.getOrNull(i * 6 + j)?.toIntOrNull() ?: 0) / 100.0
                }
                splines.add(SplineHelper().also { it.init(y) })
            }
        }

        fun getValue(index: Int, x: Double): Double =
            if (index in splines.indices) splines[index].getValue(x) else 0.0
    }

    private class SplineHelper {
        private lateinit var a: DoubleArray
        private lateinit var b: DoubleArray
        private lateinit var c: DoubleArray
        private lateinit var d: DoubleArray
        private val x = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)

        fun init(y: DoubleArray) {
            val n = x.size - 1
            a = y.copyOf()
            b = DoubleArray(n)
            c = DoubleArray(n + 1)
            d = DoubleArray(n)
            val h = DoubleArray(n) { x[it + 1] - x[it] }
            val alpha = DoubleArray(n)
            for (i in 1 until n) {
                alpha[i] = 3.0 / h[i] * (a[i + 1] - a[i]) - 3.0 / h[i - 1] * (a[i] - a[i - 1])
            }
            val l = DoubleArray(n + 1)
            val mu = DoubleArray(n + 1)
            val z = DoubleArray(n + 1)
            l[0] = 1.0
            for (i in 1 until n) {
                l[i] = 2.0 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]
                mu[i] = h[i] / l[i]
                z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
            }
            l[n] = 1.0
            for (j in n - 1 downTo 0) {
                c[j] = z[j] - mu[j] * c[j + 1]
                b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3.0
                d[j] = (c[j + 1] - c[j]) / (3 * h[j])
            }
        }

        fun getValue(xv: Double): Double {
            val n = x.size - 1
            var idx = -1
            for (i in 0 until n) {
                if (xv in x[i]..x[i + 1]) { idx = i; break }
            }
            if (idx == -1) {
                val i2 = if (xv < x[0]) 0 else 4
                val diff = xv - x[i2]
                return a[i2] + (b[i2] + c[i2] + d[i2]) * diff
            }
            val diff = xv - x[idx]
            return a[idx] + b[idx] * diff + c[idx] * diff * diff + d[idx] * diff * diff * diff
        }
    }
}

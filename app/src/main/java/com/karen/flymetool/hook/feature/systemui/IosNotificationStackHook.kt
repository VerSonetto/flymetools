package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import kotlin.math.max
import kotlin.math.min

/**
 * iOS 锁屏通知堆叠 — 对齐 showcase.html layoutFor。
 *
 * 折叠 p≈0：整组沉底，首卡全尺寸顶在 THRESH，下层 L*PEEK 错位 + 缩放下沉。
 * 展开 p→1：与原生列表 Y 插值；仅 overflow 继续堆叠。
 * 有 pinned HUN 时整段不碰。
 */
object IosNotificationStackHook : FeatureHook {

    private const val TAG = "IosNotifStack"
    private const val FEATURE_KEY = "ios_notification_stack"
    /** THRESH 距内容区底的 inset（越大堆叠越高） */
    private const val DEFAULT_BOTTOM_PAD = 180

    // showcase.html
    private const val STEP_DP = 108f
    private const val PEEK_DP = 46f
    private const val SCALE_PER_L = 0.05f
    private const val MIN_SCALE = 0.84f
    private const val MAX_L_VISIBLE = 3.5f
    private const val Z_BASE = 8f
    private const val Z_STEP = 2f

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    private data class Item(
        val view: View,
        val height: Float,
        val nativeY: Float,
        val index: Int,
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return
        prefsPackage = packageName
        loadParam = lpparam
        try {
            mount(lpparam)
            Logger.i(TAG, "iOS 堆叠 Hook 完成 (showcase layoutFor)")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook 挂载失败", e)
        }
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam) {
        val algoCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm",
            lpparam.classLoader
        )
        val ambientCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.stack.AmbientState",
            lpparam.classLoader
        )
        val rowCl = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            algoCl,
            "resetViewStates",
            ambientCl,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        apply(param.args[0], param.thisObject, rowCl)
                    } catch (e: Throwable) {
                        Logger.e(TAG, "堆叠失败", e)
                    }
                }
            }
        )
    }

    private fun readBottomPadDp(): Int {
        val lp = loadParam ?: return DEFAULT_BOTTOM_PAD
        return XposedPrefs.getFeatureValue(
            lp, prefsPackage, FEATURE_KEY, DEFAULT_BOTTOM_PAD
        ).coerceIn(0, 280)
    }

    private fun apply(ambient: Any, algo: Any, rowCl: Class<*>) {
        val host = XposedHelpers.getObjectField(algo, "mHostView") as? ViewGroup ?: return
        if (hasPinnedHeadsUp(host, rowCl)) return

        val trackedHun = readTrackedHun(ambient)
        val items = collect(host, rowCl, ambient, trackedHun)
        if (items.isEmpty()) return

        val stackY = readStackY(ambient)
        val innerH = readInnerHeight(ambient)
        if (innerH <= 0) return

        val contentBottom = stackY + innerH
        // THRESH：折叠时首卡顶边（整组沉底锚点）
        val thresh = contentBottom - dp(readBottomPadDp().toFloat())
        val listTop = stackY + dp(8f)
        val step = dp(STEP_DP)
        val peek = dp(PEEK_DP)
        val scrollY = readScrollY(ambient)
        val scrollRange = readScrollRange(host)
        // 0=整组沉底；1=完全用系统列表（含滚到底）
        val pe = expandProgress(ambient, scrollY, scrollRange)

        // 完全展开：不改任何布局，系统列表可滚到底看全部
        if (pe >= 0.995f) {
            hideShelf(ambient)
            return
        }

        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, viewState(item.view), ambient, trackedHun)) {
                continue
            }
            val st = viewState(item.view) ?: continue
            val h = max(item.height, 1f)
            val i = item.index

            // p=0: et = THRESH + i*STEP → 整组沉底，首卡全尺寸，其余 L=i 堆叠
            // p→1: et → nativeY（系统已算 scroll）
            val collapsedEt = thresh + i * step
            val et = lerp(collapsedEt, item.nativeY, pe)
            val over = et - thresh

            val ty: Float
            val scale: Float
            val alpha: Float
            val z: Float
            val stacked: Boolean

            if (over < 0f) {
                ty = et
                scale = 1f
                alpha = 1f
                z = Z_BASE - i * 0.5f
                stacked = false
            } else {
                val L = over / step
                if (L >= MAX_L_VISIBLE) {
                    ty = thresh + MAX_L_VISIBLE * peek
                    XposedHelpers.setBooleanField(st, "hidden", true)
                    XposedHelpers.callMethod(st, "setAlpha", 0f)
                    XposedHelpers.callMethod(st, "setYTranslation", ty)
                    XposedHelpers.callMethod(st, "setScaleX", MIN_SCALE)
                    XposedHelpers.callMethod(st, "setScaleY", MIN_SCALE)
                    continue
                }
                ty = thresh + L * peek
                scale = max(MIN_SCALE, 1f - L * SCALE_PER_L)
                alpha = (1f - (L - 0.2f) * 0.5f).coerceIn(0f, 1f)
                z = Z_BASE - i * Z_STEP
                stacked = true
            }

            val y = ty - h * (1f - scale) * 0.5f

            XposedHelpers.callMethod(st, "setYTranslation", y)
            XposedHelpers.callMethod(st, "setScaleX", scale)
            XposedHelpers.callMethod(st, "setScaleY", scale)
            XposedHelpers.callMethod(st, "setAlpha", alpha)
            XposedHelpers.callMethod(st, "setZTranslation", z)
            XposedHelpers.setBooleanField(st, "hidden", false)
            XposedHelpers.setBooleanField(st, "inShelf", false)
            XposedHelpers.setIntField(st, "clipBottomAmount", 0)
            XposedHelpers.setIntField(st, "clipTopAmount", 0)

            try {
                item.view.elevation = if (stacked && scale < 0.98f) {
                    max(0f, (1f - scale) * 12f)
                } else 0f
            } catch (_: Throwable) {
            }
        }

        hideShelf(ambient)
        Logger.once(TAG, "sink pe=$pe scroll=$scrollY/$scrollRange n=${items.size}")
    }

    /**
     * 0 = 整组沉底；1 = 系统完整列表。
     * 锁屏与下拉通知栏同一套逻辑（布局相同，下拉仅多清空按钮）。
     * 驱动：NSSL scrollY / scrollRange。
     */
    private fun expandProgress(ambient: Any, scrollY: Float, scrollRange: Float): Float {
        // 滚到底 → 完全展开，交给系统列表
        if (scrollRange > 2f && scrollY >= scrollRange - 4f) return 1f

        // 无滚动空间：内容装得下 → 列表；否则沉底
        if (scrollRange <= 2f) {
            val shadeExpanded = callBool(ambient, "isShadeExpanded")
            // 面板可见且内容装得下时不堆叠
            return if (shadeExpanded) 1f else 0f
        }

        // scrollY 0→range 映射 0→1
        return (scrollY / scrollRange).coerceIn(0f, 1f)
    }

    private fun readScrollY(ambient: Any): Float = try {
        (XposedHelpers.callMethod(ambient, "getScrollY") as Int).toFloat()
    } catch (_: Throwable) {
        0f
    }

    /** NSSL 可滚范围；优先 getScrollRange / getOwnScrollY 相关 */
    private fun readScrollRange(host: ViewGroup): Float {
        // public/private getScrollRange
        for (name in arrayOf("getScrollRange", "getMaxScrollAmount")) {
            try {
                val v = XposedHelpers.callMethod(host, name)
                val f = when (v) {
                    is Int -> v.toFloat()
                    is Float -> v
                    else -> continue
                }
                if (f >= 0f) return f
            } catch (_: Throwable) {
            }
        }
        // contentHeight - maxLayoutHeight 近似
        try {
            val content = XposedHelpers.callMethod(host, "getContentHeight") as Int
            val maxH = try {
                XposedHelpers.getIntField(host, "mMaxLayoutHeight")
            } catch (_: Throwable) {
                host.height
            }
            return max(0, content - maxH).toFloat()
        } catch (_: Throwable) {
        }
        return 0f
    }

    private fun hasPinnedHeadsUp(host: ViewGroup, rowCl: Class<*>): Boolean {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (!rowCl.isInstance(child)) continue
            if (callBool(child, "isHeadsUp") && callBool(child, "isPinned")) return true
            if (boolField(child, "mIsHeadsUpStatus") && callBool(child, "isPinned")) return true
        }
        return false
    }

    private fun collect(
        host: ViewGroup,
        rowCl: Class<*>,
        ambient: Any,
        trackedHun: Any?,
    ): List<Item> {
        val raw = ArrayList<View>()
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (!rowCl.isInstance(child)) continue
            if (child.visibility == View.GONE) continue
            val st = viewState(child) ?: continue
            if (boolField(st, "gone")) continue
            if (isPinnedOrAnimatingHun(child, st, ambient, trackedHun)) continue
            raw += child
        }
        raw.sortBy {
            try {
                XposedHelpers.getIntField(viewState(it)!!, "notGoneIndex")
            } catch (_: Throwable) {
                Int.MAX_VALUE
            }
        }
        return raw.mapIndexed { idx, v ->
            val st = viewState(v)!!
            Item(
                view = v,
                height = readHeight(v, st),
                nativeY = XposedHelpers.callMethod(st, "getYTranslation") as Float,
                index = idx,
            )
        }
    }

    private fun isPinnedOrAnimatingHun(
        row: View,
        st: Any?,
        ambient: Any,
        trackedHun: Any?,
    ): Boolean {
        if (callBool(row, "isHeadsUp") && callBool(row, "isPinned")) return true
        if (callBool(row, "isPinned")) return true
        if (callBool(row, "isHeadsUpAnimatingAway")) return true
        if (callBool(row, "showingPulsing")) return true
        if (boolField(row, "mHeadsupDisappearRunning")) return true
        if (st != null) {
            try {
                if (XposedHelpers.getIntField(st, "location") == 1) return true
            } catch (_: Throwable) {
            }
        }
        if (trackedHun != null && trackedHun === row) return true
        try {
            val tracked = XposedHelpers.callMethod(ambient, "getTrackedHeadsUpRow")
            if (tracked != null && tracked === row) return true
        } catch (_: Throwable) {
        }
        try {
            val pinnedStatus = XposedHelpers.getObjectField(row, "mPinnedStatus")
            if (pinnedStatus != null && callBool(pinnedStatus, "isPinned")) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun callBool(obj: Any, name: String): Boolean = try {
        XposedHelpers.callMethod(obj, name) as Boolean
    } catch (_: Throwable) {
        false
    }

    private fun callFloat(obj: Any, name: String, default: Float): Float = try {
        when (val v = XposedHelpers.callMethod(obj, name)) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            else -> default
        }
    } catch (_: Throwable) {
        default
    }

    private fun readTrackedHun(ambient: Any): Any? = try {
        XposedHelpers.callMethod(ambient, "getTrackedHeadsUpRow")
    } catch (_: Throwable) {
        null
    }

    private fun hideShelf(ambient: Any) {
        try {
            val shelf = XposedHelpers.callMethod(ambient, "getShelf") ?: return
            val st = XposedHelpers.callMethod(shelf, "getViewState") ?: return
            XposedHelpers.setBooleanField(st, "hidden", true)
            XposedHelpers.callMethod(st, "setAlpha", 0f)
        } catch (_: Throwable) {
        }
    }

    private fun readHeight(row: View, st: Any): Float {
        val h = try {
            XposedHelpers.getIntField(st, "height").toFloat()
        } catch (_: Throwable) {
            0f
        }
        if (h > 1f) return h
        return try {
            (XposedHelpers.callMethod(row, "getIntrinsicHeight") as Int).toFloat()
        } catch (_: Throwable) {
            dp(72f)
        }
    }

    private fun viewState(view: Any): Any? = try {
        XposedHelpers.callMethod(view, "getViewState")
    } catch (_: Throwable) {
        null
    }

    private fun boolField(obj: Any, name: String): Boolean = try {
        XposedHelpers.getBooleanField(obj, name)
    } catch (_: Throwable) {
        false
    }

    private fun readStackY(ambient: Any): Float = try {
        XposedHelpers.callMethod(ambient, "getStackY") as Float
    } catch (_: Throwable) {
        try {
            XposedHelpers.callMethod(ambient, "getStackTop") as Float
        } catch (_: Throwable) {
            0f
        }
    }

    private fun readInnerHeight(ambient: Any): Int = try {
        XposedHelpers.callMethod(ambient, "getInnerHeight") as Int
    } catch (_: Throwable) {
        try {
            XposedHelpers.callMethod(ambient, "getLayoutMaxHeight") as Int
        } catch (_: Throwable) {
            0
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, Resources.getSystem().displayMetrics
    )
}

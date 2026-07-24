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

/**
 * iOS 锁屏通知堆叠（对齐 HTML layoutFor）。
 *
 * - 顶层堆叠卡 scale=1（与上方正常卡同大）
 * - 与上方完整卡保持 GAP，避免重叠
 * - 堆叠基准 BOTTOM_PAD 可配置
 */
object IosNotificationStackHook : FeatureHook {

    private const val TAG = "IosNotifStack"
    private const val FEATURE_KEY = "ios_notification_stack"
    private const val DEFAULT_BOTTOM_PAD = 56

    private const val STEP_DP = 108f
    private const val PEEK_DP = 46f
    private const val MAX_LAYER = 3f
    private const val GAP_DP = 10f
    private const val SCALE_STEP = 0.04f
    private const val MIN_SCALE = 0.84f
    private const val Z_BASE = 80f
    private const val Z_STEP = 20f

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
            Logger.i(TAG, "iOS 堆叠 Hook 完成")
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
        ).coerceIn(0, 160)
    }

    private fun apply(ambient: Any, algo: Any, rowCl: Class<*>) {
        val host = XposedHelpers.getObjectField(algo, "mHostView") as? ViewGroup ?: return
        val trackedHun = readTrackedHun(ambient)
        val items = collect(host, rowCl, ambient, trackedHun)
        if (items.isEmpty()) return

        hideShelf(ambient)

        val stackY = readStackY(ambient)
        val innerH = readInnerHeight(ambient)
        if (innerH <= 0) return

        val bottomPad = dp(readBottomPadDp().toFloat())
        val B = stackY + innerH - bottomPad
        val step = dp(STEP_DP)
        val peek = dp(PEEK_DP)
        val gap = dp(GAP_DP)

        // 第一遍：完整区底边（用于防重叠）— 仅统计非 HUN 列表卡
        var lastFullBottom = stackY
        for (item in items) {
            val h = max(item.height, 1f)
            if (item.nativeY + h <= B + 0.5f) {
                lastFullBottom = max(lastFullBottom, item.nativeY + h)
            }
        }
        val stackMinTop = lastFullBottom + gap

        for (item in items) {
            // 二次保险：绝不改写悬浮/HUN（系统 updateHeadsUpStates 已定好位置）
            if (isFloatingHeadsUp(item.view, viewState(item.view), ambient, trackedHun)) {
                continue
            }
            val st = viewState(item.view) ?: continue
            val h = max(item.height, 1f)
            val et = item.nativeY
            val over = et + h - B

            if (over <= 0f) {
                applyFull(st, item)
                continue
            }

            var L = over / step
            val ty: Float
            val scale: Float
            val alpha: Float
            val z: Float

            if (L >= MAX_LAYER) {
                L = MAX_LAYER
                ty = max((B - h) + MAX_LAYER * peek, stackMinTop)
                scale = MIN_SCALE
                alpha = 0f
                z = 0f
                XposedHelpers.setBooleanField(st, "hidden", true)
            } else {
                // 顶层 L→0：scale=1、alpha=1；下层随 L 缩小淡出
                ty = max((B - h) + L * peek, stackMinTop + L * peek * 0.15f)
                scale = if (L <= 0.05f) {
                    1f
                } else {
                    max(MIN_SCALE, 1f - L * SCALE_STEP)
                }
                alpha = if (L <= 0.05f) {
                    1f
                } else {
                    (1f - (L - 0.05f) * 0.4f).coerceIn(0.55f, 1f)
                }
                z = Z_BASE - item.index * Z_STEP
                XposedHelpers.setBooleanField(st, "hidden", false)
            }

            // pivot=center：目标顶边 ty
            val y = ty - h * (1f - scale) * 0.5f

            XposedHelpers.callMethod(st, "setYTranslation", y)
            XposedHelpers.callMethod(st, "setScaleX", scale)
            XposedHelpers.callMethod(st, "setScaleY", scale)
            XposedHelpers.callMethod(st, "setAlpha", alpha)
            XposedHelpers.callMethod(st, "setZTranslation", z)
            XposedHelpers.setBooleanField(st, "inShelf", false)
            XposedHelpers.setIntField(st, "clipBottomAmount", 0)
            XposedHelpers.setIntField(st, "clipTopAmount", 0)
        }
    }

    private fun applyFull(st: Any, item: Item) {
        XposedHelpers.callMethod(st, "setYTranslation", item.nativeY)
        XposedHelpers.callMethod(st, "setScaleX", 1f)
        XposedHelpers.callMethod(st, "setScaleY", 1f)
        XposedHelpers.callMethod(st, "setAlpha", 1f)
        XposedHelpers.callMethod(st, "setZTranslation", Z_BASE - item.index * 2f)
        XposedHelpers.setBooleanField(st, "hidden", false)
        XposedHelpers.setBooleanField(st, "inShelf", false)
        XposedHelpers.setIntField(st, "clipBottomAmount", 0)
        XposedHelpers.setIntField(st, "clipTopAmount", 0)
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
            // 悬浮通知：完全不进列表，保留系统 updateHeadsUpStates 结果
            if (isFloatingHeadsUp(child, st, ambient, trackedHun)) continue
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

    /**
     * Flyme12 悬浮通知判定（对齐 NSSL.isPinnedHeadsUp + StackScrollAlgorithm.updateHeadsUpStates）。
     * 特征：isHeadsUp && isPinned、location==1、trackedHun、mIsHeadsUp/mIsHeadsUpStatus 字段。
     */
    private fun isFloatingHeadsUp(
        row: View,
        st: Any?,
        ambient: Any,
        trackedHun: Any?,
    ): Boolean {
        // 1) 系统官方 isPinnedHeadsUp：isHeadsUp && isPinned
        val headsUp = callBool(row, "isHeadsUp")
        val pinned = callBool(row, "isPinned")
        if (headsUp && pinned) return true

        // 2) 仍处于 HUN 状态（含 disappear 动画）
        if (callBool(row, "isHeadsUpState")) return true
        if (callBool(row, "isHeadsUpAnimatingAway")) return true
        if (callBool(row, "showingPulsing")) return true

        // 3) 算法标记的首条 HUN location = 1 (LOCATION_FIRST_HEADS_UP)
        if (st != null) {
            try {
                if (XposedHelpers.getIntField(st, "location") == 1) return true
            } catch (_: Throwable) {
            }
            if (boolField(st, "headsUpIsVisible") && callBool(row, "mustStayOnScreen")) return true
        }

        // 4) AmbientState 跟踪的 HUN 行
        if (trackedHun != null && trackedHun === row) return true
        try {
            val tracked = XposedHelpers.callMethod(ambient, "getTrackedHeadsUpRow")
            if (tracked != null && tracked === row) return true
        } catch (_: Throwable) {
        }

        // 5) 字段兜底（公开方法偶发被混淆/代理时）
        if (boolField(row, "mIsHeadsUp")) return true
        if (boolField(row, "mIsHeadsUpStatus")) return true
        if (boolField(row, "mHeadsupDisappearRunning")) return true
        try {
            val pinnedStatus = XposedHelpers.getObjectField(row, "mPinnedStatus")
            if (pinnedStatus != null && callBool(pinnedStatus, "isPinned")) return true
        } catch (_: Throwable) {
        }

        // 6) 顶区 + mustStayOnScreen：悬浮横幅钉在 headsUpInset 附近
        if (callBool(row, "mustStayOnScreen") || callBool(row, "isAboveShelf")) {
            val y = if (st != null) {
                try {
                    XposedHelpers.callMethod(st, "getYTranslation") as Float
                } catch (_: Throwable) {
                    row.translationY
                }
            } else {
                row.translationY
            }
            // 顶区大致在状态栏/headsUpInset 一带（< 200dp 且明显在列表上方）
            if (y < dp(200f)) return true
        }

        return false
    }

    private fun callBool(obj: Any, name: String): Boolean = try {
        XposedHelpers.callMethod(obj, name) as Boolean
    } catch (_: Throwable) {
        false
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

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, Resources.getSystem().displayMetrics
    )
}

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
 * iOS 锁屏/下拉通知堆叠。
 *
 * 悬浮横幅（isHeadsUp && isPinned）期间整段不改 viewState，
 * 避免把系统已 hidden 的列表卡 unhide 到 HUN 上方。
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
    private const val Z_BASE = 4f
    private const val Z_STEP = 1f

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

        // 有 pinned 悬浮横幅：完全交给系统（NSSL.isPinnedHeadsUp）
        // 这是图里「上面多一条 LSPosed」的根因修复点
        if (hasPinnedHeadsUp(host, rowCl)) return

        // 面板未展开且无 HUN：也不堆叠（锁屏/收起态系统自管）
        if (!isShadeExpanded(ambient) && !isOnKeyguard(ambient)) return

        val trackedHun = readTrackedHun(ambient)
        val items = collect(host, rowCl, ambient, trackedHun)
        if (items.isEmpty()) return

        val stackY = readStackY(ambient)
        val innerH = readInnerHeight(ambient)
        if (innerH <= 0) return

        val bottomPad = dp(readBottomPadDp().toFloat())
        val B = stackY + innerH - bottomPad
        val step = dp(STEP_DP)
        val peek = dp(PEEK_DP)
        val gap = dp(GAP_DP)

        val hasOverflow = items.any { it.nativeY + max(it.height, 1f) > B + 0.5f }
        if (!hasOverflow) return

        hideShelf(ambient)

        var lastFullBottom = stackY
        for (item in items) {
            val h = max(item.height, 1f)
            if (item.nativeY + h <= B + 0.5f) {
                lastFullBottom = max(lastFullBottom, item.nativeY + h)
            }
        }
        val stackMinTop = lastFullBottom + gap

        for (item in items) {
            if (isPinnedOrAnimatingHun(item.view, viewState(item.view), ambient, trackedHun)) {
                continue
            }
            val st = viewState(item.view) ?: continue
            // 系统已 hidden 的列表卡：面板半收起时不要 unhide
            if (boolField(st, "hidden") && item.nativeY + max(item.height, 1f) <= B) {
                continue
            }

            val h = max(item.height, 1f)
            val et = item.nativeY
            val over = et + h - B

            if (over <= 0f) {
                // 完整区：不强制改 Y/hidden，只清 inShelf 脏标记
                if (boolField(st, "inShelf")) {
                    XposedHelpers.setBooleanField(st, "inShelf", false)
                }
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

    private fun hasPinnedHeadsUp(host: ViewGroup, rowCl: Class<*>): Boolean {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            if (!rowCl.isInstance(child)) continue
            if (callBool(child, "isHeadsUp") && callBool(child, "isPinned")) return true
            // Flyme：mIsHeadsUpStatus 在 pinned 时同步
            if (boolField(child, "mIsHeadsUpStatus") && callBool(child, "isPinned")) return true
        }
        return false
    }

    private fun isShadeExpanded(ambient: Any): Boolean = try {
        XposedHelpers.callMethod(ambient, "isShadeExpanded") as Boolean
    } catch (_: Throwable) {
        true
    }

    private fun isOnKeyguard(ambient: Any): Boolean = try {
        XposedHelpers.callMethod(ambient, "isOnKeyguard") as Boolean
    } catch (_: Throwable) {
        false
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

    /**
     * 只排除钉顶/动画中的真悬浮，不用 isHeadsUpState 误杀列表项。
     */
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

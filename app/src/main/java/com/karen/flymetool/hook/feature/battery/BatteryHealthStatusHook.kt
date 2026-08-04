package com.karen.flymetool.hook.feature.battery

import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BatteryHealthStatusHook : FeatureHook {

    private const val TAG = "BatteryHealthStatus"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        Logger.i(TAG, "handle 被调用")

        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "health_monitor")) return

        try {
            val clazz = XposedHelpers.findClass(
                "com.meizu.battery.app.settings.BatteryHealthStatusPreference",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "onBindView",
                View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        val context = view.context ?: return

                        val tvStatus = findTextView(view, "tv_status") ?: return
                        val tvTips = findTextView(view, "tv_tips")

                        val sohPrefs = context.getSharedPreferences("StarCoreManager", 0)
                        val soh = sohPrefs.getInt("soh", -1)
                        Logger.i(TAG, "读取 SOH: $soh")

                        val currentText = tvStatus.text.toString()
                        if (soh > 0 && !currentText.contains("%")) {
                            tvStatus.text = "$currentText ($soh%)"
                            Logger.i(TAG, "已更新状态: ${tvStatus.text}")
                        }

                        val cycleCount = getCycleCount(context)
                        if (cycleCount >= 0) addCycleCountCard(view, cycleCount)
                    }
                }
            )

            Logger.i(TAG, "Hook 已安装")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook 失败", e)
        }
    }

    private fun addCycleCountCard(view: View, cycleCount: Int) {
        val root = view as? ViewGroup ?: return
        if (root.childCount < 2) return
        if (root.findViewWithTag<View>("cycle_tag") != null) return

        val healthCard = root.getChildAt(0) as? ViewGroup ?: return
        val tvTips = findTextView(view, "tv_tips") ?: return
        val tipsIndex = root.indexOfChild(tvTips)

        val styleTitle = healthCard.getChildAt(0) as? TextView ?: return
        val styleValue = healthCard.getChildAt(1) as? TextView ?: return

        val cardHeight = healthCard.layoutParams?.height
            ?: (76 * view.context.resources.displayMetrics.density).toInt()

        val card = LinearLayout(view.context).apply {
            tag = "cycle_tag"
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                cardHeight
            ).apply {
                topMargin = (healthCard.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            }
            background = healthCard.background.mutate()
            setPadding(
                healthCard.paddingLeft, healthCard.paddingTop,
                healthCard.paddingRight, healthCard.paddingBottom
            )
        }

        val titleTv = TextView(view.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            textSize = styleTitle.textSize / view.context.resources.displayMetrics.scaledDensity
            setTextColor(styleTitle.textColors)
            typeface = styleTitle.typeface
            text = "循环次数"
        }

        val valueTv = TextView(view.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
            textSize = styleValue.textSize / view.context.resources.displayMetrics.scaledDensity
            setTextColor(styleValue.textColors)
            text = "$cycleCount 次"
        }

        card.addView(titleTv)
        card.addView(valueTv)
        root.addView(card, tipsIndex + 1)
        Logger.i(TAG, "已添加循环次数卡片: $cycleCount")
    }

    private fun findTextView(view: View, name: String): TextView? {
        val id = view.resources.getIdentifier(name, "id", view.context.packageName)
        if (id == 0) {
            Logger.w(TAG, "未找到资源 $name")
            return null
        }
        return view.findViewById(id)
    }

    private fun getCycleCount(context: android.content.Context): Int {
        if (Build.VERSION.SDK_INT < 34) return -1
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
        } catch (e: Throwable) {
            Logger.e(TAG, "读取循环次数失败", e)
            -1
        }
    }
}

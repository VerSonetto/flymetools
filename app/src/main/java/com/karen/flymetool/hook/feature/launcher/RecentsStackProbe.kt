package com.karen.flymetool.hook.feature.launcher

import android.os.SystemClock
import android.view.View
import com.karen.flymetool.hook.base.Logger
import java.util.ArrayDeque

/** 调试开关控制；逐帧仅累计数字，停止绘制后统一输出，避免日志干扰被测手势。 */
internal class RecentsStackProbe {
    private val events = ArrayDeque<Array<out Pair<String, Any?>>>()
    private var scheduled = false
    private var lastActivity = 0L
    private var windowStart = 0L
    private var previousFrame = 0L
    private var frames = 0
    private var totalApply = 0L
    private var maxApply = 0L
    private var totalDraw = 0L
    private var maxDraw = 0L
    private var totalSetup = 0L
    private var totalCards = 0L
    private var totalDecor = 0L
    private var maxSetup = 0L
    private var maxCards = 0L
    private var maxDecor = 0L
    private var gapSamples = 0
    private var gapsOverBudget = 0
    private var maxGap = 0L
    var scaleCallbacks = 0
    var layoutCallbacks = 0
    var rebuilds = 0
    var skippedTransforms = 0
    var layoutRequests = 0
    var layoutStacks = 0
    var lastLayoutStackTime = 0L
    var setupEnd = 0L
    var cardsEnd = 0L
    var pageCount = 0
    var frameBudgetNs = 16_666_667L
    var lastMode = Int.MIN_VALUE
    var pendingSnapshot: String? = null
    var serial = 0
    var failed = false

    fun event(host: View, vararg values: Pair<String, Any?>) {
        if (!Logger.debugEnabled) return
        if (events.size == 32) events.removeFirst()
        events.addLast(values)
        schedule(host)
    }

    fun frame(host: View, start: Long, applyEnd: Long, drawEnd: Long) {
        if (!Logger.debugEnabled) return
        val apply = applyEnd - start
        val draw = drawEnd - applyEnd
        frames++
        totalApply += apply
        maxApply = maxOf(maxApply, apply)
        totalDraw += draw
        maxDraw = maxOf(maxDraw, draw)
        if (setupEnd >= start && cardsEnd >= setupEnd) {
            val setup = setupEnd - start
            val cards = cardsEnd - setupEnd
            val decor = applyEnd - cardsEnd
            totalSetup += setup
            totalCards += cards
            totalDecor += decor
            maxSetup = maxOf(maxSetup, setup)
            maxCards = maxOf(maxCards, cards)
            maxDecor = maxOf(maxDecor, decor)
        }
        val gap = start - previousFrame
        // 排除用户停顿后的下一帧；这是连续绘制间隔，不当作 GPU 帧耗时。
        if (previousFrame != 0L && gap in 1..100_000_000L) {
            gapSamples++
            maxGap = maxOf(maxGap, gap)
            if (gap > frameBudgetNs * 3 / 2) gapsOverBudget++
        }
        previousFrame = start
        schedule(host)
    }

    private fun schedule(host: View) {
        lastActivity = SystemClock.uptimeMillis()
        if (scheduled) return
        scheduled = true
        windowStart = lastActivity
        host.postDelayed(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (Logger.debugEnabled && now - lastActivity < 600 && now - windowStart < 8000) {
                    host.postDelayed(this, 600)
                    return
                }
                scheduled = false
                flush()
            }
        }, 600)
    }

    private fun flush() {
        while (events.isNotEmpty()) {
            Logger.d(TAG, "堆叠探针：状态", *events.removeFirst())
        }
        if (frames > 0) {
            Logger.d(TAG, "堆叠探针：耗时汇总（单位 us）",
                "frames" to frames, "pages" to pageCount,
                "applyAvg" to totalApply / frames / 1000, "applyMax" to maxApply / 1000,
                "nativeDrawAvg" to totalDraw / frames / 1000, "nativeDrawMax" to maxDraw / 1000,
                "setupAvg" to totalSetup / frames / 1000, "setupMax" to maxSetup / 1000,
                "cardsAvg" to totalCards / frames / 1000, "cardsMax" to maxCards / 1000,
                "decorAvg" to totalDecor / frames / 1000, "decorMax" to maxDecor / 1000,
                "rebuilds" to rebuilds, "layouts" to layoutCallbacks, "scaleCalls" to scaleCallbacks,
                "skippedHiddenTransforms" to skippedTransforms,
                "layoutRequests" to layoutRequests,
                "frameBudget" to frameBudgetNs / 1000, "gapSamples" to gapSamples,
                "gapsOver1.5Budget" to gapsOverBudget, "maxGap" to maxGap / 1000)
        }
        frames = 0
        totalApply = 0; maxApply = 0; totalDraw = 0; maxDraw = 0
        totalSetup = 0; totalCards = 0; totalDecor = 0
        maxSetup = 0; maxCards = 0; maxDecor = 0
        previousFrame = 0; gapSamples = 0; gapsOverBudget = 0; maxGap = 0
        scaleCallbacks = 0; layoutCallbacks = 0; rebuilds = 0
        skippedTransforms = 0
        layoutRequests = 0; layoutStacks = 0
    }

    private companion object {
        const val TAG = "IosDepthStackRecents"
    }
}

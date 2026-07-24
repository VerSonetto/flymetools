package com.karen.flymetool.hook.feature.launcher

import android.content.ComponentName
import android.graphics.Canvas
import android.util.FloatProperty
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign


object IosDepthStackRecentsHook : FeatureHook {

    private const val TAG = "IosDepthStackRecents"
    private const val TARGET_PACKAGE = "com.meizu.flyme.launcher"
    private const val FEATURE_KEY = "ios_stacked_recents"

    private const val LEFT_PEEK_FACTOR = 0.24f
    private const val LEFT_DECAY = 0.28f
    private const val RIGHT_SPACING_FACTOR = 0.85f
    private const val RIGHT_PARALLAX_EXPONENT = 1.2f
    private const val FOCUSED_SCALE = 1f
    private const val MIN_LEFT_SCALE = 0.956f
    private const val LEFT_SCALE_DECAY = 0.50f
    private const val DEPTH_Z_STEP_DP = 1f
    private const val EPSILON = 0.0005f

    private val recentsClassCandidates = arrayOf(
        "com.android.quickstep.views.RecentsView",
        "com.android.quickstep.views.LauncherRecentsView",
    )
    private val taskClassCandidates = arrayOf(
        "com.android.quickstep.views.TaskView",
    )
    private val runtimeRotationMethods = HashMap<Class<*>, Method?>()
    private val stateByRecents = IdentityHashMap<ViewGroup, RecentsState>()

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        try {
            mount(lpparam.classLoader)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "挂载失败", throwable)
        }
    }

    private fun mount(classLoader: ClassLoader) {
        val taskClass = findFeatureClass(taskClassCandidates, classLoader) { clazz ->
            ViewGroup::class.java.isAssignableFrom(clazz) &&
                findMethod(clazz, "getTaskViewId", Int::class.javaPrimitiveType) != null
        } ?: error("未找到符合 TaskView 公开契约的类")

        val recentsClass = findFeatureClass(recentsClassCandidates, classLoader) { clazz ->
            ViewGroup::class.java.isAssignableFrom(clazz) &&
                findScrollForPage(clazz) != null
        } ?: error("未找到符合 RecentsView 公开契约的类")

        val pagedOrientationHandler = findMethodsInHierarchy(recentsClass).firstOrNull { method ->
            method.name == "getPagedOrientationHandler" &&
                method.parameterTypes.isEmpty() && method.returnType != Void.TYPE
        }?.apply { isAccessible = true }
        val orientationRotation = pagedOrientationHandler?.returnType?.let { handlerClass ->
            findMethod(handlerClass, "getRotation", Int::class.javaPrimitiveType)
        }

        val hooks = ResolvedHooks(
            taskClass = taskClass,
            getScrollForPage = findScrollForPage(recentsClass) ?: error("缺少 getScrollForPage"),
            showAsGrid = findMethod(recentsClass, "showAsGrid", Boolean::class.javaPrimitiveType),
            isSplitSelectionActive = findMethod(recentsClass, "isSplitSelectionActive", Boolean::class.javaPrimitiveType),
            getHomeTaskView = findMethod(recentsClass, "getHomeTaskView", taskClass),
            horizontalOffsetProperty = findNoArgMethod(taskClass, "getHorizontalOffsetTranslationProperty"),
            primaryTaskOffsetProperty = findNoArgMethod(taskClass, "getPrimaryTaskOffsetTranslationProperty"),
            taskComponent = findNoArgMethod(taskClass, "getTaskFlowComponent"),
            pagedOrientationHandler = pagedOrientationHandler,
            orientationRotation = orientationRotation,
        )

        hookLayoutCallbacks(recentsClass, hooks)
        hookStateCallbacks(recentsClass, hooks)

        Logger.i(
            TAG,
            "已挂载: recents=${recentsClass.name}, task=${taskClass.name}, " +
                "独立位移属性=${hooks.horizontalOffsetProperty != null}, " +
                "方向处理器=${pagedOrientationHandler != null}",
        )
    }

    // === Hooks ===

    private fun hookLayoutCallbacks(recentsClass: Class<*>, hooks: ResolvedHooks) {
        val dispatchDraw = requireMethod(recentsClass, "dispatchDraw", Void.TYPE, Canvas::class.java)
        XposedBridge.hookMethod(dispatchDraw, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val recents = param.thisObject as? ViewGroup ?: return
                val state = stateFor(recents)
                val allowPageRebuild = state.pageRebuildPending
                state.pageRebuildPending = false
                applyStack(recents, hooks, allowPageRebuild)
            }
        })

        val onLayout = requireMethod(
            recentsClass, "onLayout", Void.TYPE,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        XposedBridge.hookMethod(onLayout, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                requestStackApply(param.thisObject as? ViewGroup ?: return, rebuildPages = true)
            }
        })

        val onScrollChanged = requireMethod(
            recentsClass, "onScrollChanged", Void.TYPE,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        XposedBridge.hookMethod(onScrollChanged, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                requestStackApply(param.thisObject as? ViewGroup ?: return, rebuildPages = false)
            }
        })
    }

    private fun hookStateCallbacks(recentsClass: Class<*>, hooks: ResolvedHooks) {
        findMethod(recentsClass, "setOverviewStateEnabled", Void.TYPE, Boolean::class.javaPrimitiveType)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    stateFor(recents).overviewEnabled = param.args[0] as? Boolean ?: false
                    requestStackApply(recents, rebuildPages = true)
                }
            })
        }
        findMethod(recentsClass, "setFullscreenProgress", Void.TYPE, Float::class.javaPrimitiveType)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    stateFor(recents).fullscreenProgress = ((param.args[0] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                    requestStackApply(recents, rebuildPages = true)
                }
            })
        }
        findMethod(recentsClass, "setContentAlpha", Void.TYPE, Float::class.javaPrimitiveType)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    stateFor(recents).contentAlpha = ((param.args[0] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                    requestStackApply(recents, rebuildPages = false)
                }
            })
        }
    }

    /** 事件源只置标志并请求下一帧重绘，真正重算合并到 dispatchDraw 前一次。 */
    private fun requestStackApply(recents: ViewGroup, rebuildPages: Boolean) {
        if (rebuildPages) stateFor(recents).pageRebuildPending = true
        recents.postInvalidateOnAnimation()
    }

    // === 主循环 ===

    private fun applyStack(recents: ViewGroup, hooks: ResolvedHooks, allowPageRebuild: Boolean) {
        val state = stateFor(recents)
        if (state.applying) return
        state.applying = true
        try {
            val active = state.overviewEnabled || state.contentAlpha > EPSILON
            val unsupported = invokeBoolean(hooks.showAsGrid, recents) ||
                invokeBoolean(hooks.isSplitSelectionActive, recents)
            if (!active || unsupported || recents.width <= 0 || recents.height <= 0) {
                resetAllTransforms(recents, state, hooks)
                state.rotation = -1
                state.cachedPages = null
                return
            }

            val rotation = rotationFor(recents, hooks)
            if (state.rotation != rotation) {
                resetAllTransforms(recents, state, hooks)
                state.rotation = rotation
                state.cachedPages = null
            }
            val landscape = RecentsRotationGeometry.isLandscape(rotation)

            val pages = if (!allowPageRebuild &&
                state.cachedPages != null &&
                state.cachedPagesChildCount == recents.childCount &&
                state.cachedPagesRotation == rotation
            ) {
                state.cachedPages!!
            } else {
                collectTaskPages(recents, hooks, state, rotation).also {
                    state.cachedPages = it
                    state.cachedPagesChildCount = recents.childCount
                    state.cachedPagesRotation = rotation
                }
            }
            if (pages.isEmpty()) return

            val cardPrimarySize = pages.firstNotNullOfOrNull { page ->
                primarySize(page.view, rotation).takeIf { it > 0f }
            } ?: return

            val logicalPrimaryScroll = primaryScroll(recents, rotation)
            val physicalPrimaryScroll = RecentsRotationGeometry.logicalToPhysical(logicalPrimaryScroll, rotation)
            val scrollPosition = calculateScrollPosition(pages, physicalPrimaryScroll)
            val maxPosition = (pages.size - 1).coerceAtLeast(0).toFloat()
            val clampedPosition = scrollPosition.coerceIn(0f, maxPosition)
            val overscroll = scrollPosition - clampedPosition
            // 稳定 overview 态，堆叠量恒为 1（无入场渐变）。
            val stackLayoutAmount = 1f
            val visualCenter = if (landscape) 0f else recents.width / 2f

            pages.forEachIndexed { ordinal, page ->
                val task = page.view
                val taskState = state.taskStates.getOrPut(task) { TaskVisualState(task.translationZ) }
                val nativePrimaryTranslation = removeCustomPrimaryOffset(task, taskState, hooks, rotation)
                val relativePosition = ordinal.toFloat() - clampedPosition
                val visual = stackVisual(visualCenter, cardPrimarySize, relativePosition, overscroll, ordinal.toFloat())

                updateStackPivot(task, taskState, relativePosition < -EPSILON)
                val customPrimaryOffset = if (landscape) {
                    val logicalDelta = page.logicalPageScroll - logicalPrimaryScroll + nativePrimaryTranslation
                    val nativePhysicalOffset = RecentsRotationGeometry.logicalToPhysical(logicalDelta, rotation)
                    RecentsRotationGeometry.physicalToLogical(visual.centerX - nativePhysicalOffset, rotation) * stackLayoutAmount
                } else {
                    val nativeCenterX = task.left + task.width / 2f - recents.scrollX + nativePrimaryTranslation
                    (visual.centerX - nativeCenterX) * stackLayoutAmount
                }
                applyCustomPrimaryOffset(task, taskState, hooks, rotation, customPrimaryOffset)
                applyScale(task, taskState, lerp(1f, visual.scale, stackLayoutAmount))
                applyDepthOrder(task, taskState, visual.depthOrder, stackLayoutAmount)
                applyTaskAlpha(task, taskState, lerp(1f, visual.alpha, stackLayoutAmount))
            }
        } catch (throwable: Throwable) {
            Logger.once(TAG, "运行时降级: ${throwable.javaClass.simpleName}: ${throwable.message}")
        } finally {
            state.applying = false
        }
    }

    // === 闭式几何（照 4933a65）===

    private fun stackVisual(
        centerX: Float,
        cardWidth: Float,
        relativePosition: Float,
        overscroll: Float,
        depthOrder: Float,
    ): StackVisual {
        val alpha = when {
            relativePosition >= -2f -> 1f
            relativePosition <= -3f -> 0f
            else -> 3f + relativePosition
        }
        return StackVisual(
            centerX = cardCenterX(centerX, cardWidth, relativePosition, overscroll),
            scale = depthScale(relativePosition) * overscrollSinkScale(relativePosition, overscroll),
            alpha = alpha,
            depthOrder = depthOrder,
        )
    }

    private fun cardCenterX(centerX: Float, cardWidth: Float, relativePosition: Float, overscroll: Float): Float {
        val leftPeek = cardWidth * LEFT_PEEK_FACTOR
        val rightSpacing = cardWidth * RIGHT_SPACING_FACTOR
        val baseX = if (relativePosition <= 0f) {
            val distance = -relativePosition
            centerX - leftPeek * (1f - LEFT_DECAY.pow(distance)) / (1f - LEFT_DECAY)
        } else {
            centerX + relativePosition.pow(RIGHT_PARALLAX_EXPONENT) * rightSpacing
        }
        if (abs(overscroll) <= EPSILON) return baseX
        val weight = if (overscroll < 0f) {
            val distance = relativePosition.coerceAtLeast(0f)
            0.72f + 0.55f * (distance / (distance + 0.6f))
        } else {
            val distance = (-relativePosition).coerceAtLeast(0f)
            0.65f / (distance + 1f)
        }
        val absOverscroll = abs(overscroll)
        val damped = absOverscroll / (1f + absOverscroll * 0.8f)
        return baseX - overscroll.sign * damped * rightSpacing * weight
    }

    private fun depthScale(relativePosition: Float): Float {
        val depthAmount = (FOCUSED_SCALE - MIN_LEFT_SCALE) * (1f - LEFT_SCALE_DECAY.pow(abs(relativePosition)))
        return (FOCUSED_SCALE + depthAmount * relativePosition.sign).coerceIn(MIN_LEFT_SCALE, FOCUSED_SCALE)
    }

    private fun overscrollSinkScale(relativePosition: Float, overscroll: Float): Float {
        if (overscroll <= 0f) return 1f
        val distance = (-relativePosition).coerceAtLeast(0f)
        val sinkWeight = 0.3f + 0.7f * (distance / (distance + 0.8f))
        val damped = overscroll / (1f + overscroll * 0.8f)
        return 1f - damped * 0.15f * sinkWeight
    }

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    // === 变换应用（带脏检查，写前比 EPSILON）===

    /** 读回原生主轴位移（剥离我们上帧加的偏移），返回原生基准值。 */
    private fun removeCustomPrimaryOffset(task: View, state: TaskVisualState, hooks: ResolvedHooks, rotation: Int): Float {
        val property = resolveOffsetProperty(task, state, hooks, rotation)
        if (property != null) {
            val expected = state.nativeStackOffset + state.customPrimaryOffset
            val current = property.get(task)
            if (abs(current - expected) > EPSILON) {
                state.nativeStackOffset = current
                state.customPrimaryOffset = 0f
            }
            return state.nativeStackOffset
        }
        val current = primaryTranslation(task, rotation)
        if (state.lastAppliedPrimaryTranslation.isNaN() || abs(current - state.lastAppliedPrimaryTranslation) > EPSILON) {
            state.nativePrimaryTranslation = current
        }
        return state.nativePrimaryTranslation
    }

    private fun applyCustomPrimaryOffset(task: View, state: TaskVisualState, hooks: ResolvedHooks, rotation: Int, offset: Float) {
        val property = resolveOffsetProperty(task, state, hooks, rotation)
        if (property != null) {
            if (abs(offset - state.customPrimaryOffset) > EPSILON) {
                property.set(task, state.nativeStackOffset + offset)
                state.customPrimaryOffset = offset
            }
            return
        }
        val translation = state.nativePrimaryTranslation + offset
        setPrimaryTranslation(task, rotation, translation)
        state.lastAppliedPrimaryTranslation = translation
    }

    private fun resolveOffsetProperty(task: View, state: TaskVisualState, hooks: ResolvedHooks, rotation: Int): FloatProperty<Any>? {
        if (state.offsetResolved && state.offsetRotation == rotation) return state.offsetProperty
        state.offsetResolved = true
        state.offsetRotation = rotation
        val method = if (RecentsRotationGeometry.isLandscape(rotation)) hooks.primaryTaskOffsetProperty else hooks.horizontalOffsetProperty
        @Suppress("UNCHECKED_CAST")
        state.offsetProperty = try { method?.invoke(task) as? FloatProperty<Any> } catch (_: Throwable) { null }
        state.nativeStackOffset = try { state.offsetProperty?.get(task) ?: 0f } catch (_: Throwable) { 0f }
        state.customPrimaryOffset = 0f
        return state.offsetProperty
    }

    private fun applyScale(task: View, state: TaskVisualState, factor: Float) {
        if (state.lastAppliedScale.isNaN() || abs(task.scaleX - state.lastAppliedScale) > EPSILON) {
            state.nativeScale = task.scaleX
        }
        val scale = state.nativeScale * factor
        if (abs(task.scaleX - scale) > EPSILON) { task.scaleX = scale; task.scaleY = scale }
        state.lastAppliedScale = scale
    }

    private fun applyTaskAlpha(task: View, state: TaskVisualState, factor: Float) {
        if (state.lastAppliedAlpha.isNaN() || abs(task.alpha - state.lastAppliedAlpha) > EPSILON) {
            state.nativeAlpha = task.alpha
        }
        val alpha = state.nativeAlpha * factor
        if (abs(task.alpha - alpha) > EPSILON) task.alpha = alpha
        state.lastAppliedAlpha = alpha
    }

    private fun applyDepthOrder(task: View, state: TaskVisualState, physicalOrder: Float, layoutAmount: Float) {
        val step = DEPTH_Z_STEP_DP * task.resources.displayMetrics.density
        val z = state.initialTranslationZ + (physicalOrder + 1) * step * layoutAmount
        if (abs(task.translationZ - z) > EPSILON) task.translationZ = z
    }

    private fun updateStackPivot(task: View, state: TaskVisualState, useCenter: Boolean) {
        if (useCenter) {
            if (!state.centerPivotApplied) {
                state.nativePivotX = task.pivotX
                state.nativePivotY = task.pivotY
                state.centerPivotApplied = true
            }
            val cx = task.width / 2f; val cy = task.height / 2f
            if (abs(task.pivotX - cx) > EPSILON) task.pivotX = cx
            if (abs(task.pivotY - cy) > EPSILON) task.pivotY = cy
        } else if (state.centerPivotApplied) {
            task.pivotX = state.nativePivotX
            task.pivotY = state.nativePivotY
            state.centerPivotApplied = false
        }
    }

    private fun resetAllTransforms(recents: ViewGroup, state: RecentsState, hooks: ResolvedHooks) {
        val it = state.taskStates.entries.iterator()
        while (it.hasNext()) {
            val (task, ts) = it.next()
            if (task.parent !== recents) { it.remove(); continue }
            val rotation = if (state.rotation >= 0) state.rotation else rotationFor(recents, hooks)
            val property = resolveOffsetProperty(task, ts, hooks, rotation)
            if (property != null && ts.customPrimaryOffset != 0f) {
                try { property.set(task, ts.nativeStackOffset) } catch (_: Throwable) {}
                ts.customPrimaryOffset = 0f
            }
            if (!ts.lastAppliedScale.isNaN()) { task.scaleX = ts.nativeScale; task.scaleY = ts.nativeScale; ts.lastAppliedScale = Float.NaN }
            if (!ts.lastAppliedAlpha.isNaN()) { task.alpha = ts.nativeAlpha; ts.lastAppliedAlpha = Float.NaN }
            if (ts.centerPivotApplied) { task.pivotX = ts.nativePivotX; task.pivotY = ts.nativePivotY; ts.centerPivotApplied = false }
            task.translationZ = ts.initialTranslationZ
            ts.lastAppliedPrimaryTranslation = Float.NaN
        }
    }

    // === 页面收集 ===

    private fun collectTaskPages(recents: ViewGroup, hooks: ResolvedHooks, state: RecentsState, rotation: Int): List<TaskPage> {
        val pages = ArrayList<TaskPage>()
        val homeTask = try { hooks.getHomeTaskView?.invoke(recents) as? View } catch (_: Throwable) { null }
        for (index in 0 until recents.childCount) {
            val child = recents.getChildAt(index)
            if (!hooks.taskClass.isInstance(child)) continue
            state.taskStates.getOrPut(child) { TaskVisualState(child.translationZ) }
            if (child === homeTask || isLauncherTask(child, hooks)) continue
            val logicalPageScroll = (hooks.getScrollForPage.invoke(recents, index) as? Number)?.toFloat() ?: continue
            pages += TaskPage(child, index, logicalPageScroll, RecentsRotationGeometry.logicalToPhysical(logicalPageScroll, rotation))
        }
        if (pages.size <= 1) return pages
        val sorted = pages.sortedBy { it.pageScroll }
        val usable = sorted.zipWithNext().any { (a, b) -> abs(b.pageScroll - a.pageScroll) > 1f }
        if (usable) return sorted
        // pageScroll 不可用时按视图位置回退。
        return pages.map { page ->
            val fallback = if (RecentsRotationGeometry.isLandscape(rotation)) {
                page.view.top + page.view.height / 2f - recents.height / 2f
            } else {
                page.view.left + page.view.width / 2f - recents.width / 2f
            }
            page.copy(logicalPageScroll = fallback, pageScroll = RecentsRotationGeometry.logicalToPhysical(fallback, rotation))
        }.sortedBy { it.pageScroll }
    }

    private fun isLauncherTask(task: View, hooks: ResolvedHooks): Boolean {
        val component = try { hooks.taskComponent?.invoke(task) as? ComponentName } catch (_: Throwable) { null }
        return component?.packageName == TARGET_PACKAGE
    }

    private fun calculateScrollPosition(pages: List<TaskPage>, primaryScroll: Float): Float {
        if (pages.size <= 1) return 0f
        if (primaryScroll <= pages.first().pageScroll) {
            return (primaryScroll - pages[0].pageScroll) / safeSpacing(pages[0].pageScroll, pages[1].pageScroll)
        }
        for (i in 0 until pages.lastIndex) {
            val start = pages[i].pageScroll; val end = pages[i + 1].pageScroll
            if (primaryScroll <= end) return i + ((primaryScroll - start) / safeSpacing(start, end)).coerceIn(0f, 1f)
        }
        val last = pages.lastIndex
        return last + (primaryScroll - pages[last].pageScroll) / safeSpacing(pages[last - 1].pageScroll, pages[last].pageScroll)
    }

    private fun safeSpacing(a: Float, b: Float): Float = (b - a).let { if (abs(it) < 1f) 1f else it }

    // === 方向/滚动/位移原语 ===

    private fun rotationFor(recents: ViewGroup, hooks: ResolvedHooks): Int = try {
        val handler = hooks.pagedOrientationHandler?.invoke(recents)
        if (handler != null) {
            val m = hooks.orientationRotation ?: synchronized(runtimeRotationMethods) {
                runtimeRotationMethods.getOrPut(handler.javaClass) {
                    findMethodsInHierarchy(handler.javaClass).firstOrNull {
                        it.name == "getRotation" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
                    }?.apply { isAccessible = true }
                }
            }
            (m?.invoke(handler) as? Number)?.let { return it.toInt() }
            val name = handler.javaClass.name
            when {
                name.contains("Seascape", true) -> return 3
                name.contains("Landscape", true) -> return 1
            }
        }
        recents.display?.rotation ?: 0
    } catch (_: Throwable) { recents.display?.rotation ?: 0 }

    private fun primaryScroll(recents: ViewGroup, rotation: Int): Float =
        if (RecentsRotationGeometry.isLandscape(rotation)) recents.scrollY.toFloat() else recents.scrollX.toFloat()

    private fun primaryTranslation(task: View, rotation: Int): Float =
        if (RecentsRotationGeometry.isLandscape(rotation)) task.translationY else task.translationX

    private fun setPrimaryTranslation(task: View, rotation: Int, value: Float) {
        if (RecentsRotationGeometry.isLandscape(rotation)) task.translationY = value else task.translationX = value
    }

    private fun primarySize(task: View, rotation: Int): Float =
        if (RecentsRotationGeometry.isLandscape(rotation)) task.height.toFloat() else task.width.toFloat()

    private fun invokeBoolean(method: Method?, target: Any): Boolean =
        try { method?.invoke(target) as? Boolean ?: false } catch (_: Throwable) { false }

    // === 反射查找（校验签名，沿继承链）===

    private fun findFeatureClass(candidates: Array<String>, classLoader: ClassLoader, predicate: (Class<*>) -> Boolean): Class<*>? {
        for (name in candidates) {
            val clazz = try { classLoader.loadClass(name) } catch (_: Throwable) { continue }
            if (predicate(clazz)) return clazz
        }
        return null
    }

    private fun requireMethod(clazz: Class<*>, name: String, returnType: Class<*>?, vararg params: Class<*>?): Method =
        findMethod(clazz, name, returnType, *params) ?: error("缺少公开契约方法: ${clazz.name}#$name")

    private fun findMethod(clazz: Class<*>, name: String, returnType: Class<*>?, vararg params: Class<*>?): Method? =
        findMethodsInHierarchy(clazz).firstOrNull {
            it.name == name && it.returnType == returnType && it.parameterTypes.contentEquals(params)
        }?.apply { isAccessible = true }

    /** 单参数 getScrollForPage(int)（Flyme PagedView）；不校验返回类型以兼容 int 变体。 */
    private fun findScrollForPage(clazz: Class<*>): Method? =
        findMethodsInHierarchy(clazz).firstOrNull {
            it.name == "getScrollForPage" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }

    /** 按名+无参查找，不校验返回类型（用于返回 FloatProperty 子类）。 */
    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? =
        findMethodsInHierarchy(clazz).firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }

    private fun findMethodsInHierarchy(clazz: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = clazz
        while (current != null) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun stateFor(recents: ViewGroup): RecentsState =
        stateByRecents.getOrPut(recents) { RecentsState() }

    // === 数据类 ===

    private data class TaskPage(val view: View, val childIndex: Int, val logicalPageScroll: Float, val pageScroll: Float)

    private class StackVisual(val centerX: Float, val scale: Float, val alpha: Float, val depthOrder: Float)

    private class RecentsState {
        var applying = false
        var overviewEnabled = false
        var contentAlpha = 0f
        var fullscreenProgress = 0f
        var rotation = -1
        var pageRebuildPending = false
        var cachedPages: List<TaskPage>? = null
        var cachedPagesChildCount = -1
        var cachedPagesRotation = -1
        val taskStates = IdentityHashMap<View, TaskVisualState>()
    }

    private class TaskVisualState(val initialTranslationZ: Float) {
        var offsetResolved = false
        var offsetRotation = -1
        var offsetProperty: FloatProperty<Any>? = null
        var nativeStackOffset = 0f
        var customPrimaryOffset = 0f
        var nativePrimaryTranslation = 0f
        var lastAppliedPrimaryTranslation = Float.NaN
        var nativeScale = 1f
        var lastAppliedScale = Float.NaN
        var nativeAlpha = 1f
        var lastAppliedAlpha = Float.NaN
        var centerPivotApplied = false
        var nativePivotX = 0f
        var nativePivotY = 0f
    }

    private data class ResolvedHooks(
        val taskClass: Class<*>,
        val getScrollForPage: Method,
        val showAsGrid: Method?,
        val isSplitSelectionActive: Method?,
        val getHomeTaskView: Method?,
        val horizontalOffsetProperty: Method?,
        val primaryTaskOffsetProperty: Method?,
        val taskComponent: Method?,
        val pagedOrientationHandler: Method?,
        val orientationRotation: Method?,
    )
}

package com.karen.flymetool.hook.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.FloatProperty
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
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
    // 头部模糊：仅可见区域内最左侧卡片，低强度、轻微降透明度。
    private const val HEADER_BLUR_RADIUS_DP = 4.2f
    private const val HEADER_BLUR_ALPHA = 0.9f
    // 模糊向外扩散预留：padding = 半径 * 倍数，避免被 overlay bounds 裁切成硬矩形。
    private const val HEADER_BLUR_PADDING_MULTIPLIER = 2f

    private val recentsClassCandidates = arrayOf(
        "com.android.quickstep.views.RecentsView",
        "com.android.quickstep.views.LauncherRecentsView",
    )
    private val taskClassCandidates = arrayOf(
        "com.android.quickstep.views.TaskView",
    )
    private val runtimeRotationMethods = HashMap<Class<*>, Method?>()
    private val stateByRecents = IdentityHashMap<ViewGroup, RecentsState>()
    private val scratchRect = Rect()

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
            isRunningTask = findMethod(taskClass, "isRunningTask", Boolean::class.javaPrimitiveType),
            horizontalOffsetProperty = findNoArgMethod(taskClass, "getHorizontalOffsetTranslationProperty"),
            primaryTaskOffsetProperty = findNoArgMethod(taskClass, "getPrimaryTaskOffsetTranslationProperty"),
            taskComponent = findNoArgMethod(taskClass, "getTaskFlowComponent"),
            pagedOrientationHandler = pagedOrientationHandler,
            orientationRotation = orientationRotation,
        )

        hookLayoutCallbacks(recentsClass, hooks)
        hookStateCallbacks(recentsClass, hooks)
        hookDismissChannel(taskClass, hooks)

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

    /**
     * 接管上滑删除：
     * - setDismissTranslationY(次轴/竖屏Y)= 被删卡飞出，记录它并算删除进度，放行让其飞出。
     * - setDismissTranslationX(主轴/竖屏X)= 原生补位平移，吃掉(置0)，补位改由 applyStack 做 iOS 收拢。
     */
    /**
     * 删除位移分两轴，且轴角色随方向对调（竖屏主X次Y、横屏主Y次X）：
     * - 次轴 dismiss = 被删卡飞出：记录它 + 按“次轴位移/次轴维度”算删除进度，放行。
     * - 主轴 dismiss = 原生补位平移：吃掉(置0)，补位由 applyStack 接管。
     * 两个 setter 都按当前 rotation 判断自己此刻是主轴还是次轴。
     */
    private fun hookDismissChannel(taskClass: Class<*>, hooks: ResolvedHooks) {
        val setY = findMethodInHierarchyNamed(taskClass, "setDismissTranslationY", Float::class.javaPrimitiveType)
        val setX = findMethodInHierarchyNamed(taskClass, "setDismissTranslationX", Float::class.javaPrimitiveType)
        if (setY == null || setX == null) {
            Logger.w(TAG, "未找到 setDismissTranslationX/Y，删除接管跳过")
            return
        }
        // isYAxis: 该 setter 写的是 Y 轴。竖屏次轴=Y、横屏次轴=X。
        for ((method, isYAxis) in listOf(setY to true, setX to false)) {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as? View ?: return
                    val recents = task.parent as? ViewGroup ?: return
                    val landscape = RecentsRotationGeometry.isLandscape(rotationFor(recents, hooks))
                    val isSecondaryAxis = if (landscape) !isYAxis else isYAxis
                    if (isSecondaryAxis) return  // 次轴=被删卡飞出，放行，进度在 after 里算
                    // 主轴 = 原生补位平移，吃掉，交给 applyStack。
                    param.args[0] = 0f
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as? View ?: return
                    val recents = task.parent as? ViewGroup ?: return
                    val landscape = RecentsRotationGeometry.isLandscape(rotationFor(recents, hooks))
                    val isSecondaryAxis = if (landscape) !isYAxis else isYAxis
                    if (!isSecondaryAxis) return
                    val state = stateFor(recents)
                    val value = (param.args[0] as? Number)?.toFloat() ?: 0f
                    // 次轴维度：横屏次轴=X→宽，竖屏次轴=Y→高。
                    val secondaryDim = (if (landscape) task.width else task.height).takeIf { it > 0 } ?: return
                    if (abs(value) > EPSILON) {
                        state.dismissingTask = task
                        state.dismissProgress = (abs(value) / secondaryDim).coerceIn(0f, 1f)
                    } else if (state.dismissingTask === task) {
                        state.dismissingTask = null
                        state.dismissProgress = 0f
                    }
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
            val stackLayoutAmount = 1f
            val visualCenter = if (landscape) 0f else recents.width / 2f

            // dismiss 接管：被删卡从布局序数中剔除，其余卡按“压缩后的序数”重排实现 iOS 收拢补位。
            if (state.dismissingTask?.parent !== recents) {
                state.dismissingTask = null
                state.dismissProgress = 0f
            }
            val dismissing = state.dismissingTask
            val dismissProgress = if (dismissing != null) state.dismissProgress else 0f
            val dismissedOrdinal = if (dismissing != null) pages.indexOfFirst { it.view === dismissing } else -1

            pages.forEachIndexed { ordinal, page ->
                val task = page.view
                val taskState = state.taskStates.getOrPut(task) { TaskVisualState(task.translationZ) }
                val nativePrimaryTranslation = removeCustomPrimaryOffset(task, taskState, hooks, rotation)
                val isDismissing = task === dismissing
                // running task 用独立 live tile surface 渲染，若给它加 offset/scale，TaskView 空白底板
                // (清空+dimming)会与 surface 分离而露出。故让它停在原生位置，不施加我们的位移与缩放。
                val isRunning = invokeBoolean(hooks.isRunningTask, task)

                // 补位规则(拖动中按 dismissProgress 渐进，删除确认后 rebuild 补完剩余距离)：
                //  · 被删卡有左邻(dismissedOrdinal>0)：其左侧卡朝被删卡位置右移(+1)，右侧卡不动。
                //  · 被删卡是最左侧(dismissedOrdinal==0)：无左邻，改由右侧卡整体左移(-1)填补空位。
                val fullOrdinal = ordinal.toFloat()
                val compactOrdinal = when {
                    dismissedOrdinal < 0 -> ordinal.toFloat()
                    dismissedOrdinal == 0 -> if (ordinal > 0) ordinal - 1f else ordinal.toFloat()
                    else -> if (ordinal < dismissedOrdinal) ordinal + 1f else ordinal.toFloat()
                }
                val effectiveOrdinal = if (isDismissing) fullOrdinal else lerp(fullOrdinal, compactOrdinal, dismissProgress)
                val relativePosition = effectiveOrdinal - clampedPosition
                val visual = stackVisual(visualCenter, cardPrimarySize, relativePosition, overscroll, effectiveOrdinal)

                updateStackPivot(task, taskState, relativePosition < -EPSILON)
                // 被删卡的主轴位移交给原生(飞出/回弹)。running task 仍施加 offset(待在堆叠位、与左邻卡
                // 衔接，避免离场时脱节形成缝)，仅跳过 scale——scale 才会让空白底板与 live tile surface 尺寸
                // 不符而露出。
                if (!isDismissing) {
                    val customPrimaryOffset = if (landscape) {
                        val logicalDelta = page.logicalPageScroll - logicalPrimaryScroll + nativePrimaryTranslation
                        val nativePhysicalOffset = RecentsRotationGeometry.logicalToPhysical(logicalDelta, rotation)
                        RecentsRotationGeometry.physicalToLogical(visual.centerX - nativePhysicalOffset, rotation) * stackLayoutAmount
                    } else {
                        val nativeCenterX = task.left + task.width / 2f - recents.scrollX + nativePrimaryTranslation
                        (visual.centerX - nativeCenterX) * stackLayoutAmount
                    }
                    applyCustomPrimaryOffset(task, taskState, hooks, rotation, customPrimaryOffset)
                }
                // running task 恢复原生缩放(factor=1)，其余卡用堆叠缩放。
                applyScale(task, taskState, if (isRunning) 1f else lerp(1f, visual.scale, stackLayoutAmount))
                // Z 序按补位序数，避免删除中左卡盖右卡；被删卡压到最上以自然飞出。
                val depthOrder = if (isDismissing) (pages.size + 1).toFloat() else effectiveOrdinal
                applyDepthOrder(task, taskState, depthOrder, stackLayoutAmount)
                // 被删卡额外按删除进度淡出；其余卡用堆叠 alpha。
                val alpha = if (isDismissing) visual.alpha * (1f - dismissProgress) else visual.alpha
                applyTaskAlpha(task, taskState, lerp(1f, alpha, stackLayoutAmount))
                taskState.frameRelativePosition = relativePosition
                taskState.frameVisibleAlpha = alpha
            }

            // 标题遮挡淡出：仅稳定态计算(入场/删除中略过以省 getGlobalVisibleRect 开销)。
            // 每卡标题被其右邻卡(ordinal+1，上层)覆盖的比例决定 app_name 的 alpha。
            if (stackLayoutAmount >= 1f - EPSILON && dismissing == null) {
                pages.forEach { it.view.getGlobalVisibleRect(state.taskStates.getValue(it.view).bounds) }
                pages.forEachIndexed { ordinal, page ->
                    val taskState = state.taskStates.getValue(page.view)
                    val frontBounds = pages.getOrNull(ordinal + 1)?.let { state.taskStates.getValue(it.view).bounds }
                    applyTitleOcclusion(page.view, taskState, frontBounds, landscape)
                }
                // 头部模糊：只给可见区域内最左侧卡片(rel 最小且仍可见)加低强度模糊，其余清除。
                var blurIndex = -1
                var minRel = Float.MAX_VALUE
                pages.forEachIndexed { index, page ->
                    val ts = state.taskStates.getValue(page.view)
                    if (ts.frameVisibleAlpha > 0.05f && ts.frameRelativePosition < minRel) {
                        minRel = ts.frameRelativePosition
                        blurIndex = index
                    }
                }
                pages.forEachIndexed { index, page ->
                    val ts = state.taskStates.getValue(page.view)
                    applyHeaderBlur(page.view, ts, index == blurIndex)
                }
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

    private fun smoothStep(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 定位并缓存卡片标题(app_name)视图。 */
    private fun resolveTitle(task: View, state: TaskVisualState): View? {
        if (state.titleResolved) return state.titleView
        state.titleResolved = true
        val id = task.resources.getIdentifier("app_name", "id", task.context.packageName)
        state.titleView = if (id != 0) task.findViewById(id) else null
        return state.titleView
    }

    /**
     * 标题遮挡淡出：按被右邻卡覆盖的比例淡出 app_name。visibleFraction=1 全显、0 全遮。
     * frontBounds 为右邻卡的全局可见矩形；null 表示无遮挡卡。
     */
    private fun applyTitleOcclusion(task: View, state: TaskVisualState, frontBounds: Rect?, landscape: Boolean) {
        val title = resolveTitle(task, state) ?: return
        val visibleFraction = if (frontBounds == null) 1f else {
            title.getGlobalVisibleRect(scratchRect)
            val childStart = if (landscape) scratchRect.top else scratchRect.left
            val childEnd = if (landscape) scratchRect.bottom else scratchRect.right
            val frontStart = if (landscape) frontBounds.top else frontBounds.left
            val frontEnd = if (landscape) frontBounds.bottom else frontBounds.right
            val overlaps = childEnd > frontStart && childStart < frontEnd
            if (!overlaps) 1f else {
                val size = (childEnd - childStart).coerceAtLeast(1)
                val covered = (minOf(childEnd, frontEnd) - maxOf(childStart, frontStart)).coerceAtLeast(0)
                (1f - covered.toFloat() / size).coerceIn(0f, 1f)
            }
        }
        val occlusionAlpha = smoothStep(visibleFraction)
        if (state.titleLastApplied.isNaN() || abs(title.alpha - state.titleLastApplied) > EPSILON) {
            state.titleNativeAlpha = title.alpha
        }
        val applied = state.titleNativeAlpha * occlusionAlpha
        if (abs(title.alpha - applied) > EPSILON) title.alpha = applied
        state.titleLastApplied = applied
        val hidden = occlusionAlpha <= 0.02f
        if (hidden && title.visibility == View.VISIBLE) title.visibility = View.INVISIBLE
        else if (!hidden && title.visibility == View.INVISIBLE) title.visibility = View.VISIBLE
    }

    /** 定位并缓存卡片头部图标(icon)视图。 */
    private fun resolveHeader(task: View, state: TaskVisualState): View? {
        if (state.headerResolved) return state.iconView
        state.headerResolved = true
        val pkg = task.context.packageName
        val iconId = task.resources.getIdentifier("icon", "id", pkg)
        state.iconView = if (iconId != 0) task.findViewById(iconId) else null
        // task_head 是图标+标题的父容器，入场淡入由 onSettledProgressUpdated 改写它的 alpha 驱动。
        val headId = task.resources.getIdentifier("task_head", "id", pkg)
        state.taskHeadView = if (headId != 0) task.findViewById(headId) else null
        return state.iconView
    }

    private fun readIconDrawable(icon: View): Drawable? = try {
        XposedHelpers.callMethod(icon, "getDrawable") as? Drawable
    } catch (_: Throwable) { null }

    /**
     * 图标低强度模糊：只作用于最左侧可见卡。图标 drawable 复制画进带 padding 的 overlay，
     * 对 overlay 施加模糊后原图标 alpha 置 0——模糊可向外扩散，不被图标矩形 bounds 裁成硬边。
     */
    private fun applyHeaderBlur(task: View, state: TaskVisualState, enable: Boolean) {
        val icon = resolveHeader(task, state) ?: return
        // overlay 挂到 task_head 的 ViewOverlay：随宿主一起绘制并继承其 alpha，
        // 于是入场淡入(onSettledProgressUpdated 改写 task_head.alpha)自动带上模糊图标，
        // 无需 applyStack 每帧手动追踪，避免动画结束后 overlay 停在中途值。
        val host = state.taskHeadView as? ViewGroup ?: return
        if (!enable) {
            if (state.headerBlurred) clearHeaderBlur(icon, state)
            return
        }
        val source = readIconDrawable(icon)
        if (source == null) { if (state.headerBlurred) clearHeaderBlur(icon, state); return }

        val density = task.resources.displayMetrics.density
        val radius = HEADER_BLUR_RADIUS_DP * density
        val paddingPx = (radius * HEADER_BLUR_PADDING_MULTIPLIER).roundToInt().coerceAtLeast(1)
        val overlay = state.blurOverlay ?: BlurredIconOverlayView(task.context).also { state.blurOverlay = it }
        if (!overlay.updateSource(source)) { if (state.headerBlurred) clearHeaderBlur(icon, state); return }

        val bounds = Rect(0, 0, icon.width, icon.height)
        host.offsetDescendantRectToMyCoords(icon, bounds)
        overlay.layout(bounds.left - paddingPx, bounds.top - paddingPx, bounds.right + paddingPx, bounds.bottom + paddingPx)
        overlay.rotation = icon.rotation
        overlay.scaleX = icon.scaleX
        overlay.scaleY = icon.scaleY
        overlay.pivotX = overlay.width / 2f
        overlay.pivotY = overlay.height / 2f
        overlay.updateDrawableBounds(source.bounds, paddingPx)
        overlay.alpha = HEADER_BLUR_ALPHA

        if (!state.headerBlurred) {
            overlay.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
            state.iconNativeAlpha = icon.alpha
            host.overlay.add(overlay)
            state.overlayAdded = true
            state.headerBlurred = true
        }
        icon.alpha = 0f
    }

    private fun clearHeaderBlur(icon: View, state: TaskVisualState) {
        val host = state.taskHeadView as? ViewGroup
        state.blurOverlay?.let { overlay ->
            if (state.overlayAdded) { host?.overlay?.remove(overlay); state.overlayAdded = false }
            overlay.setRenderEffect(null)
        }
        icon.alpha = state.iconNativeAlpha
        state.headerBlurred = false
    }

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
            // 恢复标题原生 alpha 与可见性。
            if (!ts.titleLastApplied.isNaN()) {
                ts.titleView?.let { title ->
                    title.alpha = ts.titleNativeAlpha
                    if (title.visibility == View.INVISIBLE) title.visibility = View.VISIBLE
                }
                ts.titleLastApplied = Float.NaN
            }
            // 恢复头部模糊。
            if (ts.headerBlurred) {
                ts.iconView?.let { clearHeaderBlur(it, ts) }
            }
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

    /** 按名+参数查找（不校验返回类型）。 */
    private fun findMethodInHierarchyNamed(clazz: Class<*>, name: String, vararg params: Class<*>?): Method? =
        findMethodsInHierarchy(clazz).firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(params)
        }?.apply { isAccessible = true }

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
        // dismiss 接管：正在删除的卡与删除进度(0→1，按被删卡次轴位移/卡高)。
        var dismissingTask: View? = null
        var dismissProgress = 0f
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
        // 标题遮挡淡出：app_name 视图与其原生 alpha 基线。
        var titleResolved = false
        var titleView: View? = null
        var titleNativeAlpha = 1f
        var titleLastApplied = Float.NaN
        val bounds = Rect()
        // 帧内快照：本卡相对滚动位置与可见 alpha，供标题/模糊阶段判定最左可见卡。
        var frameRelativePosition = 0f
        var frameVisibleAlpha = 0f
        // 头部模糊：icon 视图、承接模糊扩散的 overlay 及生效标记。
        var headerResolved = false
        var iconView: View? = null
        var taskHeadView: View? = null
        var iconNativeAlpha = 1f
        var blurOverlay: BlurredIconOverlayView? = null
        var overlayAdded = false
        var headerBlurred = false
    }

    /** 承接图标模糊扩散的视图：把图标 drawable 复制画在带 padding 的空间里，避免被裁成硬矩形。 */
    private class BlurredIconOverlayView(context: Context) : View(context) {
        private var source: Drawable? = null
        private var drawable: Drawable? = null

        fun updateSource(newSource: Drawable): Boolean {
            if (source !== newSource || drawable == null) {
                val copy = newSource.constantState?.newDrawable(resources)?.mutate() ?: return false
                source = newSource
                drawable = copy
            }
            drawable?.let { copy ->
                copy.state = newSource.state
                copy.level = newSource.level
                copy.alpha = newSource.alpha
                copy.layoutDirection = newSource.layoutDirection
                copy.colorFilter = newSource.colorFilter
            }
            return true
        }

        fun updateDrawableBounds(sourceBounds: Rect, paddingPx: Int) {
            drawable?.bounds = Rect(sourceBounds).also { it.offset(paddingPx, paddingPx) }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawable?.draw(canvas)
        }

        override fun hasOverlappingRendering(): Boolean = false
    }

    private data class ResolvedHooks(
        val taskClass: Class<*>,
        val getScrollForPage: Method,
        val showAsGrid: Method?,
        val isSplitSelectionActive: Method?,
        val getHomeTaskView: Method?,
        val isRunningTask: Method?,
        val horizontalOffsetProperty: Method?,
        val primaryTaskOffsetProperty: Method?,
        val taskComponent: Method?,
        val pagedOrientationHandler: Method?,
        val orientationRotation: Method?,
    )
}

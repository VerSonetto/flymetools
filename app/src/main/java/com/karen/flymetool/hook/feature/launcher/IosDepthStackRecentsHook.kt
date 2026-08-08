package com.karen.flymetool.hook.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.FloatProperty
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign


object IosDepthStackRecentsHook : FeatureHook {

    private const val TAG = "IosDepthStackRecents"
    private const val LOGCAT_TAG = "FT_IosRecents"
    private const val TRACE_PLACEHOLDER = false
    private const val TRACE_TASK_FRAMES = false
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
    // 清空模式复位超时：原生清空动画 300ms + 错峰 30ms*卡数，无 dismiss setter 超过该时长即退出。
    private const val DISMISS_MULTI_TIMEOUT_MS = 450L
    // 头部模糊：仅可见区域内最左侧卡片，低强度、轻微降透明度。
    private const val HEADER_BLUR_RADIUS_DP = 4.2f
    private const val HEADER_BLUR_ALPHA = 0.9f
    // 模糊向外扩散预留：padding = 半径 * 倍数，避免被 overlay bounds 裁切成硬矩形。
    private const val HEADER_BLUR_PADDING_MULTIPLIER = 2f
    private const val TAG_PLACEHOLDER_SUPPRESSED = Int.MAX_VALUE - 901
    private const val TAG_REMOTE_TARGETS = Int.MAX_VALUE - 902
    private const val TAG_PLACEHOLDER_RELEASE_PENDING = Int.MAX_VALUE - 903

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
        val handlerClass = pagedOrientationHandler?.returnType
        val orientationRotation = handlerClass?.let { clazz ->
            findMethod(clazz, "getRotation", Int::class.javaPrimitiveType)
        }

        val remoteHandles = findNoArgMethod(recentsClass, "getRemoteTargetHandles")
        val simulatorClass = remoteHandles?.returnType?.componentType?.let { handleClass ->
            findNoArgMethod(handleClass, "getTaskViewSimulator")?.returnType
        }
        val animatedFloatClass = simulatorClass?.declaredFields?.firstOrNull { it.name == "taskPrimaryTranslation" }?.type

        val hooks = ResolvedHooks(
            taskClass = taskClass,
            getScrollForPage = findScrollForPage(recentsClass) ?: error("缺少 getScrollForPage"),
            showAsGrid = findMethod(recentsClass, "showAsGrid", Boolean::class.javaPrimitiveType),
            isSplitSelectionActive = findMethod(recentsClass, "isSplitSelectionActive", Boolean::class.javaPrimitiveType),
            getHomeTaskView = findMethod(recentsClass, "getHomeTaskView", taskClass),
            isRunningTask = findMethod(taskClass, "isRunningTask", Boolean::class.javaPrimitiveType),
            applyScale = findMethod(taskClass, "applyScale", Void.TYPE),
            horizontalOffsetProperty = findNoArgMethod(taskClass, "getHorizontalOffsetTranslationProperty"),
            primaryTaskOffsetProperty = findNoArgMethod(taskClass, "getPrimaryTaskOffsetTranslationProperty"),
            taskComponent = findNoArgMethod(taskClass, "getTaskFlowComponent"),
            pagedOrientationHandler = pagedOrientationHandler,
            orientationRotation = orientationRotation,
            getRunningTaskView = findNoArgMethod(recentsClass, "getRunningTaskView"),
            getEnableDrawingLiveTile = findMethod(recentsClass, "getEnableDrawingLiveTile", Boolean::class.javaPrimitiveType),
            getRemoteTargetHandles = remoteHandles,
            // live tile 通道是纯字段透传（getXxx(){ return mXxx; }），字段优先免去每帧 invoke。
            enableDrawingLiveTileField = findField(recentsClass, "mEnableDrawingLiveTile"),
            remoteTargetHandlesField = findField(recentsClass, "mRemoteTargetHandles"),
            getTaskViewSimulator = remoteHandles?.returnType?.componentType?.let { findNoArgMethod(it, "getTaskViewSimulator") },
            taskPrimaryTranslationField = findField(simulatorClass, "taskPrimaryTranslation"),
            taskSecondaryTranslationField = findField(simulatorClass, "taskSecondaryTranslation"),
            animatedFloatValueField = findField(animatedFloatClass, "value"),
            // Flyme 退桌动画驱动 ADJACENT_PAGE_SCALE(0→1)，contentAlpha 直到动画结束才置 0。
            adjacentPageScaleField = findField(recentsClass, "mAdjacentPageScale"),
        )

        hookLayoutCallbacks(recentsClass, hooks)
        hookStateCallbacks(recentsClass, hooks)
        hookPageScaleUpdates(recentsClass, hooks)
        hookRemoteTargetLifecycle(recentsClass, hooks)
        hookPlaceholderDrawing(classLoader)
        hookScreenshotSwitch(taskClass)
        hookDismissChannel(taskClass, hooks)
        hookDismissAll(recentsClass)
        hookReset(recentsClass, hooks)

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
                    stateFor(recents).apply {
                        fullscreenProgress = ((param.args[0] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                        fullscreenProgressKnown = true
                    }
                    // 入场动画中 setFullscreenProgress 每帧回调：页面几何(mPageScrolls)只随
                    // onLayout 变化，子卡增减已由 cachedPagesChildCount 兜底，无需每帧重建。
                    requestStackApply(recents, rebuildPages = false)
                }
            })
        }
        findMethod(recentsClass, "setContentAlpha", Void.TYPE, Float::class.javaPrimitiveType)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    val alpha = ((param.args[0] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                    val state = stateFor(recents)
                    state.contentAlpha = alpha
                    // contentAlpha 降到 0 后 setVisibility(GONE) 会立即生效，
                    // dispatchDraw 不再被触发，applyStack/resetAllTransforms 无法
                    // 通过常规路径清零 horizontalOffsetTranslationX 等堆叠通道。
                    // 这里在 afterHook 中立即执行一次清理，确保卡片回到原生位置。
                    if (alpha <= EPSILON && !state.applying) {
                        resetAllTransforms(recents, state, hooks)
                        state.rotation = -1
                        state.cachedPages = null
                        state.cachedAxis = null
                        state.detachFadeActive = false
                        state.lastAdjacentPageScale = 0f
                        state.adjacentPageScale = 0f
                    } else {
                        requestStackApply(recents, rebuildPages = false)
                    }
                }
            })
        }
    }

    private fun hookPageScaleUpdates(recentsClass: Class<*>, hooks: ResolvedHooks) {
        findMethod(recentsClass, "updatePageScales", Void.TYPE)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    val state = stateFor(recents)
                    val scale = readAdjacentPageScale(recents, hooks)
                    // 用 scale 升降区分入场(1→0) / 退桌(0→1)，只在退桌同帧 apply。
                    if (scale > state.lastAdjacentPageScale + 0.001f && scale > 0.02f) {
                        state.detachFadeActive = true
                    }
                    if (scale <= 0.02f) {
                        state.detachFadeActive = false
                    }
                    state.lastAdjacentPageScale = scale
                    state.adjacentPageScale = scale
                    // 入场/稳态 updatePageScales 很密：只 request，真正重算并进 dispatchDraw，
                    // 避免与 dispatchDraw 双倍 applyStack 掉帧。
                    // 退桌必须同帧收敛，否则 exitFade 慢一帧会看见堆叠卡滞留。
                    if (state.detachFadeActive && !state.applying) {
                        applyStack(recents, hooks, allowPageRebuild = false)
                    } else {
                        requestStackApply(recents, rebuildPages = false)
                    }
                }
            })
        } ?: Logger.w(TAG, "未找到 updatePageScales，悬浮卡片缩放稳定跳过")
    }

    private fun readAdjacentPageScale(recents: ViewGroup, hooks: ResolvedHooks): Float {
        val field = hooks.adjacentPageScaleField ?: return 0f
        return try {
            (field.get(recents) as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        } catch (_: Throwable) {
            0f
        }
    }

    private fun hookRemoteTargetLifecycle(recentsClass: Class<*>, hooks: ResolvedHooks) {
        findMethodsInHierarchy(recentsClass).firstOrNull {
            it.name == "setRecentsAnimationTargets" && it.parameterTypes.size == 2 && it.returnType == Void.TYPE
        }?.apply { isAccessible = true }?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    val active = param.args.getOrNull(1) != null
                    recents.setTag(TAG_REMOTE_TARGETS, active)
                    trace(recents, "setRecentsAnimationTargets active=$active childCount=${recents.childCount}")
                }
            })
        }
        findMethod(recentsClass, "cleanupRemoteTargets", Void.TYPE)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    trace(recents, "cleanupRemoteTargets before")
                    recents.setTag(TAG_REMOTE_TARGETS, false)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    trace(recents, "cleanupRemoteTargets after")
                    markPlaceholderReleasePending(recents, hooks)
                    requestStackApply(recents, rebuildPages = false)
                }
            })
        }
    }

    /** running task 使用 live tile 时，抑制其无缩略图占位底板绘制，避免横向手势露出白卡。 */
    private fun hookPlaceholderDrawing(classLoader: ClassLoader) {
        val thumbnailClass = try {
            classLoader.loadClass("com.android.quickstep.views.TaskThumbnailViewDeprecated")
        } catch (_: Throwable) {
            return
        }
        findMethod(thumbnailClass, "onDraw", Void.TYPE, Canvas::class.java)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val thumbnail = param.thisObject as? View ?: return
                    val suppressed = thumbnail.getTag(TAG_PLACEHOLDER_SUPPRESSED) == true
                    if (suppressed && hasThumbnailView(thumbnail)) {
                        releasePlaceholderFromThumbnail(thumbnail)
                        trace(thumbnail, "thumbnail onDraw release-before-draw hasThumb=true")
                        return
                    }
                    if (suppressed) {
                        trace(thumbnail, "thumbnail onDraw suppressed hasThumb=false")
                        param.result = null
                    }
                }
            })
        }
        findMethodsInHierarchy(thumbnailClass).filter {
            it.name == "setThumbnail" && it.parameterTypes.size in 2..3 && it.returnType == Void.TYPE
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val thumbnail = param.thisObject as? View ?: return
                    val hasThumb = hasThumbnailView(thumbnail)
                    trace(thumbnail, "setThumbnail after pending=${thumbnail.getTag(TAG_PLACEHOLDER_RELEASE_PENDING)} suppressed=${thumbnail.getTag(TAG_PLACEHOLDER_SUPPRESSED)} hasThumb=$hasThumb args=${param.args.size}")
                    if (thumbnail.getTag(TAG_PLACEHOLDER_RELEASE_PENDING) == true && hasThumb) {
                        releasePlaceholderFromThumbnail(thumbnail)
                    }
                }
            })
        }
    }

    private fun hookScreenshotSwitch(taskClass: Class<*>) {
        findMethodsInHierarchy(taskClass).filter {
            it.name == "setShouldShowScreenshot" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType && it.returnType == Void.TYPE
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as? View ?: return
                    val shouldShow = param.args.firstOrNull() as? Boolean ?: return
                    trace(task, "setShouldShowScreenshot after shouldShow=$shouldShow pending=${task.getTag(TAG_PLACEHOLDER_RELEASE_PENDING)}")
                    if (shouldShow) releasePlaceholderTask(task)
                }
            })
        }
    }

    /**
     * 删除位移分两轴，且轴角色随方向对调（竖屏主X次Y、横屏主Y次X）：
     * - 次轴 dismiss = 被删卡飞出：记录它 + 按“次轴位移/次轴维度”算删除进度，放行。
     * - 主轴 dismiss = 原生补位平移：吃掉(置0)，补位由 applyStack 接管。
     * 两个 setter 都按当前 axis 判断自己此刻是主轴还是次轴。
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
                    val axis = axisFor(recents, hooks)
                    val isSecondaryAxis = if (axis.landscape) !isYAxis else isYAxis
                    if (isSecondaryAxis) return  // 次轴=被删卡飞出，放行，进度在 after 里算
                    // 主轴 = 原生补位平移，吃掉，交给 applyStack。
                    param.args[0] = 0f
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as? View ?: return
                    val recents = task.parent as? ViewGroup ?: return
                    val state = stateFor(recents)
                    // 动画期间复用 applyStack 缓存的 axis，避免每帧每卡反射 getPagedOrientationHandler。
                    val axis = if (state.cachedAxis != null && state.rotation >= 0) {
                        state.cachedAxis!!
                    } else {
                        axisFor(recents, hooks)
                    }
                    val isSecondaryAxis = if (axis.landscape) !isYAxis else isYAxis
                    if (!isSecondaryAxis) return
                    val value = (param.args[0] as? Number)?.toFloat() ?: 0f
                    // 次轴维度：横屏次轴=X→宽，竖屏次轴=Y→高。
                    val secondaryDim = axis.childSecondarySize(task).takeIf { it > 0f } ?: return
                    if (abs(value) > EPSILON) {
                        // 兜底：若 preDismissAllTasks 入口未命中，错峰动画下多卡并发仍会击穿单槽
                        // dismissingTask（顶 z/补位/渐淡互相打架）。检测到第二张不同卡并发移动
                        // 即整体放弃单卡接管：堆叠几何保持，飞出交还原生。
                        if (state.dismissingTask != null && state.dismissingTask !== task) {
                            state.multiDismissActive = true
                            state.dismissingTask = null
                            state.dismissProgress = 0f
                        } else if (!state.multiDismissActive) {
                            state.dismissingTask = task
                            state.dismissProgress = (abs(value) / secondaryDim).coerceIn(0f, 1f)
                        }
                        state.lastDismissMoveTime = SystemClock.uptimeMillis()
                    } else if (state.dismissingTask === task) {
                        state.dismissingTask = null
                        state.dismissProgress = 0f
                    }
                    requestStackApply(recents, rebuildPages = false)
                }
            })
        }
    }

    /**
     * 清空最近任务入口：清空按钮 → dismissAllTasks() → 第一行 preDismissAllTasks()（反编译对照，
     * protected 空钩子，CTS/非 CTS 两条分支都经过）。直接置清空模式，不依赖并发检测——
     * 初始态（未右滑）currentPage=0 时动画范围只有 [0,1]，running task 跳过动画后可能只剩
     * 一张卡移动，永远不满足并发条件，单槽接管仍会把移动卡顶到最高 z。
     */
    private fun hookDismissAll(recentsClass: Class<*>) {
        findMethod(recentsClass, "preDismissAllTasks", Void.TYPE)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    val state = stateFor(recents)
                    state.multiDismissActive = true
                    state.dismissingTask = null
                    state.dismissProgress = 0f
                    state.lastDismissMoveTime = SystemClock.uptimeMillis()
                    Logger.i(TAG, "清空最近任务：进入清空模式，飞出交还原生")
                }
            })
        } ?: Logger.w(TAG, "未找到 preDismissAllTasks()，清空模式降级为并发检测")
    }

    /**
     * RecentsView.reset() 在 onStateTransitionComplete(NORMAL) 中被调用，
     * 它会调 resetTaskVisuals() → resetViewTransforms()，但原生不清零
     * Hook 专用的 horizontalOffsetTranslationX 通道。这里作为安全兜底，
     * 确保 reset 后所有堆叠位移通道都被清零。
     */
    private fun hookReset(recentsClass: Class<*>, hooks: ResolvedHooks) {
        findMethod(recentsClass, "reset", Void.TYPE)?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val recents = param.thisObject as? ViewGroup ?: return
                    val state = stateFor(recents)
                    if (state.applying) return
                    resetAllTransforms(recents, state, hooks)
                    state.rotation = -1
                    state.cachedPages = null
                    state.cachedAxis = null
                }
            })
        } ?: Logger.w(TAG, "未找到 reset()，安全清理跳过")
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
            // dispatchDraw 路径也采样一次，防止只靠 updatePageScales 漏帧。
            state.adjacentPageScale = readAdjacentPageScale(recents, hooks)
            val active = state.overviewEnabled || state.contentAlpha > EPSILON
            val unsupported = invokeBoolean(hooks.showAsGrid, recents) ||
                invokeBoolean(hooks.isSplitSelectionActive, recents)
            if (!active || unsupported || recents.width <= 0 || recents.height <= 0) {
                resetAllTransforms(recents, state, hooks)
                state.rotation = -1
                state.cachedPages = null
                state.cachedAxis = null
                return
            }

            val axis = if (state.cachedAxis != null && state.rotation >= 0) {
                // 同旋转下复用 axis，避免每帧反射 getPagedOrientationHandler。
                state.cachedAxis!!
            } else {
                axisFor(recents, hooks)
            }
            val rotation = axis.rotation
            if (state.rotation != rotation) {
                resetAllTransforms(recents, state, hooks)
                state.rotation = rotation
                state.cachedPages = null
                state.cachedAxis = axis
            } else if (state.cachedAxis == null) {
                state.cachedAxis = axis
            }

            val pages = if (!allowPageRebuild &&
                state.cachedPages != null &&
                state.cachedPagesChildCount == recents.childCount &&
                state.cachedPagesRotation == rotation
            ) {
                state.cachedPages!!
            } else {
                collectTaskPages(recents, hooks, state, axis).also {
                    state.cachedPages = it
                    state.cachedPagesChildCount = recents.childCount
                    state.cachedPagesRotation = rotation
                }
            }
            if (pages.isEmpty()) return

            val cardPrimarySize = pages.firstNotNullOfOrNull { page ->
                axis.childPrimarySize(page.view).takeIf { it > 0f }
            } ?: return

            // pageScroll 与 primaryScroll 同处 handler 主轴逻辑空间；Landscape 的 RTL 已体现在 getScrollForPage。
            val primaryScroll = axis.primaryScroll(recents)
            val scrollPosition = calculateScrollPosition(pages, primaryScroll)
            val maxPosition = (pages.size - 1).coerceAtLeast(0).toFloat()
            val clampedPosition = scrollPosition.coerceIn(0f, maxPosition)
            val overscroll = scrollPosition - clampedPosition
            // Flyme 退桌时 contentAlpha 常保持 1，直到 spring 结束才置 0；
            // 真正驱动退桌的是 mAdjacentPageScale(0→1)。堆叠强度按两者共同收敛：
            // amount = contentAlpha * (1 - adjacentPageScale)。
            // 这样退桌 spring 过程中左侧卡会同步收回原生位，而不是卡在堆叠位直到 contentAlpha 突变。
            // 退桌时绝不能把 stackLayoutAmount 收到 0：左侧卡原生 page 位在屏外左侧，
            // 收回偏移会让它们横向飞过屏幕（录屏第二版现象）。保持堆叠几何，
            // 仅用 exitFade 把整组卡 alpha 淡出。
            val stackLayoutAmount = 1f
            // 只在 scale 抬升（退桌）时启用 exitFade；入场 scale 从高到低时不压暗，
            // 避免入场交会期每帧额外改 alpha 造成掉帧。
            val scale = state.adjacentPageScale
            if (scale > state.lastAdjacentPageScale + 0.001f && scale > 0.02f) {
                state.detachFadeActive = true
            }
            if (scale <= 0.02f) {
                state.detachFadeActive = false
            }
            state.lastAdjacentPageScale = scale
            // native setStableAlpha 已把 contentAlpha 写进 task.alpha（由 applyTaskAlpha 读为 nativeAlpha）。
            // 入场/稳态 exitFade=1，不再二次乘 contentAlpha，避免交会期重复改 alpha 掉帧。
            // 仅退桌时 contentAlpha 常钉在 1，用 (1-adjacentPageScale) 额外淡出堆叠卡。
            val exitFade = if (state.detachFadeActive) {
                (1f - scale).coerceIn(0f, 1f)
            } else {
                1f
            }
            // 已完全淡出时只卸位移通道，避免 GONE 后残留 offset。
            // 不能走完整 resetAllTransforms：它会把 alpha 恢复成 nativeAlpha(常为 1)，
            // 在 contentAlpha 尚未置 0 的最后几帧造成整组卡闪一下。
            if (exitFade <= EPSILON) {
                clearStackOffsetsOnly(recents, state, hooks)
                return
            }

            // dismiss 接管：被删卡从布局序数中剔除，其余卡按“压缩后的序数”重排实现 iOS 收拢补位。
            if (state.dismissingTask?.parent !== recents) {
                state.dismissingTask = null
                state.dismissProgress = 0f
            }
            // 清空模式复位：原生动画结束（无 dismiss setter 超时）后恢复单卡接管能力。
            if (state.multiDismissActive &&
                SystemClock.uptimeMillis() - state.lastDismissMoveTime > DISMISS_MULTI_TIMEOUT_MS
            ) {
                state.multiDismissActive = false
            }
            // multiDismiss 期间屏蔽单卡接管：无顶 z/补位/渐淡，卡片保持堆叠几何由原生统一飞出。
            val dismissing = if (state.multiDismissActive) null else state.dismissingTask
            val dismissProgress = if (dismissing != null) state.dismissProgress else 0f
            val dismissedOrdinal = if (dismissing != null) pages.indexOfFirst { it.view === dismissing } else -1
            fun effectiveOrdinalFor(ordinal: Int, isDismissing: Boolean): Float {
                val fullOrdinal = ordinal.toFloat()
                val compactOrdinal = when {
                    dismissedOrdinal < 0 -> ordinal.toFloat()
                    dismissedOrdinal == 0 -> if (ordinal > 0) ordinal - 1f else ordinal.toFloat()
                    else -> if (ordinal < dismissedOrdinal) ordinal + 1f else ordinal.toFloat()
                }
                return if (isDismissing) fullOrdinal else lerp(fullOrdinal, compactOrdinal, dismissProgress)
            }

            // 横竖屏统一：堆叠目标主轴中心 = 视口主轴中心 + 闭式曲线偏移。
            val visualCenter = axis.primaryViewportSize(recents) / 2f
            if (TRACE_TASK_FRAMES) {
                state.traceFrame++
                trace(
                    recents,
                    "frame=${state.traceFrame} rot=$rotation land=${axis.landscape} pages=${pages.size} " +
                        "scroll=$scrollPosition clamp=$clampedPosition over=$overscroll " +
                        "fullscreen=${state.fullscreenProgress} content=${state.contentAlpha} remote=${recents.getTag(TAG_REMOTE_TARGETS)}",
                )
            }

            // isRunningTask() 内部 = this == getRecentsView().getRunningTaskView()（反编译对照），
            // 每帧每卡 invoke 会重复走视图树与任务扫描；这里每帧只解析一次运行卡再按身份比较。
            val runningTaskView = resolveRunningTaskView(recents, hooks)
            // depthStep 与卡无关（同一 display），每帧提一次，省去每卡 resources 读取。
            val depthStepPx = DEPTH_Z_STEP_DP * recents.resources.displayMetrics.density

            pages.forEachIndexed { ordinal, page ->
                val task = page.view
                val taskState = state.taskStates.getOrPut(task) { TaskVisualState(task.translationZ) }
                val nativePrimaryTranslation = removeCustomPrimaryOffset(task, taskState, hooks, axis)
                val isDismissing = task === dismissing
                // running task 用独立 live tile surface 渲染，若给它加 offset/scale，TaskView 空白底板
                // (清空+dimming)会与 surface 分离而露出。故让它停在原生位置，不施加我们的位移与缩放。
                val isRunning = if (hooks.getRunningTaskView != null) task === runningTaskView
                else invokeBoolean(hooks.isRunningTask, task)
                val suppressPlaceholder = isRunning &&
                    (recents.getTag(TAG_REMOTE_TARGETS) == true || task.getTag(TAG_PLACEHOLDER_RELEASE_PENDING) == true)
                setPlaceholderSuppressedCached(task, taskState, suppressPlaceholder)

                // 补位规则(拖动中按 dismissProgress 渐进，删除确认后 rebuild 补完剩余距离)：
                //  · 被删卡有左邻(dismissedOrdinal>0)：其左侧卡朝被删卡位置右移(+1)，右侧卡不动。
                //  · 被删卡是最左侧(dismissedOrdinal==0)：无左邻，改由右侧卡整体左移(-1)填补空位。
                val effectiveOrdinal = effectiveOrdinalFor(ordinal, isDismissing)
                val relativePosition = effectiveOrdinal - clampedPosition
                // Seascape：逻辑 ordinal 与屏幕左右相反。曲线/缩放/overscroll 在「视觉空间」采样
                // （stackRelative = -rel，使后方 peek + 缩小落在低 Z 侧），再把目标主轴坐标镜像回 layout。
                val stackRelative = if (axis.invertStackDepth) -relativePosition else relativePosition
                val stackOverscroll = if (axis.invertStackDepth) -overscroll else overscroll
                val visual = stackVisual(
                    visualCenter,
                    cardPrimarySize,
                    stackRelative,
                    stackOverscroll,
                )
                val targetPrimaryCenter = if (axis.invertStackDepth) {
                    2f * visualCenter - visual.primaryCenter
                } else {
                    visual.primaryCenter
                }

                updateStackPivot(task, taskState, stackRelative < -EPSILON)
                // 被删卡的主轴位移交给原生(飞出/回弹)。running task 的 live tile surface 同步到相同 offset，
                // 保持原始堆叠手感，同时避免 TaskView 底板与 surface 分离露出纯色占位层。
                if (!isDismissing) {
                    // 视口主轴坐标：nativeCenter = start + size/2 - scroll + nativeOffset
                    val nativeCenter = axis.childPrimaryStart(task) +
                        axis.childPrimarySize(task) / 2f -
                        primaryScroll +
                        nativePrimaryTranslation
                    val customPrimaryOffset = (targetPrimaryCenter - nativeCenter) * stackLayoutAmount
                    applyCustomPrimaryOffset(task, taskState, hooks, axis, customPrimaryOffset)
                    if (isRunning) {
                        syncRunningLiveTile(
                            recents,
                            hooks,
                            axis.primaryTranslation(task),
                            axis.secondaryTranslation(task),
                        )
                    }
                }
                // running task 恢复原生缩放(factor=1)，其余卡用堆叠缩放。
                val scaleFactor = if (isRunning) 1f else lerp(1f, visual.scale, stackLayoutAmount)
                applyScale(task, taskState, hooks, scaleFactor)
                if (TRACE_TASK_FRAMES && (abs(stackRelative) < 0.35f || isRunning)) {
                    val thumbnail = thumbnailViewForTask(task)
                    trace(
                        task,
                        "frame=${state.traceFrame} task ord=$ordinal child=${page.childIndex} rel=$relativePosition stackRel=$stackRelative eff=$effectiveOrdinal " +
                            "run=$isRunning dis=$isDismissing nativePrim=$nativePrimaryTranslation custom=${taskState.customPrimaryOffset} " +
                            "scaleFactor=$scaleFactor nativeScale=${taskState.nativeScale} stableScale=${taskState.lastStableNativeScale} " +
                            "sx=${task.scaleX} sy=${task.scaleY} tx=${task.translationX} ty=${task.translationY} z=${task.translationZ} alpha=${task.alpha} " +
                            "visPrim=$targetPrimaryCenter curvePrim=${visual.primaryCenter} visScale=${visual.scale} visAlpha=${visual.alpha} suppress=${thumbnail?.getTag(TAG_PLACEHOLDER_SUPPRESSED)} " +
                            "pending=${task.getTag(TAG_PLACEHOLDER_RELEASE_PENDING)} hasThumb=${thumbnail?.let { hasThumbnailView(it) }} bounds=${task.left},${task.top},${task.right},${task.bottom}",
                    )
                }
                // Z 序：默认 ordinal 越大越高；Seascape 下 ordinal 与屏幕左右相反，需镜像使右侧更高。
                // 被删卡保持自身堆叠层级（effectiveOrdinal=fullOrdinal）飞出，不顶到最上——
                // 顶 z 会在上滑删除瞬间让卡片短暂违背堆叠层级，清空动画已交还原生，单卡也不该例外。
                val depthOrder = when {
                    axis.invertStackDepth -> (pages.lastIndex - effectiveOrdinal)
                    else -> effectiveOrdinal
                }
                applyDepthOrder(task, taskState, depthOrder, stackLayoutAmount, depthStepPx)
                // 被删卡额外按删除进度淡出；其余卡用堆叠 alpha。
                // exitFade：退桌 spring 中 contentAlpha 常为 1，用 (1-adjacentPageScale) 把卡淡出，
                // 同时保持 stack 位移，避免左侧卡回到屏外原生位而横向滞留。
                val baseAlpha = if (isDismissing) visual.alpha * (1f - dismissProgress) else visual.alpha
                val alpha = baseAlpha * exitFade
                applyTaskAlpha(task, taskState, lerp(1f, alpha, stackLayoutAmount))
                // 帧快照用视觉相对位置：后方 peek 恒为 stackRel 更小的一侧，模糊选择无需再分支。
                taskState.frameRelativePosition = stackRelative
                taskState.frameVisibleAlpha = alpha
            }

            // 标题遮挡淡出：仅稳定态计算(入场/删除中略过以省 getGlobalVisibleRect 开销)。
            // 上层邻卡覆盖 app_name：默认 ordinal+1；Seascape 镜像后上层为 ordinal-1。
            // 标题遮挡/头部模糊依赖 getGlobalVisibleRect + overlay，开销大。
            // 仅 overview 稳定态做：入场交会期(fullscreen>0 或 content 未满)跳过，避免掉帧。
            val settledOverview = !state.detachFadeActive &&
                state.contentAlpha >= 0.98f &&
                (!state.fullscreenProgressKnown || state.fullscreenProgress <= 0.02f) &&
                state.adjacentPageScale <= 0.02f
            if (settledOverview && exitFade >= 1f - EPSILON && dismissing == null && !state.multiDismissActive) {
                pages.forEach { it.view.getGlobalVisibleRect(state.taskStates.getValue(it.view).bounds) }
                val frontOrdinalDelta = if (axis.invertStackDepth) -1 else 1
                pages.forEachIndexed { ordinal, page ->
                    val taskState = state.taskStates.getValue(page.view)
                    val frontBounds = pages.getOrNull(ordinal + frontOrdinalDelta)
                        ?.let { state.taskStates.getValue(it.view).bounds }
                    applyTitleOcclusion(page.view, taskState, frontBounds, axis)
                }
                // 头部模糊：可见堆叠中后方 peek（stackRel 最小且仍可见）。
                var blurIndex = -1
                var minStackRel = Float.MAX_VALUE
                pages.forEachIndexed { index, page ->
                    val ts = state.taskStates.getValue(page.view)
                    if (ts.frameVisibleAlpha > 0.05f && ts.frameRelativePosition < minStackRel) {
                        minStackRel = ts.frameRelativePosition
                        blurIndex = index
                    }
                }
                // 列表视觉最后方那张无更后遮挡源，不加模糊。
                val rearTerminalIndex = if (axis.invertStackDepth) pages.lastIndex else 0
                if (blurIndex == rearTerminalIndex) blurIndex = -1
                pages.forEachIndexed { index, page ->
                    val ts = state.taskStates.getValue(page.view)
                    applyHeaderBlur(page.view, ts, index == blurIndex)
                }
            } else {
                // 入场/退桌中跳过模糊时，清掉可能残留的 overlay，避免额外合成开销。
                state.taskStates.forEach { (task, ts) ->
                    if (ts.headerBlurred) {
                        ts.iconView?.let { clearHeaderBlur(it, ts) }
                    }
                    if (!ts.titleLastApplied.isNaN()) {
                        ts.titleView?.let { title ->
                            title.alpha = ts.titleNativeAlpha
                            if (title.visibility == View.INVISIBLE) title.visibility = View.VISIBLE
                        }
                        ts.titleLastApplied = Float.NaN
                    }
                }
            }
        } catch (throwable: Throwable) {
            Logger.once(TAG, "runtime_degrade", "运行时降级: ${throwable.javaClass.simpleName}: ${throwable.message}")
        } finally {
            state.applying = false
        }
    }

    // === 闭式几何（照 4933a65）===

    /** 单帧内顺序消费的 scratch：stackVisual 填充后立即使用，避免每帧每卡分配。 */
    private val scratchVisual = StackVisual()

    private fun stackVisual(
        viewportCenter: Float,
        cardPrimarySize: Float,
        relativePosition: Float,
        overscroll: Float,
    ): StackVisual {
        scratchVisual.primaryCenter = stackPrimaryCenter(viewportCenter, cardPrimarySize, relativePosition, overscroll)
        scratchVisual.scale = depthScale(relativePosition) * overscrollSinkScale(relativePosition, overscroll)
        scratchVisual.alpha = when {
            relativePosition >= -2f -> 1f
            relativePosition <= -3f -> 0f
            else -> 3f + relativePosition
        }
        return scratchVisual
    }

    /** 主轴视口坐标下的卡片目标中心（横竖屏共用闭式曲线）。 */
    private fun stackPrimaryCenter(
        viewportCenter: Float,
        cardPrimarySize: Float,
        relativePosition: Float,
        overscroll: Float,
    ): Float {
        val leftPeek = cardPrimarySize * LEFT_PEEK_FACTOR
        val rightSpacing = cardPrimarySize * RIGHT_SPACING_FACTOR
        val base = if (relativePosition <= 0f) {
            val distance = -relativePosition
            viewportCenter - leftPeek * (1f - LEFT_DECAY.pow(distance)) / (1f - LEFT_DECAY)
        } else {
            viewportCenter + relativePosition.pow(RIGHT_PARALLAX_EXPONENT) * rightSpacing
        }
        if (abs(overscroll) <= EPSILON) return base
        val weight = if (overscroll < 0f) {
            val distance = relativePosition.coerceAtLeast(0f)
            0.72f + 0.55f * (distance / (distance + 0.6f))
        } else {
            val distance = (-relativePosition).coerceAtLeast(0f)
            0.65f / (distance + 1f)
        }
        val absOverscroll = abs(overscroll)
        val damped = absOverscroll / (1f + absOverscroll * 0.8f)
        return base - overscroll.sign * damped * rightSpacing * weight
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

    /** 仅在 running task 接近当前页时用其原生位置锚定堆叠，避免横滑到邻页时整组卡片被远端 running task 拉飞。 */
    private fun runningAnchorWeight(relativePosition: Float): Float {
        val distance = abs(relativePosition)
        if (distance <= 0.92f) return 1f
        if (distance >= 1.28f) return 0f
        return smoothStep((1.28f - distance) / 0.36f)
    }

    /** running task 锚点只影响它自身与左侧堆叠卡，右侧待切换卡保持原始滑动曲线，避免被锚点拉出弹簧感。 */
    private fun runningAnchorInfluence(relativePosition: Float, anchorRelative: Float): Float {
        if (anchorRelative.isNaN()) return 0f
        if (relativePosition > anchorRelative + EPSILON) return 0f
        val distance = anchorRelative - relativePosition
        if (distance <= 1f) return 1f
        if (distance >= 2.2f) return 0f
        return smoothStep((2.2f - distance) / 1.2f)
    }

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
    private fun applyTitleOcclusion(task: View, state: TaskVisualState, frontBounds: Rect?, axis: RecentsAxis) {
        val title = resolveTitle(task, state) ?: return
        val visibleFraction = if (frontBounds == null) 1f else {
            title.getGlobalVisibleRect(scratchRect)
            val childStart = axis.boundsPrimaryStart(scratchRect)
            val childEnd = axis.boundsPrimaryEnd(scratchRect)
            val frontStart = axis.boundsPrimaryStart(frontBounds)
            val frontEnd = axis.boundsPrimaryEnd(frontBounds)
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
    private fun removeCustomPrimaryOffset(task: View, state: TaskVisualState, hooks: ResolvedHooks, axis: RecentsAxis): Float {
        val property = resolveOffsetProperty(task, state, hooks, axis)
        if (property != null) {
            val expected = state.nativeStackOffset + state.customPrimaryOffset
            val current = property.get(task)
            if (abs(current - expected) > EPSILON) {
                state.nativeStackOffset = current
                state.customPrimaryOffset = 0f
            }
            return state.nativeStackOffset
        }
        val current = axis.primaryTranslation(task)
        if (state.lastAppliedPrimaryTranslation.isNaN() || abs(current - state.lastAppliedPrimaryTranslation) > EPSILON) {
            state.nativePrimaryTranslation = current
        }
        return state.nativePrimaryTranslation
    }

    private fun applyCustomPrimaryOffset(task: View, state: TaskVisualState, hooks: ResolvedHooks, axis: RecentsAxis, offset: Float) {
        val property = resolveOffsetProperty(task, state, hooks, axis)
        if (property != null) {
            if (abs(offset - state.customPrimaryOffset) > EPSILON) {
                property.set(task, state.nativeStackOffset + offset)
                state.customPrimaryOffset = offset
            }
            return
        }
        val translation = state.nativePrimaryTranslation + offset
        axis.setPrimaryTranslation(task, translation)
        state.lastAppliedPrimaryTranslation = translation
    }

    private fun clearCustomPrimaryOffset(task: View, state: TaskVisualState, hooks: ResolvedHooks, axis: RecentsAxis) {
        val property = resolveOffsetProperty(task, state, hooks, axis)
        if (property != null) {
            if (abs(state.customPrimaryOffset) > EPSILON) {
                try { property.set(task, state.nativeStackOffset) } catch (_: Throwable) {}
                state.customPrimaryOffset = 0f
            }
            return
        }
        if (!state.lastAppliedPrimaryTranslation.isNaN()) {
            axis.setPrimaryTranslation(task, state.nativePrimaryTranslation)
            state.lastAppliedPrimaryTranslation = Float.NaN
        }
    }

    /**
     * 同时清掉竖屏 horizontalOffset(X) 与横屏 primaryTaskOffset(Y) 两条堆叠通道。
     * 切轴/退出 overview 后 state.rotation 可能已是 -1 或新方向，只清“当前轴”会让另一轴残留，
     * 表现为横屏卡片既有 X 又有 Y → 斜对角飞出视口。
     */
    private fun clearAllStackOffsetChannels(task: View, state: TaskVisualState, hooks: ResolvedHooks) {
        zeroOffsetProperty(task, hooks.horizontalOffsetProperty)
        zeroOffsetProperty(task, hooks.primaryTaskOffsetProperty)
        state.customPrimaryOffset = 0f
        state.nativeStackOffset = 0f
        state.lastAppliedPrimaryTranslation = Float.NaN
        state.offsetResolved = false
        state.offsetRotation = -1
        state.offsetProperty = null
    }

    private fun zeroOffsetProperty(task: View, getter: Method?) {
        if (getter == null) return
        try {
            @Suppress("UNCHECKED_CAST")
            val property = getter.invoke(task) as? FloatProperty<Any> ?: return
            property.set(task, 0f)
        } catch (_: Throwable) {
            return
        }
    }

    /**
     * 竖屏：horizontalOffset（只进 applyTranslationX，与原生 page offset 通道分离）。
     * 横屏：primaryTaskOffset（Landscape/Seascape 主轴 = Y，对应 TASK_OFFSET_TRANSLATION_Y）。
     * 绑定时先把“非当前轴”的堆叠通道置 0，避免竖屏→横屏残留 X 造成斜排。
     */
    private fun resolveOffsetProperty(task: View, state: TaskVisualState, hooks: ResolvedHooks, axis: RecentsAxis): FloatProperty<Any>? {
        if (state.offsetResolved && state.offsetRotation == axis.rotation) return state.offsetProperty
        // 轴切换：清掉另一条通道上的自定义位移（property 值留在 View 上，不只是我们的 state）。
        if (axis.landscape) {
            zeroOffsetProperty(task, hooks.horizontalOffsetProperty)
        } else {
            zeroOffsetProperty(task, hooks.primaryTaskOffsetProperty)
        }
        state.offsetResolved = true
        state.offsetRotation = axis.rotation
        val method = if (axis.landscape) hooks.primaryTaskOffsetProperty else hooks.horizontalOffsetProperty
        @Suppress("UNCHECKED_CAST")
        state.offsetProperty = try { method?.invoke(task) as? FloatProperty<Any> } catch (_: Throwable) { null }
        // 只认原生基线；我们的 custom 从 0 重新累加，避免把残留 custom 读成 native。
        state.nativeStackOffset = 0f
        try {
            state.offsetProperty?.set(task, 0f)
        } catch (_: Throwable) {
        }
        state.customPrimaryOffset = 0f
        return state.offsetProperty
    }

    private fun applyScale(task: View, state: TaskVisualState, hooks: ResolvedHooks, factor: Float) {
        if (state.lastAppliedScale.isNaN() || abs(task.scaleX - state.lastAppliedScale) > EPSILON) {
            normalizeNativeScale(task, state, hooks)
        }
        if (state.scaleInitialized &&
            factor < 1f - EPSILON && state.nativeScale > state.lastStableNativeScale + 0.02f
        ) {
            state.nativeScale = state.lastStableNativeScale
        }
        val scale = state.nativeScale * factor
        if (abs(task.scaleX - scale) > EPSILON || abs(task.scaleY - scale) > EPSILON) {
            task.scaleX = scale
            task.scaleY = scale
        }
        state.lastAppliedScale = scale
        if (abs(factor - 1f) <= 0.01f && state.nativeScale <= state.lastStableNativeScale + 0.02f) {
            state.lastStableNativeScale = state.nativeScale
            state.scaleInitialized = true
        }
    }

    /**
     * Flyme 到达“停顿将应用悬浮”阈值时会通过 RecentsView.updatePageScales() 直接放大非运行卡。
     * 这里先让 TaskView.applyScale() 按自身持久状态重算，避免把那次临时放大误记为堆叠基线。
     */
    private fun normalizeNativeScale(task: View, state: TaskVisualState, hooks: ResolvedHooks) {
        val before = task.scaleX
        val expected = state.lastAppliedScale
        if (hooks.applyScale != null && !expected.isNaN() && abs(before - expected) > EPSILON) {
            try {
                hooks.applyScale.invoke(task)
            } catch (_: Throwable) {
                task.scaleX = state.nativeScale
                task.scaleY = state.nativeScale
            }
        }
        state.nativeScale = if (!state.scaleInitialized && task.scaleX > 1.05f) 1f else task.scaleX
        state.lastAppliedScale = Float.NaN
    }

    private fun applyTaskAlpha(task: View, state: TaskVisualState, factor: Float) {
        if (state.lastAppliedAlpha.isNaN() || abs(task.alpha - state.lastAppliedAlpha) > EPSILON) {
            state.nativeAlpha = task.alpha
        }
        val alpha = state.nativeAlpha * factor
        if (abs(task.alpha - alpha) > EPSILON) task.alpha = alpha
        state.lastAppliedAlpha = alpha
    }

    private fun applyDepthOrder(task: View, state: TaskVisualState, physicalOrder: Float, layoutAmount: Float, depthStepPx: Float) {
        val z = state.initialTranslationZ + (physicalOrder + 1) * depthStepPx * layoutAmount
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

    /** 仅清堆叠位移通道与深度/缩放脏标记，不动 alpha（退桌淡出末帧专用）。 */
    private fun clearStackOffsetsOnly(recents: ViewGroup, state: RecentsState, hooks: ResolvedHooks) {
        val it = state.taskStates.entries.iterator()
        while (it.hasNext()) {
            val (task, ts) = it.next()
            clearAllStackOffsetChannels(task, ts, hooks)
            if (task.parent !== recents) {
                it.remove()
                continue
            }
            if (!ts.lastAppliedScale.isNaN()) {
                task.scaleX = ts.nativeScale
                task.scaleY = ts.nativeScale
                ts.lastAppliedScale = Float.NaN
            }
            if (ts.centerPivotApplied) {
                task.pivotX = ts.nativePivotX
                task.pivotY = ts.nativePivotY
                ts.centerPivotApplied = false
            }
            task.translationZ = ts.initialTranslationZ
            if (ts.headerBlurred) {
                ts.iconView?.let { clearHeaderBlur(it, ts) }
            }
            // alpha：保持当前淡出值，只丢掉我们的脏标记，避免被读回 native=1。
            ts.lastAppliedAlpha = Float.NaN
            ts.nativeAlpha = 1f
            setPlaceholderSuppressed(task, false)
        }
        for (i in 0 until recents.childCount) {
            val child = recents.getChildAt(i) ?: continue
            if (!hooks.taskClass.isInstance(child)) continue
            if (state.taskStates.containsKey(child)) continue
            zeroOffsetProperty(child, hooks.horizontalOffsetProperty)
            zeroOffsetProperty(child, hooks.primaryTaskOffsetProperty)
        }
    }

    private fun resetAllTransforms(recents: ViewGroup, state: RecentsState, hooks: ResolvedHooks) {
        // 清空模式随 reset 一并复位，避免下次进 overview 误屏蔽单卡删除。
        state.multiDismissActive = false
        state.lastDismissMoveTime = 0L
        state.dismissingTask = null
        state.dismissProgress = 0f
        val it = state.taskStates.entries.iterator()
        while (it.hasNext()) {
            val (task, ts) = it.next()
            // 即便已从 parent 摘掉也要清 View 上的 offset 通道：TaskView 会被复用，
            // 竖屏 horizontalOffset(X) 残留会在横屏再叠 Y → 斜对角。
            clearAllStackOffsetChannels(task, ts, hooks)
            if (task.parent !== recents) { it.remove(); continue }
            if (!ts.lastAppliedScale.isNaN()) { task.scaleX = ts.nativeScale; task.scaleY = ts.nativeScale; ts.lastAppliedScale = Float.NaN }
            if (!ts.lastAppliedAlpha.isNaN()) { task.alpha = ts.nativeAlpha; ts.lastAppliedAlpha = Float.NaN }
            if (ts.centerPivotApplied) { task.pivotX = ts.nativePivotX; task.pivotY = ts.nativePivotY; ts.centerPivotApplied = false }
            task.translationZ = ts.initialTranslationZ
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
            setPlaceholderSuppressed(task, false)
        }
        // 仍挂在 recents 下、但尚未进入 taskStates 的 TaskView 也清一遍（旋转后新建 state 前的残留）。
        for (i in 0 until recents.childCount) {
            val child = recents.getChildAt(i) ?: continue
            if (!hooks.taskClass.isInstance(child)) continue
            if (state.taskStates.containsKey(child)) continue
            zeroOffsetProperty(child, hooks.horizontalOffsetProperty)
            zeroOffsetProperty(child, hooks.primaryTaskOffsetProperty)
        }
    }

    private fun setPlaceholderSuppressed(task: View, suppress: Boolean) {
        try {
            val container = XposedHelpers.callMethod(task, "getFirstTaskContainer") ?: return
            val thumbnail = XposedHelpers.callMethod(container, "getThumbnailViewDeprecated") as? View ?: return
            thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, suppress)
        } catch (_: Throwable) {
            return
        }
    }

    /**
     * 缓存 TaskView 的占位底板视图：热路径（applyStack 每帧每卡）只首次走反射，
     * 之后用直读字段校验复用性（TaskView 回收/容器重建会使旧 thumbnail 脱离窗口）。
     */
    private fun resolvePlaceholderThumbnail(task: View, state: TaskVisualState): View? {
        if (state.placeholderThumbnailResolved) {
            val cached = state.placeholderThumbnail
            if (cached != null && cached.isAttachedToWindow) return cached
            if (cached == null && !task.isAttachedToWindow) return null
            state.placeholderThumbnailResolved = false
        }
        state.placeholderThumbnailResolved = true
        state.placeholderThumbnail = thumbnailViewForTask(task)
        return state.placeholderThumbnail
    }

    /** 热路径版：tag 无变化时跳过 setTag（getTag 为直读，开销可忽略）。 */
    private fun setPlaceholderSuppressedCached(task: View, state: TaskVisualState, suppress: Boolean) {
        val thumbnail = resolvePlaceholderThumbnail(task, state) ?: return
        if ((thumbnail.getTag(TAG_PLACEHOLDER_SUPPRESSED) == true) == suppress) return
        thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, suppress)
    }

    private fun markPlaceholderReleasePending(recents: ViewGroup, hooks: ResolvedHooks) {
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (!hooks.taskClass.isInstance(task)) continue
            if (!invokeBoolean(hooks.isRunningTask, task)) continue
            val thumbnail = thumbnailViewForTask(task) ?: continue
            task.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, true)
            thumbnail.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, true)
            setPlaceholderSuppressed(task, true)
            if (hasThumbnailView(thumbnail)) {
                releasePlaceholderTask(task)
            }
        }
    }

    private fun releasePlaceholderTask(task: View) {
        task.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, false)
        thumbnailViewForTask(task)?.let { releasePlaceholderThumbnail(it) }
    }

    private fun releasePlaceholderThumbnail(thumbnail: View) {
        thumbnail.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, false)
        thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, false)
    }

    private fun releasePlaceholderFromThumbnail(thumbnail: View) {
        releasePlaceholderThumbnail(thumbnail)
        (thumbnail.parent as? View)?.let { task ->
            task.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, false)
        }
    }

    private fun thumbnailViewForTask(task: View): View? = try {
        val container = XposedHelpers.callMethod(task, "getFirstTaskContainer") ?: return null
        XposedHelpers.callMethod(container, "getThumbnailViewDeprecated") as? View
    } catch (_: Throwable) {
        null
    }

    private fun hasThumbnailView(thumbnail: View): Boolean = try {
        XposedHelpers.callMethod(thumbnail, "getThumbnail") != null
    } catch (_: Throwable) {
        false
    }

    private fun clearPlaceholderSuppression(recents: ViewGroup, hooks: ResolvedHooks) {
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (hooks.taskClass.isInstance(task)) {
                task.setTag(TAG_PLACEHOLDER_RELEASE_PENDING, false)
                setPlaceholderSuppressed(task, false)
            }
        }
    }

    private fun trace(view: View, message: String) {
        if (!TRACE_PLACEHOLDER) return
        Log.i(LOGCAT_TAG, "t=${SystemClock.uptimeMillis()} ${viewIdentity(view)} $message")
    }

    private fun viewIdentity(view: View): String = try {
        val taskId = if (view.javaClass.name.contains("TaskThumbnail")) {
            val task = XposedHelpers.getObjectField(view, "mTask")
            val key = task?.let { XposedHelpers.getObjectField(it, "key") }
            key?.let { XposedHelpers.getIntField(it, "id") }
        } else {
            val component = runCatching { XposedHelpers.callMethod(view, "getTaskFlowComponent") as? ComponentName }.getOrNull()
            component?.flattenToShortString() ?: "taskView"
        }
        "view=${view.javaClass.simpleName}@${System.identityHashCode(view).toString(16)} task=$taskId"
    } catch (_: Throwable) {
        "view=${view.javaClass.simpleName}@${System.identityHashCode(view).toString(16)}"
    }

    // === 页面收集 ===

    private fun collectTaskPages(recents: ViewGroup, hooks: ResolvedHooks, state: RecentsState, axis: RecentsAxis): List<TaskPage> {
        val pages = ArrayList<TaskPage>()
        val homeTask = try { hooks.getHomeTaskView?.invoke(recents) as? View } catch (_: Throwable) { null }
        for (index in 0 until recents.childCount) {
            val child = recents.getChildAt(index)
            if (!hooks.taskClass.isInstance(child)) continue
            state.taskStates.getOrPut(child) { TaskVisualState(child.translationZ) }
            if (child === homeTask || isLauncherTask(child, hooks)) continue
            // 与 axis.primaryScroll 同空间：不二次符号翻转；Landscape 的 RTL 已写入 mPageScrolls。
            val pageScroll = (hooks.getScrollForPage.invoke(recents, index) as? Number)?.toFloat() ?: continue
            pages += TaskPage(child, index, pageScroll)
        }
        if (pages.size <= 1) return pages
        val sorted = pages.sortedBy { it.pageScroll }
        val usable = sorted.zipWithNext().any { (a, b) -> abs(b.pageScroll - a.pageScroll) > 1f }
        if (usable) return sorted
        // pageScroll 不可用时按主轴视口相对位置回退。
        val viewportHalf = axis.primaryViewportSize(recents) / 2f
        return pages.map { page ->
            val fallback = axis.childPrimaryStart(page.view) + axis.childPrimarySize(page.view) / 2f - viewportHalf
            page.copy(pageScroll = fallback)
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

    private fun axisFor(recents: ViewGroup, hooks: ResolvedHooks): RecentsAxis {
        val handler = try { hooks.pagedOrientationHandler?.invoke(recents) } catch (_: Throwable) { null }
        val rotation = rotationFromHandler(handler, hooks) ?: (recents.display?.rotation ?: 0)
        return RecentsAxis(rotation = rotation, landscape = RecentsAxis.isLandscape(rotation))
    }

    private fun rotationFromHandler(handler: Any?, hooks: ResolvedHooks): Int? {
        if (handler == null) return null
        return try {
            val m = hooks.orientationRotation ?: synchronized(runtimeRotationMethods) {
                runtimeRotationMethods.getOrPut(handler.javaClass) {
                    findMethodsInHierarchy(handler.javaClass).firstOrNull {
                        it.name == "getRotation" && it.parameterTypes.isEmpty() &&
                            it.returnType == Int::class.javaPrimitiveType
                    }?.apply { isAccessible = true }
                }
            }
            (m?.invoke(handler) as? Number)?.toInt()?.let { return it }
            val name = handler.javaClass.name
            when {
                name.contains("Seascape", true) -> 3
                name.contains("Landscape", true) -> 1
                name.contains("Portrait", true) -> 0
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeBoolean(method: Method?, target: Any): Boolean =
        try { method?.invoke(target) as? Boolean ?: false } catch (_: Throwable) { false }

    /** 每帧解析一次运行卡；getRunningTaskView 缺失时返回 null 由调用方退回 isRunningTask。 */
    private fun resolveRunningTaskView(recents: ViewGroup, hooks: ResolvedHooks): View? {
        val method = hooks.getRunningTaskView ?: return null
        return try { method.invoke(recents) as? View } catch (_: Throwable) { null }
    }

    private fun syncRunningLiveTile(recents: ViewGroup, hooks: ResolvedHooks, primary: Float, secondary: Float) {
        try {
            if (!readLiveTileEnabled(recents, hooks)) return
            val handles = readRemoteTargetHandles(recents, hooks) ?: return
            for (handle in handles) {
                val simulator = hooks.getTaskViewSimulator?.invoke(handle ?: continue) ?: continue
                val primaryFloat = hooks.taskPrimaryTranslationField?.get(simulator) ?: continue
                hooks.animatedFloatValueField?.setFloat(primaryFloat, primary)
                val secondaryFloat = hooks.taskSecondaryTranslationField?.get(simulator)
                hooks.animatedFloatValueField?.setFloat(secondaryFloat, secondary)
            }
        } catch (_: Throwable) {
            return
        }
    }

    /** getEnableDrawingLiveTile() 反编译为纯字段透传，字段优先免每帧 invoke；异常回退方法。 */
    private fun readLiveTileEnabled(recents: ViewGroup, hooks: ResolvedHooks): Boolean {
        val field = hooks.enableDrawingLiveTileField
        if (field != null) {
            try { return field.getBoolean(recents) } catch (_: Throwable) {}
        }
        return invokeBoolean(hooks.getEnableDrawingLiveTile, recents)
    }

    /** getRemoteTargetHandles() 反编译为纯字段透传，字段优先免每帧 invoke；异常回退方法。 */
    private fun readRemoteTargetHandles(recents: ViewGroup, hooks: ResolvedHooks): Array<*>? {
        val field = hooks.remoteTargetHandlesField
        if (field != null) {
            try { return field.get(recents) as? Array<*> } catch (_: Throwable) {}
        }
        return try { hooks.getRemoteTargetHandles?.invoke(recents) as? Array<*> } catch (_: Throwable) { null }
    }

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

    private fun findField(clazz: Class<*>?, name: String): Field? {
        var current = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: Throwable) {
                current = current.superclass
            }
        }
        return null
    }

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

    private data class TaskPage(val view: View, val childIndex: Int, val pageScroll: Float)

    /** 帧内 scratch：由 stackVisual 填充，单帧顺序消费，避免每帧每卡分配。 */
    private class StackVisual {
        var primaryCenter = 0f
        var scale = 1f
        var alpha = 1f
    }

    private class RecentsState {
        var applying = false
        var overviewEnabled = false
        var contentAlpha = 0f
        var fullscreenProgress = 0f
        var fullscreenProgressKnown = false
        // Flyme ADJACENT_PAGE_SCALE：0=贴合 overview，1=退桌 detached。
        var adjacentPageScale = 0f
        var lastAdjacentPageScale = 0f
        // true=退桌淡出中（scale 正在抬升）；入场 scale 下落时保持 false。
        var detachFadeActive = false
        var rotation = -1
        var pageRebuildPending = false
        var cachedPages: List<TaskPage>? = null
        var cachedPagesChildCount = -1
        var cachedPagesRotation = -1
        var cachedAxis: RecentsAxis? = null
        val taskStates = IdentityHashMap<View, TaskVisualState>()
        // dismiss 接管：正在删除的卡与删除进度(0→1，按被删卡次轴位移/卡高)。
        var dismissingTask: View? = null
        var dismissProgress = 0f
        // 清空模式：多卡并发 dismiss（createAllTasksDismissAnimationMz 错峰飞出）时置位，
        // 屏蔽单卡接管（顶 z/补位/渐淡），堆叠几何保持；超时或 reset 后复位。
        var multiDismissActive = false
        var lastDismissMoveTime = 0L
        var traceFrame = 0
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
        var lastStableNativeScale = 1f
        var scaleInitialized = false
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
        // 占位底板视图缓存：TaskView 回收/容器重建后经 isAttachedToWindow 校验失效重解析。
        var placeholderThumbnailResolved = false
        var placeholderThumbnail: View? = null
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
        val applyScale: Method?,
        val horizontalOffsetProperty: Method?,
        val primaryTaskOffsetProperty: Method?,
        val taskComponent: Method?,
        val pagedOrientationHandler: Method?,
        val orientationRotation: Method?,
        val getRunningTaskView: Method?,
        val getEnableDrawingLiveTile: Method?,
        val getRemoteTargetHandles: Method?,
        val enableDrawingLiveTileField: Field?,
        val remoteTargetHandlesField: Field?,
        val getTaskViewSimulator: Method?,
        val taskPrimaryTranslationField: Field?,
        val taskSecondaryTranslationField: Field?,
        val animatedFloatValueField: Field?,
        val adjacentPageScaleField: Field?,
    )
}

package com.karen.flymetool.hook.feature.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.FloatProperty
import android.view.View
import android.view.ViewGroup
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.lang.reflect.Method
import kotlin.math.roundToInt


object IosStackedRecentsHook : FeatureHook {

    private const val TAG = "IosStackedRecents"
    private const val FEATURE_KEY = "ios_stacked_recents"
    private const val RECENTS_VIEW = "com.android.quickstep.views.RecentsView"
    private const val TASK_VIEW = "com.android.quickstep.views.TaskView"
    private const val TASK_THUMBNAIL_VIEW = "com.android.quickstep.views.TaskThumbnailViewDeprecated"
    private const val SWIPE_UP_HANDLER = "com.android.quickstep.AbsSwipeUpHandler"
    private const val PAGE_OFFSET_EPSILON = 0.001f

    private val TAG_ACTIVE = Int.MAX_VALUE - 701
    private val TAG_SCALE = Int.MAX_VALUE - 702
    private val TAG_DISMISS_X = Int.MAX_VALUE - 703
    private val TAG_DISMISS_Y = Int.MAX_VALUE - 704
    private val TAG_NATIVE_OFFSET_X = Int.MAX_VALUE - 705
    private val TAG_NATIVE_OFFSET_Y = Int.MAX_VALUE - 706
    private val TAG_FULLSCREEN_PROGRESS = Int.MAX_VALUE - 707
    private val TAG_REMOTE_TARGETS = Int.MAX_VALUE - 708
    private val TAG_OFFSET_HANDOFF = Int.MAX_VALUE - 709
    private val TAG_REAPPLYING_OFFSET = Int.MAX_VALUE - 711
    private val TAG_PLACEHOLDER_SUPPRESSED = Int.MAX_VALUE - 714
    private val TAG_PLACEHOLDER_INSPECTED = Int.MAX_VALUE - 715
    private val TAG_REFRESH_POSTED = Int.MAX_VALUE - 716
    private val TAG_HEADER_STATE = Int.MAX_VALUE - 717

    private const val HEADER_HIDE_ALPHA_THRESHOLD = 0.02f
    private const val HEADER_ALPHA_EPSILON = 0.001f
    private const val ICON_MAX_BLUR_DP = 6f
    private const val ICON_BLUR_STEPS = 16
    private const val ICON_BLUR_PADDING_MULTIPLIER = 2f

    private val HEADER_NOT_FOUND = Any()
    private val iconBlurEffectCache = mutableMapOf<Int, RenderEffect>()

    private val math = IosRecentsMath()
    private var headerDebugCount = 0
    private var offsetXMethod: Method? = null
    private var offsetYMethod: Method? = null
    private var applyScaleMethod: Method? = null
    private var adjacentPageOffsetProperty: FloatProperty<Any>? = null
    private var adjacentPageScaleProperty: FloatProperty<Any>? = null

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.meizu.flyme.launcher") return
        if (!ctx.featureEnabled(FEATURE_KEY)) return
        try {
            mount(ctx)
            Logger.i(TAG, "堆叠后台 Hook 完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "堆叠后台 Hook 挂载失败", e)
        }
    }

    private fun mount(ctx: HookContext) {
        val recentsCl = Reflect.findClass(RECENTS_VIEW, ctx.classLoader)
        val taskCl = Reflect.findClass(TASK_VIEW, ctx.classLoader)
        offsetXMethod = findFloatMethod(taskCl, "setTaskOffsetTranslationX")
        offsetYMethod = findFloatMethod(taskCl, "setTaskOffsetTranslationY")
        applyScaleMethod = taskCl.getDeclaredMethod("applyScale").also { it.isAccessible = true }
        adjacentPageOffsetProperty =
            findRecentsFloatProperty(recentsCl, "ADJACENT_PAGE_HORIZONTAL_OFFSET")
        adjacentPageScaleProperty =
            findRecentsFloatProperty(recentsCl, "ADJACENT_PAGE_SCALE")

        hookOffsetChannel(ctx, taskCl)
        hookScaleChannel(ctx)
        hookDismissChannel(ctx, taskCl)
        hookPlaceholderDrawing(ctx)
        hookFullscreenProgress(ctx, recentsCl, taskCl)
        hookRemoteTargets(ctx, recentsCl, taskCl)
        hookGestureLifecycle(ctx, recentsCl, taskCl)
        hookContinuousGestureProgress(ctx, taskCl)
        hookPageOffsetUpdates(ctx, recentsCl, taskCl)
        hookPageScaleUpdates(ctx, recentsCl, taskCl)
        hookRefreshSources(ctx, recentsCl, taskCl)
        hookLiveTileEnable(ctx, recentsCl, taskCl)
    }

    private fun findFloatMethod(type: Class<*>, name: String): Method? {
        val floatType = Float::class.javaPrimitiveType
        return type.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(floatType))
        }?.also { it.isAccessible = true }
    }

    /** 把样条曲线写进 Flyme 原生 taskOffsetTranslation，而不是追加 View.translation。 */
    private fun hookOffsetChannel(ctx: HookContext, taskCl: Class<*>) {
        val floatType = Float::class.javaPrimitiveType
        for ((method, nativeTag, isX) in listOf(
            Triple(offsetXMethod, TAG_NATIVE_OFFSET_X, true),
            Triple(offsetYMethod, TAG_NATIVE_OFFSET_Y, false),
        )) {
            val target = method ?: continue
            if (target.parameterTypes.singleOrNull() != floatType) continue
            Reflect.hookMethod(ctx.api, target) { chain ->
                val task = chain.getThisObject() as View
                val nativeValue = chain.getArg(0) as Float
                if (task.getTag(TAG_REAPPLYING_OFFSET) != true) {
                    task.setTag(nativeTag, nativeValue)
                }
                val recents = task.parent as? ViewGroup ?: return@hookMethod chain.proceed()
                val transform = calculateTransform(ctx, recents, task) ?: return@hookMethod chain.proceed()
                val isLandscape = isLandscape(ctx, recents)
                val replacement = when {
                    isX && !isLandscape -> transform.primary
                    !isX && isLandscape -> transform.primary
                    isX -> transform.secondary
                    else -> transform.secondary
                }
                chain.proceed(arrayOf<Any>(replacement))
            }
        }
    }

    private fun hookScaleChannel(ctx: HookContext) {
        val method = applyScaleMethod ?: return
        Reflect.hookMethod(ctx.api, method) { chain ->
            val result = chain.proceed()
            val task = chain.getThisObject() as View
            if (task.getTag(TAG_ACTIVE) != true) return@hookMethod result
            val scale = task.getTag(TAG_SCALE) as? Float ?: return@hookMethod result
            task.scaleX *= scale
            task.scaleY *= scale
            result
        }
    }

    private fun hookDismissChannel(ctx: HookContext, taskCl: Class<*>) {
        for ((name, tag) in listOf(
            "setDismissTranslationX" to TAG_DISMISS_X,
            "setDismissTranslationY" to TAG_DISMISS_Y,
        )) {
            val method = findFloatMethod(taskCl, name) ?: continue
            Reflect.hookMethod(ctx.api, method) { chain ->
                (chain.getThisObject() as View).setTag(tag, chain.getArg(0) as Float)
                val result = chain.proceed()
                val task = chain.getThisObject() as View
                (task.parent as? ViewGroup)?.let { requestStackRefresh(ctx, it, taskCl) }
                result
            }
        }
    }

    private fun hookFullscreenProgress(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        val floatType = Float::class.javaPrimitiveType
        val method = recentsCl.declaredMethods.firstOrNull {
            it.name == "setFullscreenProgress" &&
                it.parameterTypes.contentEquals(arrayOf(floatType)) &&
                it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "setFullscreenProgress(float) 未找到")
            return
        }
        Reflect.hookMethod(ctx.api, method) { chain ->
            (chain.getThisObject() as ViewGroup).setTag(
                TAG_FULLSCREEN_PROGRESS,
                (1f - (chain.getArg(0) as Float)).coerceIn(0f, 1f),
            )
            val result = chain.proceed()
            requestStackRefresh(ctx, chain.getThisObject() as ViewGroup, taskCl)
            result
        }
    }

    private fun hookRemoteTargets(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        val setTargets = recentsCl.declaredMethods.firstOrNull {
            it.name == "setRecentsAnimationTargets" && it.parameterTypes.size == 2 && it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "setRecentsAnimationTargets 未找到")
            return
        }
        Reflect.hookMethod(ctx.api, setTargets) { chain ->
            if (chain.getArg(1) == null) return@hookMethod chain.proceed()
            val recents = chain.getThisObject() as ViewGroup
            recents.setTag(TAG_REMOTE_TARGETS, true)
            recents.setTag(TAG_OFFSET_HANDOFF, false)
            val result = chain.proceed()
            requestStackRefresh(ctx, chain.getThisObject() as ViewGroup, taskCl)
            result
        }

        val cleanup = recentsCl.declaredMethods.firstOrNull {
            it.name == "cleanupRemoteTargets" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: return
        Reflect.hookMethod(ctx.api, cleanup) { chain ->
            val recents = chain.getThisObject() as ViewGroup
            if (recents.getTag(TAG_REMOTE_TARGETS) == true) {
                val progress = readStackProgress(recents)
                val adjacentOffset = kotlin.math.abs(readAdjacentPageOffset(recents))
                val adjacentScale = kotlin.math.abs(readAdjacentPageScale(recents))
                recents.setTag(
                    TAG_OFFSET_HANDOFF,
                    progress >= 1f - PAGE_OFFSET_EPSILON &&
                        (adjacentOffset > PAGE_OFFSET_EPSILON ||
                            adjacentScale > PAGE_OFFSET_EPSILON),
                )
            }
            val result = chain.proceed()
            val recentsAfter = chain.getThisObject() as ViewGroup
            recentsAfter.setTag(TAG_REMOTE_TARGETS, false)
            clearPlaceholderSuppression(ctx, recentsAfter, taskCl)
            requestStackRefresh(ctx, recentsAfter, taskCl)
            result
        }
    }

    /** 仅在远端交接期间抑制无缩略图占位底板。 */
    private fun hookPlaceholderDrawing(ctx: HookContext) {
        try {
            val thumbnailCl = Reflect.findClass(TASK_THUMBNAIL_VIEW, ctx.classLoader)
            val onDraw = thumbnailCl.declaredMethods.firstOrNull {
                it.name == "onDraw" &&
                    it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) &&
                    it.returnType == Void.TYPE
            } ?: run {
                Logger.w(TAG, "TaskThumbnailViewDeprecated.onDraw(Canvas) 未找到")
                return
            }
            Reflect.hookMethod(ctx.api, onDraw) { chain ->
                if ((chain.getThisObject() as View).getTag(TAG_PLACEHOLDER_SUPPRESSED) == true) {
                    null
                } else {
                    chain.proceed()
                }
            }

            thumbnailCl.declaredMethods.filter {
                it.name == "setThumbnail" && it.parameterTypes.size in 2..3 && it.returnType == Void.TYPE
            }.forEach { method ->
                Reflect.hookMethod(ctx.api, method) { chain ->
                    val result = chain.proceed()
                    val thumbnail = chain.getThisObject() as View
                    if (Reflect.callMethod(ctx.api, thumbnail, "getThumbnail") != null) {
                        thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, false)
                    }
                    result
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "占位缩略图绘制 Hook 挂载失败", e)
        }
    }

    private fun hookGestureLifecycle(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        val gestureStart = recentsCl.declaredMethods.firstOrNull {
            it.name == "onGestureAnimationStart" && it.parameterTypes.size == 1 && it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "onGestureAnimationStart(...) 未找到")
            return
        }
        Reflect.hookMethod(ctx.api, gestureStart) { chain ->
            val recents = chain.getThisObject() as ViewGroup
            recents.setTag(TAG_OFFSET_HANDOFF, false)
            clearPlaceholderSuppression(ctx, recents, taskCl)
            chain.proceed()
        }
    }

    /** 远端手势期间优先跟随 mCurrentShift 的逐帧进度。 */
    private fun hookContinuousGestureProgress(
        ctx: HookContext,
        taskCl: Class<*>,
    ) {
        try {
            val handlerCl = Reflect.findClass(SWIPE_UP_HANDLER, ctx.classLoader)
            val method = handlerCl.declaredMethods.firstOrNull {
                it.name == "updateLauncherTransitionProgressForFlyme" &&
                    it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            } ?: handlerCl.declaredMethods.firstOrNull {
                it.name == "onCurrentShiftUpdated" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            } ?: run {
                Logger.w(TAG, "Flyme 手势进度入口未找到")
                return
            }
            Reflect.hookMethod(ctx.api, method) { chain ->
                val result = chain.proceed()
                try {
                    val handler = chain.getThisObject()
                    val recents = Reflect.getObjectField(handler, "mRecentsView") as? ViewGroup ?: return@hookMethod result
                    if (recents.getTag(TAG_REMOTE_TARGETS) != true) return@hookMethod result
                    val currentShift = Reflect.getObjectField(handler, "mCurrentShift") ?: return@hookMethod result
                    val progress = Reflect.getFloatField(currentShift, "value").coerceIn(0f, 1f)
                    recents.setTag(TAG_FULLSCREEN_PROGRESS, progress)
                    requestStackRefresh(ctx, recents, taskCl)
                } catch (e: Throwable) {
                    Logger.once(TAG, "gesture_progress", "读取连续手势进度失败")
                }
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "AbsSwipeUpHandler 连续进度 Hook 挂载失败", e)
        }
    }

    private fun hookPageOffsetUpdates(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        for (name in listOf("updatePageOffsets", "updatePageOffsetsForFlyme")) {
            val method = recentsCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            } ?: continue
            Reflect.hookMethod(ctx.api, method) { chain ->
                val result = chain.proceed()
                val recents = chain.getThisObject() as ViewGroup
                finishOffsetHandoffIfSettled(recents)
                requestStackRefresh(ctx, recents, taskCl)
                result
            }
        }
    }

    private fun hookPageScaleUpdates(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        val method = recentsCl.declaredMethods.firstOrNull {
            it.name == "updatePageScales" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "updatePageScales() 未找到")
            return
        }
        Reflect.hookMethod(ctx.api, method) { chain ->
            val result = chain.proceed()
            stabilizeStackScale(ctx, chain.getThisObject() as ViewGroup, taskCl)
            result
        }
    }

    private fun stabilizeStackScale(ctx: HookContext, recents: ViewGroup, taskCl: Class<*>) {
        if (recents.getTag(TAG_REMOTE_TARGETS) != true &&
            recents.getTag(TAG_OFFSET_HANDOFF) != true
        ) return

        val runningTask = getRunningTaskView(ctx, recents)
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (!taskCl.isInstance(task) || task === runningTask) continue
            task.pivotX = task.width / 2f
            task.pivotY = task.height / 2f
            if (task.getTag(TAG_ACTIVE) == true) {
                applyScaleMethod?.invoke(task)
            }
        }
    }

    private fun hookRefreshSources(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        for (name in listOf("updateCurveProperties", "dispatchScrollChanged")) {
            val method = recentsCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: continue
            Reflect.hookMethod(ctx.api, method) { chain ->
                val result = chain.proceed()
                requestStackRefresh(ctx, chain.getThisObject() as ViewGroup, taskCl)
                result
            }
        }
    }

    private fun hookLiveTileEnable(ctx: HookContext, recentsCl: Class<*>, taskCl: Class<*>) {
        val booleanType = Boolean::class.javaPrimitiveType
        val method = recentsCl.declaredMethods.firstOrNull {
            it.name == "setEnableDrawingLiveTile" &&
                it.parameterTypes.contentEquals(arrayOf(booleanType)) &&
                it.returnType == Void.TYPE
        } ?: return
        Reflect.hookMethod(ctx.api, method) { chain ->
            val result = chain.proceed()
            if (chain.getArg(0) as Boolean) requestStackRefresh(ctx, chain.getThisObject() as ViewGroup, taskCl)
            result
        }
    }

    /** 远端动画期合并刷新，避免同帧重复重算。 */
    private fun requestStackRefresh(ctx: HookContext, recents: ViewGroup, taskCl: Class<*>) {
        if (recents.getTag(TAG_REMOTE_TARGETS) != true &&
            recents.getTag(TAG_OFFSET_HANDOFF) != true
        ) {
            refreshStack(ctx, recents, taskCl)
            return
        }
        if (recents.getTag(TAG_REFRESH_POSTED) == true) return
        recents.setTag(TAG_REFRESH_POSTED, true)
        recents.postOnAnimation {
            recents.setTag(TAG_REFRESH_POSTED, false)
            refreshStack(ctx, recents, taskCl)
        }
    }

    private fun refreshStack(ctx: HookContext, recents: ViewGroup, taskCl: Class<*>) {
        try {
            if (!shouldStack(ctx, recents)) {
                clearStack(recents, taskCl)
                return
            }
            if (recents.childCount == 0) return
            val first = Reflect.callMethod(ctx.api, recents, "getPageAt", 0) as? View ?: return
            if (first.measuredWidth == 0) return

            var index = 0
            var remoteDepth = 0
            var runningPrimary: Float? = null
            var runningSecondary: Float? = null
            val stackedTasks = ArrayList<StackedTaskState>()
            val runningTask = if (recents.getTag(TAG_REMOTE_TARGETS) == true) getRunningTaskView(ctx, recents) else null
            for (i in 0 until recents.childCount) {
                val task = recents.getChildAt(i) ?: continue
                if (!taskCl.isInstance(task)) continue
                inspectPlaceholderOnce(ctx, recents, task)
                val isRunning = task === runningTask
                val depth = if (recents.getTag(TAG_REMOTE_TARGETS) == true && !isRunning) {
                    ++remoteDepth
                } else {
                    index
                }
                val z = if (recents.getTag(TAG_REMOTE_TARGETS) == true) {
                    if (isRunning) 0f else -depth.toFloat()
                } else {
                    -depth.toFloat()
                }
                val transform = calculateTransform(ctx, recents, task) ?: continue
                val visualDepth = if (isRunning) 0 else depth
                task.setTag(TAG_ACTIVE, true)
                task.setTag(TAG_SCALE, transform.scale)
                task.rotationY = transform.rotationY
                task.translationZ = if (recents.getTag(TAG_REMOTE_TARGETS) == true) {
                    z * transform.progress
                } else {
                    -index.toFloat() * transform.progress
                }
                reapplyNativeOffsets(task)
                applyScaleMethod?.invoke(task)
                stackedTasks += StackedTaskState(task, visualDepth)
                if (isRunning) {
                    val landscape = isLandscape(ctx, recents)
                    runningPrimary = if (landscape) task.translationY else task.translationX
                    runningSecondary = if (landscape) task.translationX else task.translationY
                }
                index++
            }
            applyHeaderOcclusion(ctx, stackedTasks, isLandscape(ctx, recents))
            if (runningPrimary != null && runningSecondary != null) {
                syncLiveTile(ctx, recents, runningPrimary, runningSecondary)
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "堆叠刷新失败", e)
        }
    }

    private fun reapplyNativeOffsets(task: View) {
        task.setTag(TAG_REAPPLYING_OFFSET, true)
        try {
            offsetXMethod?.invoke(task, task.getTag(TAG_NATIVE_OFFSET_X) as? Float ?: 0f)
            offsetYMethod?.invoke(task, task.getTag(TAG_NATIVE_OFFSET_Y) as? Float ?: 0f)
        } finally {
            task.setTag(TAG_REAPPLYING_OFFSET, false)
        }
    }

    private fun calculateTransform(ctx: HookContext, recents: ViewGroup, task: View): Transform? {
        if (!shouldStack(ctx, recents)) return null
        val landscape = isLandscape(ctx, recents)
        val primarySize = taskSizeInScrollDirection(ctx, recents, landscape).coerceAtLeast(1)
        val screenPrimary = if (landscape) recents.measuredHeight else recents.measuredWidth
        val screenSecondary = if (landscape) recents.measuredWidth else recents.measuredHeight
        if (screenPrimary == 0 || screenSecondary == 0) return null

        val scroll = if (landscape) recents.scrollY else recents.scrollX
        val isRemote = recents.getTag(TAG_REMOTE_TARGETS) == true
        val dismissPrimary = if (landscape) {
            task.getTag(TAG_DISMISS_Y) as? Float ?: 0f
        } else {
            task.getTag(TAG_DISMISS_X) as? Float ?: 0f
        }
        val center = if (landscape) {
            task.top + task.measuredHeight / 2 + dismissPrimary
        } else {
            task.left + task.measuredWidth / 2 + dismissPrimary
        }
        val distance = (center - (scroll + screenPrimary / 2f))
        // Seascape 需要以镜像距离采样样条。
        val direction = if (isSeascape(ctx, recents)) -1f else 1f
        val orientedDistance = distance * direction
        val splinePosition = 3.0 + orientedDistance / primarySize
        val centerScale = math.getValue(IosRecentsMath.SPLINE_SCALE, 3.0).toFloat()
        val centerX = math.getValue(IosRecentsMath.SPLINE_X_COORD, 3.0).toFloat()
        val centerY = math.getValue(IosRecentsMath.SPLINE_Y_COORD, 3.0).toFloat()
        val targetPrimary = ((math.getValue(IosRecentsMath.SPLINE_X_COORD, splinePosition).toFloat() - centerX) *
            screenPrimary - orientedDistance) * direction
        val targetSecondary = (math.getValue(IosRecentsMath.SPLINE_Y_COORD, splinePosition).toFloat() - centerY) *
            screenSecondary * direction
        val targetScale = math.getValue(IosRecentsMath.SPLINE_SCALE, splinePosition).toFloat() /
            centerScale.coerceAtLeast(0.0001f)

        val isOffsetHandoff = recents.getTag(TAG_OFFSET_HANDOFF) == true
        val progress = resolveProgress(recents, isRemote)
        val nativeX = task.getTag(TAG_NATIVE_OFFSET_X) as? Float ?: 0f
        val nativeY = task.getTag(TAG_NATIVE_OFFSET_Y) as? Float ?: 0f
        val nativePrimary = if (landscape) nativeY else nativeX
        val nativeSecondary = if (landscape) nativeX else nativeY
        // 远端阶段从原生相邻页偏移平滑过渡到堆叠目标。
        val primary = if (isRemote || isOffsetHandoff) {
            nativePrimary + (targetPrimary - nativePrimary) * progress
        } else {
            nativePrimary + targetPrimary * progress
        }
        val secondary = nativeSecondary + targetSecondary * progress
        return Transform(
            primary = primary,
            secondary = secondary,
            scale = 1f + (targetScale - 1f) * progress,
            rotationY = math.getValue(IosRecentsMath.SPLINE_ROTATION_Y, splinePosition).toFloat() * progress,
            progress = progress,
        )
    }

    private fun resolveProgress(recents: ViewGroup, isRemote: Boolean): Float {
        val fullscreen = readStackProgress(recents)
        if (!isRemote) return fullscreen
        return fullscreen
    }

    private fun readStackProgress(recents: ViewGroup): Float =
        (recents.getTag(TAG_FULLSCREEN_PROGRESS) as? Float ?: 1f).coerceIn(0f, 1f)

    private fun finishOffsetHandoffIfSettled(recents: ViewGroup) {
        if (recents.getTag(TAG_OFFSET_HANDOFF) != true) return
        if (kotlin.math.abs(readAdjacentPageOffset(recents)) <= PAGE_OFFSET_EPSILON &&
            kotlin.math.abs(readAdjacentPageScale(recents)) <= PAGE_OFFSET_EPSILON
        ) {
            recents.setTag(TAG_OFFSET_HANDOFF, false)
        }
    }

    private fun shouldStack(ctx: HookContext, recents: ViewGroup): Boolean = try {
        (Reflect.callMethod(ctx.api, recents, "showAsGrid") as? Boolean != true) &&
            (Reflect.callMethod(ctx.api, recents, "getTaskViewCount") as? Int ?: 0) > 0
    } catch (_: Throwable) {
        false
    }

    private fun clearStack(recents: ViewGroup, taskCl: Class<*>) {
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (!taskCl.isInstance(task) || task.getTag(TAG_ACTIVE) != true) continue
            task.setTag(TAG_ACTIVE, false)
            task.setTag(TAG_SCALE, null)
            task.rotationY = 0f
            task.translationZ = 0f
            reapplyNativeOffsets(task)
            applyScaleMethod?.invoke(task)
            resetHeaderOcclusion(task)
        }
    }

    /** 标题文字随遮挡淡出，图标保持原 alpha 并增加模糊。 */
    private fun applyHeaderOcclusion(ctx: HookContext, tasks: List<StackedTaskState>, landscape: Boolean) {
        val taskBoundsByDepth = tasks.associate { stackedTask ->
            stackedTask.depth to Rect().also { stackedTask.task.getGlobalVisibleRect(it) }
        }
        val headers = tasks.mapNotNull { stackedTask ->
            resolveHeaderState(stackedTask.task)?.let { state ->
                if (state.lockChild?.visibility == View.VISIBLE) {
                    refreshNativeLockState(ctx, stackedTask.task)
                }
                prepareHeaderState(state)
                StackedHeader(
                    stackedTask = stackedTask,
                    state = state,
                )
            }
        }
        if (headers.isEmpty()) return

        headers.forEach { current ->
            val frontBounds = taskBoundsByDepth[current.stackedTask.depth - 1]
            var titleOcclusionAlpha: Float? = null
            current.state.textChildren.forEach { child ->
                val visibleFraction = if (frontBounds == null) {
                    1f
                } else {
                    calculateHeaderVisibleFraction(child, frontBounds, landscape)
                }
                val occlusionAlpha = smoothStep(visibleFraction)
                titleOcclusionAlpha = occlusionAlpha
                applyHeaderTextAlpha(current.state, child, occlusionAlpha)
            }
            titleOcclusionAlpha?.let { alpha ->
                current.state.lockChild?.let { lock ->
                    applyHeaderFollowerAlpha(current.state, lock, alpha)
                }
            }
            current.state.iconChildren.forEach { child ->
                val visibleFraction = if (frontBounds == null) {
                    1f
                } else {
                    calculateHeaderVisibleFraction(child, frontBounds, landscape)
                }
                applyHeaderIconBlur(ctx, current.state, child, smoothStep(visibleFraction))
            }
        }
    }

    /** 通过原生入口回填 activity_lock 的真实显隐状态。 */
    private fun refreshNativeLockState(ctx: HookContext, task: View) {
        try {
            Reflect.callMethod(ctx.api, task, "updateAppLockStatus")
        } catch (e: Throwable) {
            Logger.once(TAG, "sync_task_lock", "同步原生任务锁状态失败")
        }
    }

    private fun prepareHeaderState(state: HeaderState) {
        restoreHeaderVisibility(state)
        val alphaChildren = state.textChildren + listOfNotNull(state.lockChild)
        if (!state.active) {
            state.baseAlphas.clear()
            state.lastAppliedAlphas.clear()
            alphaChildren.forEach { child -> state.baseAlphas[child] = child.alpha }
            state.active = true
            return
        }
        alphaChildren.forEach { child ->
            val lastApplied = state.lastAppliedAlphas[child]
            if (lastApplied != null &&
                kotlin.math.abs(child.alpha - lastApplied) > HEADER_ALPHA_EPSILON
            ) {
                state.baseAlphas[child] = child.alpha
            }
        }
    }

    private fun calculateHeaderVisibleFraction(
        child: View,
        frontTaskBounds: Rect,
        landscape: Boolean,
    ): Float {
        val childBounds = Rect().also { child.getGlobalVisibleRect(it) }
        val secondaryOverlaps = if (landscape) {
            childBounds.right > frontTaskBounds.left && childBounds.left < frontTaskBounds.right
        } else {
            childBounds.bottom > frontTaskBounds.top && childBounds.top < frontTaskBounds.bottom
        }
        if (!secondaryOverlaps) return 1f

        val childStart = if (landscape) childBounds.top else childBounds.left
        val childEnd = if (landscape) childBounds.bottom else childBounds.right
        val frontStart = if (landscape) frontTaskBounds.top else frontTaskBounds.left
        val frontEnd = if (landscape) frontTaskBounds.bottom else frontTaskBounds.right
        val childSize = (childEnd - childStart).coerceAtLeast(1)
        val coveredSize = (minOf(childEnd, frontEnd) - maxOf(childStart, frontStart)).coerceAtLeast(0)
        return (1f - coveredSize.toFloat() / childSize).coerceIn(0f, 1f)
    }

    private fun smoothStep(value: Float): Float {
        val bounded = value.coerceIn(0f, 1f)
        return bounded * bounded * (3f - 2f * bounded)
    }

    private fun applyHeaderTextAlpha(state: HeaderState, child: View, occlusionAlpha: Float) {
        applyHeaderFollowerAlpha(state, child, occlusionAlpha)

        if (occlusionAlpha <= HEADER_HIDE_ALPHA_THRESHOLD) {
            if (child.visibility == View.VISIBLE) {
                child.visibility = View.INVISIBLE
                state.hiddenByOcclusion += child
            }
        }
    }

    private fun applyHeaderFollowerAlpha(state: HeaderState, child: View, occlusionAlpha: Float) {
        val applied = (state.baseAlphas[child] ?: child.alpha) * occlusionAlpha
        if (kotlin.math.abs(child.alpha - applied) > HEADER_ALPHA_EPSILON) {
            child.alpha = applied
        }
        state.lastAppliedAlphas[child] = applied
    }

    private fun applyHeaderIconBlur(ctx: HookContext, state: HeaderState, child: View, visibleFraction: Float) {
        val blurStep = ((1f - visibleFraction.coerceIn(0f, 1f)) * ICON_BLUR_STEPS)
            .roundToInt()
            .coerceIn(0, ICON_BLUR_STEPS)
        val iconState = state.iconBlurStates.getOrPut(child) {
            IconBlurState(BlurredIconOverlayView(child.context))
        }

        child.setRenderEffect(null)
        updateNativeIconAlpha(iconState, child)
        if (blurStep == 0) {
            clearHeaderIconBlur(state, child, iconState)
            return
        }

        val source = readIconDrawable(ctx, child)
        if (source == null || !iconState.overlay.updateSource(source)) {
            clearHeaderIconBlur(state, child, iconState)
            return
        }

        val density = child.resources.displayMetrics.density
        val maxPaddingPx = (ICON_MAX_BLUR_DP * density * ICON_BLUR_PADDING_MULTIPLIER)
            .roundToInt()
            .coerceAtLeast(1)
        layoutIconOverlay(state.overlayHost, child, iconState.overlay, maxPaddingPx)
        iconState.overlay.updateDrawableBounds(source.bounds, maxPaddingPx)
        iconState.overlay.alpha = iconState.nativeAlpha
        if (!iconState.addedToOverlay) {
            state.overlayHost.overlay.add(iconState.overlay)
            iconState.addedToOverlay = true
        }

        if (iconState.blurStep != blurStep) {
            val radiusPx = ICON_MAX_BLUR_DP * density * blurStep / ICON_BLUR_STEPS
            val radiusKey = (radiusPx * 10f).roundToInt().coerceAtLeast(1)
            val effect = iconBlurEffectCache.getOrPut(radiusKey) {
                val cachedRadius = radiusKey / 10f
                RenderEffect.createBlurEffect(
                    cachedRadius,
                    cachedRadius,
                    Shader.TileMode.DECAL,
                )
            }
            iconState.overlay.setRenderEffect(effect)
            iconState.blurStep = blurStep
        }

        child.alpha = 0f
        iconState.lastAppliedAlpha = 0f
        iconState.usingOverlay = true
    }

    private fun readIconDrawable(ctx: HookContext, child: View): Drawable? = try {
        Reflect.callMethod(ctx.api, child, "getDrawable") as? Drawable
    } catch (_: Throwable) {
        null
    }

    private fun updateNativeIconAlpha(state: IconBlurState, child: View) {
        if (!state.usingOverlay) {
            state.nativeAlpha = child.alpha
            return
        }
        val lastApplied = state.lastAppliedAlpha
        if (lastApplied != null &&
            kotlin.math.abs(child.alpha - lastApplied) > HEADER_ALPHA_EPSILON
        ) {
            state.nativeAlpha = child.alpha
        }
    }

    private fun layoutIconOverlay(
        host: ViewGroup,
        child: View,
        overlay: View,
        paddingPx: Int,
    ) {
        val childBounds = Rect(0, 0, child.width, child.height)
        host.offsetDescendantRectToMyCoords(child, childBounds)
        overlay.layout(
            childBounds.left - paddingPx,
            childBounds.top - paddingPx,
            childBounds.right + paddingPx,
            childBounds.bottom + paddingPx,
        )
        overlay.rotation = child.rotation
        overlay.scaleX = child.scaleX
        overlay.scaleY = child.scaleY
        overlay.pivotX = overlay.width / 2f
        overlay.pivotY = overlay.height / 2f
    }

    private fun clearHeaderIconBlur(
        headerState: HeaderState,
        child: View,
        iconState: IconBlurState,
    ) {
        if (iconState.usingOverlay) {
            val lastApplied = iconState.lastAppliedAlpha
            if (lastApplied != null &&
                kotlin.math.abs(child.alpha - lastApplied) > HEADER_ALPHA_EPSILON
            ) {
                iconState.nativeAlpha = child.alpha
            }
            child.alpha = iconState.nativeAlpha
        }
        if (iconState.addedToOverlay) {
            headerState.overlayHost.overlay.remove(iconState.overlay)
            iconState.addedToOverlay = false
        }
        iconState.overlay.setRenderEffect(null)
        iconState.blurStep = 0
        iconState.lastAppliedAlpha = null
        iconState.usingOverlay = false
    }

    private fun resetHeaderOcclusion(task: View) {
        val state = task.getTag(TAG_HEADER_STATE) as? HeaderState ?: return
        if (!state.active && state.hiddenByOcclusion.isEmpty() && state.iconBlurStates.isEmpty()) return

        val alphaChildren = state.textChildren + listOfNotNull(state.lockChild)
        alphaChildren.forEach { child ->
            val lastApplied = state.lastAppliedAlphas[child]
            if (lastApplied != null &&
                kotlin.math.abs(child.alpha - lastApplied) > HEADER_ALPHA_EPSILON
            ) {
                state.baseAlphas[child] = child.alpha
            }
            val baseAlpha = state.baseAlphas[child] ?: 1f
            if (kotlin.math.abs(child.alpha - baseAlpha) > HEADER_ALPHA_EPSILON) {
                child.alpha = baseAlpha
            }
        }
        restoreHeaderVisibility(state)
        state.iconChildren.forEach { child ->
            child.setRenderEffect(null)
            state.iconBlurStates[child]?.let { iconState ->
                clearHeaderIconBlur(state, child, iconState)
            }
        }
        state.baseAlphas.clear()
        state.lastAppliedAlphas.clear()
        state.iconBlurStates.clear()
        state.active = false
    }

    /** 只依赖稳定资源 ID 与视图结构定位普通/分组任务标题栏。 */
    private fun resolveHeaderState(task: View): HeaderState? {
        when (val cached = task.getTag(TAG_HEADER_STATE)) {
            is HeaderState -> return cached
            HEADER_NOT_FOUND -> return null
        }

        val packageName = task.context.packageName
        val resources = task.resources
        val taskHeadId = resources.getIdentifier("task_head", "id", packageName)
        val iconId = resources.getIdentifier("icon", "id", packageName)
        val bottomRightIconId = resources.getIdentifier("bottomRight_icon", "id", packageName)
        val appNameId = resources.getIdentifier("app_name", "id", packageName)
        val activityLockId = resources.getIdentifier("activity_lock", "id", packageName)
        if (headerDebugCount < 5) {
            headerDebugCount++
            Logger.d(TAG) {
                "pkg=$packageName taskHead=$taskHeadId icon=$iconId appName=$appNameId taskClass=${task.javaClass.name} " +
                    "headerFound=${task.findViewById<View>(taskHeadId) != null}"
            }
        }
        if (taskHeadId == 0 || iconId == 0) {
            task.setTag(TAG_HEADER_STATE, HEADER_NOT_FOUND)
            return null
        }

        val header = task.findViewById<View>(taskHeadId) as? ViewGroup
        if (header == null) {
            task.setTag(TAG_HEADER_STATE, HEADER_NOT_FOUND)
            return null
        }
        val children = List(header.childCount) { header.getChildAt(it) }
        if (children.none { it.id == iconId }) {
            task.setTag(TAG_HEADER_STATE, HEADER_NOT_FOUND)
            return null
        }
        val iconIds = setOf(iconId, bottomRightIconId).filter { it != 0 }.toSet()
        return HeaderState(
            overlayHost = task as? ViewGroup ?: header,
            textChildren = children.filter { it.id == appNameId },
            iconChildren = children.filter { it.id in iconIds },
            lockChild = children.firstOrNull { it.id == activityLockId },
        ).also { task.setTag(TAG_HEADER_STATE, it) }
    }

    private fun restoreHeaderVisibility(state: HeaderState) {
        state.hiddenByOcclusion.forEach { child ->
            if (child.visibility == View.INVISIBLE) {
                child.visibility = View.VISIBLE
            }
        }
        state.hiddenByOcclusion.clear()
    }

    /** Seascape 也按横屏逻辑轴处理。 */
    private fun isLandscape(ctx: HookContext, recents: ViewGroup): Boolean = try {
        val handler = Reflect.callMethod(ctx.api, recents, "getPagedOrientationHandler")
        val name = handler?.javaClass?.name ?: ""
        name.contains("Landscape") || name.contains("Seascape")
    } catch (_: Throwable) {
        false
    }

    private fun isSeascape(ctx: HookContext, recents: ViewGroup): Boolean = try {
        val handler = Reflect.callMethod(ctx.api, recents, "getPagedOrientationHandler")
        handler?.javaClass?.name?.contains("Seascape") == true
    } catch (_: Throwable) {
        false
    }

    private fun taskSizeInScrollDirection(ctx: HookContext, recents: ViewGroup, landscape: Boolean): Int = try {
        val size = Reflect.callMethod(ctx.api, recents, "getLastComputedTaskSize") as? Rect
        (if (landscape) size?.height() else size?.width())?.takeIf { it > 0 }
            ?: (if (landscape) recents.measuredHeight else recents.measuredWidth) / 2
    } catch (_: Throwable) {
        recents.measuredWidth / 2
    }

    private fun getRunningTaskView(ctx: HookContext, recents: ViewGroup): View? = try {
        Reflect.callMethod(ctx.api, recents, "getRunningTaskView") as? View
    } catch (_: Throwable) {
        null
    }

    /** TaskThumbnailViewDeprecated 没有 Bitmap 时会显示随深色模式变化的纯色底板。 */
    private fun hasNoThumbnail(ctx: HookContext, task: View): Boolean = try {
        val container = Reflect.callMethod(ctx.api, task, "getFirstTaskContainer") ?: return false
        val thumbnailView = Reflect.callMethod(ctx.api, container, "getThumbnailViewDeprecated") ?: return false
        Reflect.callMethod(ctx.api, thumbnailView, "getThumbnail") == null
    } catch (_: Throwable) {
        false
    }

    /** 远端交接期只在首次进入时检查一次缩略图状态，避免逐帧反射。 */
    private fun inspectPlaceholderOnce(ctx: HookContext, recents: ViewGroup, task: View) {
        if (recents.getTag(TAG_REMOTE_TARGETS) != true || task.getTag(TAG_PLACEHOLDER_INSPECTED) == true) return
        task.setTag(TAG_PLACEHOLDER_INSPECTED, true)
        val noThumbnail = hasNoThumbnail(ctx, task)
        setPlaceholderSuppressed(ctx, task, suppress = noThumbnail)
    }

    private fun setPlaceholderSuppressed(ctx: HookContext, task: View, suppress: Boolean) {
        try {
            val container = Reflect.callMethod(ctx.api, task, "getFirstTaskContainer") ?: return
            val thumbnail = Reflect.callMethod(ctx.api, container, "getThumbnailViewDeprecated") as? View ?: return
            thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, suppress)
        } catch (_: Throwable) {
            return
        }
    }

    private fun clearPlaceholderSuppression(ctx: HookContext, recents: ViewGroup, taskCl: Class<*>) {
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (taskCl.isInstance(task)) {
                task.setTag(TAG_PLACEHOLDER_INSPECTED, false)
                setPlaceholderSuppressed(ctx, task, suppress = false)
            }
        }
    }

    private fun syncLiveTile(ctx: HookContext, recents: ViewGroup, primary: Float, secondary: Float) {
        try {
            if (Reflect.callMethod(ctx.api, recents, "getEnableDrawingLiveTile") as? Boolean != true) return
            val handles = Reflect.callMethod(ctx.api, recents, "getRemoteTargetHandles") as? Array<*> ?: return
            for (handle in handles) {
                val simulator = handle?.let { Reflect.callMethod(ctx.api, it, "getTaskViewSimulator") } ?: continue
                val primaryFloat = Reflect.getObjectField(simulator, "taskPrimaryTranslation") ?: continue
                val secondaryFloat = Reflect.getObjectField(simulator, "taskSecondaryTranslation") ?: continue
                Reflect.setFloatField(primaryFloat, "value", primary)
                Reflect.setFloatField(secondaryFloat, "value", secondary)
            }
            Reflect.callMethod(ctx.api, recents, "redrawLiveTile")
        } catch (e: Throwable) {
            Logger.once(TAG, "live_tile_sync", "Live Tile 坐标同步失败")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findRecentsFloatProperty(
        recentsCl: Class<*>,
        fieldName: String,
    ): FloatProperty<Any>? = try {
        recentsCl.fields.firstOrNull {
            it.name == fieldName &&
                FloatProperty::class.java.isAssignableFrom(it.type)
        }?.get(null) as? FloatProperty<Any>
    } catch (e: Throwable) {
        Logger.w(TAG, "未找到 $fieldName")
        null
    }

    private fun readAdjacentPageOffset(recents: ViewGroup): Float = try {
        adjacentPageOffsetProperty?.get(recents) ?: 0f
    } catch (_: Throwable) {
        0f
    }

    private fun readAdjacentPageScale(recents: ViewGroup): Float = try {
        adjacentPageScaleProperty?.get(recents) ?: 0f
    } catch (_: Throwable) {
        0f
    }

    private data class Transform(
        val primary: Float,
        val secondary: Float,
        val scale: Float,
        val rotationY: Float,
        val progress: Float,
    )

    private data class HeaderState(
        val overlayHost: ViewGroup,
        val textChildren: List<View>,
        val iconChildren: List<View>,
        val lockChild: View?,
        val baseAlphas: MutableMap<View, Float> = mutableMapOf(),
        val lastAppliedAlphas: MutableMap<View, Float> = mutableMapOf(),
        val iconBlurStates: MutableMap<View, IconBlurState> = mutableMapOf(),
        val hiddenByOcclusion: MutableSet<View> = mutableSetOf(),
        var active: Boolean = false,
    )

    private data class IconBlurState(
        val overlay: BlurredIconOverlayView,
        var nativeAlpha: Float = 1f,
        var lastAppliedAlpha: Float? = null,
        var blurStep: Int = -1,
        var addedToOverlay: Boolean = false,
        var usingOverlay: Boolean = false,
    )

    /** 给图标提供额外的模糊扩散空间，避免被原始边界裁切。 */
    private class BlurredIconOverlayView(context: Context) : View(context) {
        private var source: Drawable? = null
        private var drawable: Drawable? = null

        fun updateSource(newSource: Drawable): Boolean {
            if (source !== newSource || drawable == null) {
                val copy = newSource.constantState
                    ?.newDrawable(resources)
                    ?.mutate()
                    ?: return false
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

    private data class StackedTaskState(
        val task: View,
        val depth: Int,
    )

    private data class StackedHeader(
        val stackedTask: StackedTaskState,
        val state: HeaderState,
    )

    // Cubic spline data mirrored from Reverse-FlymeLauncher IosRecentsMath.
    private class IosRecentsMath {
        companion object {
            const val SPLINE_X_COORD = 0
            const val SPLINE_Y_COORD = 1
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
                splines += SplineHelper().also { it.init(y) }
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
            var index = -1
            for (i in 0 until n) {
                if (xv in x[i]..x[i + 1]) {
                    index = i
                    break
                }
            }
            if (index == -1) {
                val i = if (xv < x[0]) 0 else 4
                val diff = xv - x[i]
                return a[i] + (b[i] + c[i] + d[i]) * diff
            }
            val diff = xv - x[index]
            return a[index] + b[index] * diff + c[index] * diff * diff + d[index] * diff * diff * diff
        }
    }
}
package com.karen.flymetool.hook.feature.launcher

import android.graphics.Rect
import android.graphics.Canvas
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
    private val TAG_PAGE_PROGRESS = Int.MAX_VALUE - 709
    private val TAG_PAGE_ANIMATION_SEEN = Int.MAX_VALUE - 710
    private val TAG_REAPPLYING_OFFSET = Int.MAX_VALUE - 711
    // 当前应用从远端 Surface 切到截图期间的 TaskView / 异步回填副本都携带同一组 taskId。
    private val TAG_TRANSITION_TASK_IDS = Int.MAX_VALUE - 712
    private val TAG_TRANSITION_STUB = Int.MAX_VALUE - 713
    // 无 ThumbnailData 时 TaskThumbnailViewDeprecated 会主动绘制主题色底板；仅在远端交接时抑制它。
    private val TAG_PLACEHOLDER_SUPPRESSED = Int.MAX_VALUE - 714

    private val math = IosRecentsMath()
    private var offsetXMethod: Method? = null
    private var offsetYMethod: Method? = null
    private var applyScaleMethod: Method? = null
    private var adjacentPageOffsetProperty: FloatProperty<Any>? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.meizu.flyme.launcher") return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        try {
            mount(lpparam)
            Logger.i(TAG, "iOS 堆叠后台 Hook 完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "iOS 堆叠后台 Hook 挂载失败", e)
        }
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam) {
        val recentsCl = XposedHelpers.findClass(RECENTS_VIEW, lpparam.classLoader)
        val taskCl = XposedHelpers.findClass(TASK_VIEW, lpparam.classLoader)
        offsetXMethod = findFloatMethod(taskCl, "setTaskOffsetTranslationX")
        offsetYMethod = findFloatMethod(taskCl, "setTaskOffsetTranslationY")
        applyScaleMethod = taskCl.getDeclaredMethod("applyScale").also { it.isAccessible = true }
        adjacentPageOffsetProperty = findAdjacentPageOffsetProperty(recentsCl)

        hookOffsetChannel(taskCl)
        hookScaleChannel()
        hookDismissChannel(taskCl)
        hookPlaceholderDrawing(lpparam.classLoader)
        hookFullscreenProgress(recentsCl, taskCl)
        hookRemoteTargets(recentsCl, taskCl)
        hookTransitionStubLifecycle(recentsCl, taskCl)
        hookContinuousGestureProgress(lpparam, taskCl)
        hookPageOffsetUpdates(recentsCl, taskCl)
        hookRefreshSources(recentsCl, taskCl)
        hookLiveTileEnable(recentsCl, taskCl)
    }

    private fun findFloatMethod(type: Class<*>, name: String): Method? {
        val floatType = Float::class.javaPrimitiveType
        return type.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(floatType))
        }?.also { it.isAccessible = true }
    }

    /** 把样条曲线写进 Flyme 原生 taskOffsetTranslation，而不是追加 View.translation。 */
    private fun hookOffsetChannel(taskCl: Class<*>) {
        val floatType = Float::class.javaPrimitiveType
        for ((method, nativeTag, isX) in listOf(
            Triple(offsetXMethod, TAG_NATIVE_OFFSET_X, true),
            Triple(offsetYMethod, TAG_NATIVE_OFFSET_Y, false),
        )) {
            val target = method ?: continue
            if (target.parameterTypes.singleOrNull() != floatType) continue
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as View
                    val nativeValue = param.args[0] as Float
                    if (task.getTag(TAG_REAPPLYING_OFFSET) != true) {
                        task.setTag(nativeTag, nativeValue)
                    }
                    val recents = task.parent as? ViewGroup ?: return
                    // 应用上滑的运行任务由远端 Surface 锚定在中央；不能再让模块改写它的
                    // taskOffset，否则 TaskView 与 Surface 会走出两条相交轨迹。
                    if (isRemoteRunningTask(recents, task) || isTransitionTask(recents, task)) return
                    val transform = calculateTransform(recents, task) ?: return
                    val isLandscape = isLandscape(recents)
                    val replacement = when {
                        isX && !isLandscape -> transform.primary
                        !isX && isLandscape -> transform.primary
                        isX -> transform.secondary
                        else -> transform.secondary
                    }
                    param.args[0] = replacement
                }
            })
        }
    }

    /** scale 沿用 TaskView 的原生复合缩放，再叠加样条目标。 */
    private fun hookScaleChannel() {
        val method = applyScaleMethod ?: return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val task = param.thisObject as View
                if (task.getTag(TAG_ACTIVE) != true) return
                val scale = task.getTag(TAG_SCALE) as? Float ?: return
                task.scaleX *= scale
                task.scaleY *= scale
            }
        })
    }

    private fun hookDismissChannel(taskCl: Class<*>) {
        for ((name, tag) in listOf(
            "setDismissTranslationX" to TAG_DISMISS_X,
            "setDismissTranslationY" to TAG_DISMISS_Y,
        )) {
            val method = findFloatMethod(taskCl, name) ?: continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.thisObject as View).setTag(tag, param.args[0] as Float)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject as View
                    (task.parent as? ViewGroup)?.let { refreshStack(it, taskCl) }
                }
            })
        }
    }

    private fun hookFullscreenProgress(recentsCl: Class<*>, taskCl: Class<*>) {
        val floatType = Float::class.javaPrimitiveType
        val method = recentsCl.declaredMethods.firstOrNull {
            it.name == "setFullscreenProgress" &&
                it.parameterTypes.contentEquals(arrayOf(floatType)) &&
                it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "setFullscreenProgress(float) 未找到")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as ViewGroup).setTag(
                    TAG_FULLSCREEN_PROGRESS,
                    (1f - (param.args[0] as Float)).coerceIn(0f, 1f),
                )
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                refreshStack(param.thisObject as ViewGroup, taskCl)
            }
        })
    }

    private fun hookRemoteTargets(recentsCl: Class<*>, taskCl: Class<*>) {
        val setTargets = recentsCl.declaredMethods.firstOrNull {
            it.name == "setRecentsAnimationTargets" && it.parameterTypes.size == 2 && it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "setRecentsAnimationTargets 未找到")
            return
        }
        XposedBridge.hookMethod(setTargets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[1] == null) return
                val recents = param.thisObject as ViewGroup
                recents.setTag(TAG_REMOTE_TARGETS, true)
                recents.setTag(TAG_PAGE_ANIMATION_SEEN, false)
                recents.setTag(TAG_PAGE_PROGRESS, 0f)
                mergeTransitionTargetIds(recents, param.args[1])
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                refreshStack(param.thisObject as ViewGroup, taskCl)
            }
        })

        val cleanup = recentsCl.declaredMethods.firstOrNull {
            it.name == "cleanupRemoteTargets" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: return
        XposedBridge.hookMethod(cleanup, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val recents = param.thisObject as ViewGroup
                recents.setTag(TAG_REMOTE_TARGETS, false)
                recents.setTag(TAG_PAGE_ANIMATION_SEEN, false)
                recents.setTag(TAG_PAGE_PROGRESS, null)
                clearPlaceholderSuppression(recents, taskCl)
                clearTransitionTask(recents, taskCl, refresh = false)
                refreshStack(recents, taskCl)
            }
        })
    }

    /**
     * 占位卡的可见白/深色块来自 TaskThumbnailViewDeprecated.onDraw，而不是 TaskView 本身。
     * 因此保持 TaskView 的连续几何变换，仅在远端交接期间跳过这一次无缩略图绘制；一旦
     * 缩略图回填，setThumbnail() 会立刻恢复绘制，不需要把整张普通卡排除在堆叠之外。
     */
    private fun hookPlaceholderDrawing(classLoader: ClassLoader) {
        try {
            val thumbnailCl = XposedHelpers.findClass(TASK_THUMBNAIL_VIEW, classLoader)
            val onDraw = thumbnailCl.declaredMethods.firstOrNull {
                it.name == "onDraw" &&
                    it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) &&
                    it.returnType == Void.TYPE
            } ?: run {
                Logger.w(TAG, "TaskThumbnailViewDeprecated.onDraw(Canvas) 未找到")
                return
            }
            XposedBridge.hookMethod(onDraw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((param.thisObject as View).getTag(TAG_PLACEHOLDER_SUPPRESSED) == true) {
                        param.result = null
                    }
                }
            })

            thumbnailCl.declaredMethods.filter {
                it.name == "setThumbnail" && it.parameterTypes.size in 2..3 && it.returnType == Void.TYPE
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thumbnail = param.thisObject as View
                        // 有真实缩略图的帧必须立即恢复，避免被动等待下一次 Recents 刷新。
                        if (XposedHelpers.callMethod(thumbnail, "getThumbnail") != null) {
                            thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, false)
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "占位缩略图绘制 Hook 挂载失败: ${e.message}")
        }
    }

    /**
     * Flyme 在 onGestureAnimationStart() 内用 showCurrentTask() 创建/复用运行任务占位卡，
     * 任务列表随后异步回填时可能出现同 taskId 的第二个 TaskView。按 taskId 而不是按
     * getRunningTaskView() 标记，才能覆盖慢速悬停和快速松手两个交接窗口。
     */
    private fun hookTransitionStubLifecycle(recentsCl: Class<*>, taskCl: Class<*>) {
        val gestureStart = recentsCl.declaredMethods.firstOrNull {
            it.name == "onGestureAnimationStart" && it.parameterTypes.size == 1 && it.returnType == Void.TYPE
        } ?: run {
            Logger.w(TAG, "onGestureAnimationStart(...) 未找到")
            return
        }
        XposedBridge.hookMethod(gestureStart, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val recents = param.thisObject as ViewGroup
                clearTransitionTask(recents = recents, taskCl = taskCl, refresh = false)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val recents = param.thisObject as ViewGroup
                val runningTask = getRunningTaskView(recents) ?: return
                val taskIds = taskIdsOf(runningTask)
                if (taskIds.isEmpty()) return
                runningTask.setTag(TAG_TRANSITION_STUB, true)
                recents.setTag(TAG_TRANSITION_TASK_IDS, taskIds)
            }
        })

        // 不在 setShouldShowScreenshot()/onRecentsAnimationComplete() 释放标记：这两个回调
        // 都发生在远端 Surface 与截图交接的同一帧，立刻重排会让下层卡闪到最顶层。
        // 远端 target 清理（或下一次手势开始）才是安全的释放边界。
    }

    /**
     * mCurrentShift 是 AbsSwipeUpHandler 唯一逐帧更新的手势值。FULLSCREEN_PROGRESS 在
     * Quick Switch 的侧边起手分支会滞后更新，直接以该值刷新可消除卡片先停住后靠拢。
     */
    private fun hookContinuousGestureProgress(
        lpparam: XC_LoadPackage.LoadPackageParam,
        taskCl: Class<*>,
    ) {
        try {
            val handlerCl = XposedHelpers.findClass(SWIPE_UP_HANDLER, lpparam.classLoader)
            val method = handlerCl.declaredMethods.firstOrNull {
                it.name == "onCurrentShiftUpdated" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            } ?: run {
                Logger.w(TAG, "onCurrentShiftUpdated() 未找到")
                return
            }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val handler = param.thisObject
                        val recents = XposedHelpers.getObjectField(handler, "mRecentsView") as? ViewGroup ?: return
                        if (recents.getTag(TAG_REMOTE_TARGETS) != true) return
                        val currentShift = XposedHelpers.getObjectField(handler, "mCurrentShift") ?: return
                        val progress = XposedHelpers.getFloatField(currentShift, "value").coerceIn(0f, 1f)
                        recents.setTag(TAG_FULLSCREEN_PROGRESS, progress)
                        refreshStack(recents, taskCl)
                    } catch (e: Throwable) {
                        Logger.w(TAG, "读取连续手势进度失败: ${e.message}")
                    }
                }
            })
        } catch (e: Throwable) {
            Logger.w(TAG, "AbsSwipeUpHandler 连续进度 Hook 挂载失败: ${e.message}")
        }
    }

    /** 原生 updatePageOffsets 结束后再同步 Live Tile，确保不会被 f5 原始偏移覆盖。 */
    private fun hookPageOffsetUpdates(recentsCl: Class<*>, taskCl: Class<*>) {
        for (name in listOf("updatePageOffsets", "updatePageOffsetsForFlyme")) {
            val method = recentsCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            } ?: continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    refreshStack(param.thisObject as ViewGroup, taskCl)
                }
            })
        }
    }

    /** 滚动、布局与手势结束后的兜底刷新；这些入口均是 RecentsView 的公开语义回调。 */
    private fun hookRefreshSources(recentsCl: Class<*>, taskCl: Class<*>) {
        for (name in listOf("updateCurveProperties", "dispatchScrollChanged")) {
            val method = recentsCl.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    refreshStack(param.thisObject as ViewGroup, taskCl)
                }
            })
        }
    }

    private fun hookLiveTileEnable(recentsCl: Class<*>, taskCl: Class<*>) {
        val booleanType = Boolean::class.javaPrimitiveType
        val method = recentsCl.declaredMethods.firstOrNull {
            it.name == "setEnableDrawingLiveTile" &&
                it.parameterTypes.contentEquals(arrayOf(booleanType)) &&
                it.returnType == Void.TYPE
        } ?: return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args[0] as Boolean) refreshStack(param.thisObject as ViewGroup, taskCl)
            }
        })
    }

    private fun refreshStack(recents: ViewGroup, taskCl: Class<*>) {
        try {
            if (!shouldStack(recents)) {
                clearStack(recents, taskCl)
                return
            }
            if (recents.childCount == 0) return
            val first = XposedHelpers.callMethod(recents, "getPageAt", 0) as? View ?: return
            if (first.measuredWidth == 0) return

            var index = 0
            var remoteDepth = 0
            var runningPrimary: Float? = null
            var runningSecondary: Float? = null
            val runningTask = if (recents.getTag(TAG_REMOTE_TARGETS) == true) getRunningTaskView(recents) else null
            for (i in 0 until recents.childCount) {
                val task = recents.getChildAt(i) ?: continue
                if (!taskCl.isInstance(task)) continue
                val hasNoThumbnail = hasNoThumbnail(task)
                setPlaceholderSuppressed(
                    task = task,
                    suppress = recents.getTag(TAG_REMOTE_TARGETS) == true && hasNoThumbnail,
                )
                if (recents.getTag(TAG_REMOTE_TARGETS) == true &&
                    hasNoThumbnail &&
                    isKnownTransitionTarget(recents, task)
                ) {
                    // 只有当前远端 Surface 对应的无缩略图 TaskView 才是交接占位卡。
                    // 普通下层卡在刚打开最近任务时也可能还未拿到 ThumbnailData，绝不能因此
                    // 暂停它的堆叠变换，否则远端结束时会从留缝位置瞬间跳到最终堆叠。
                    task.setTag(TAG_TRANSITION_STUB, true)
                } else if (!isKnownTransitionTarget(recents, task)) {
                    task.setTag(TAG_TRANSITION_STUB, false)
                }
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
                if (isRunning || isTransitionTask(recents, task)) {
                    // 即使暂不参与位移，也必须保留其本应处于的负 Z，不能让下层占位卡抢到顶层。
                    resetTaskToNative(task, z)
                    index++
                    continue
                }
                val transform = calculateTransform(recents, task) ?: continue
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
                if (task === runningTask) {
                    val landscape = isLandscape(recents)
                    runningPrimary = if (landscape) task.translationY else task.translationX
                    runningSecondary = if (landscape) task.translationX else task.translationY
                }
                index++
            }
            if (runningPrimary != null && runningSecondary != null) {
                syncLiveTile(recents, runningPrimary, runningSecondary)
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

    /** 释放模块写入的视觉属性，让中央运行卡完全由 Flyme 的远端动画控制。 */
    private fun resetTaskToNative(task: View, translationZ: Float = 0f) {
        if (task.getTag(TAG_ACTIVE) == true) {
            task.setTag(TAG_ACTIVE, false)
            task.setTag(TAG_SCALE, null)
            task.rotationY = 0f
            reapplyNativeOffsets(task)
            applyScaleMethod?.invoke(task)
        }
        task.translationZ = translationZ
    }

    private fun calculateTransform(recents: ViewGroup, task: View): Transform? {
        if (!shouldStack(recents)) return null
        val landscape = isLandscape(recents)
        val primarySize = taskSizeInScrollDirection(recents, landscape).coerceAtLeast(1)
        val screenPrimary = if (landscape) recents.measuredHeight else recents.measuredWidth
        val screenSecondary = if (landscape) recents.measuredWidth else recents.measuredHeight
        if (screenPrimary == 0 || screenSecondary == 0) return null

        val scroll = if (landscape) recents.scrollY else recents.scrollX
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
        val distance = (center - (scroll + screenPrimary / 2)).toFloat()
        val splinePosition = 3.0 + distance / primarySize
        val centerScale = math.getValue(IosRecentsMath.SPLINE_SCALE, 3.0).toFloat()
        val centerX = math.getValue(IosRecentsMath.SPLINE_X_COORD, 3.0).toFloat()
        val centerY = math.getValue(IosRecentsMath.SPLINE_Y_COORD, 3.0).toFloat()
        val targetPrimary = (math.getValue(IosRecentsMath.SPLINE_X_COORD, splinePosition).toFloat() - centerX) *
            screenPrimary - distance
        val targetSecondary = (math.getValue(IosRecentsMath.SPLINE_Y_COORD, splinePosition).toFloat() - centerY) *
            screenSecondary
        val targetScale = math.getValue(IosRecentsMath.SPLINE_SCALE, splinePosition).toFloat() /
            centerScale.coerceAtLeast(0.0001f)

        val isRemote = recents.getTag(TAG_REMOTE_TARGETS) == true
        val progress = resolveProgress(recents, isRemote)
        val nativeX = task.getTag(TAG_NATIVE_OFFSET_X) as? Float ?: 0f
        val nativeY = task.getTag(TAG_NATIVE_OFFSET_Y) as? Float ?: 0f
        val nativePrimary = if (landscape) nativeY else nativeX
        val nativeSecondary = if (landscape) nativeX else nativeY
        // 应用路径将原生页偏移与堆叠目标写入同一个 taskOffset 字段：p=0 保持原轨迹，p=1 到目标。
        val primary = if (isRemote) {
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
        val fullscreen = (recents.getTag(TAG_FULLSCREEN_PROGRESS) as? Float ?: 1f).coerceIn(0f, 1f)
        if (!isRemote) return fullscreen
        // 单通道 taskOffset 已把原生页偏移作为插值起点，不再以相邻页弹簧限速。
        // 后者只在松手后才补到终点，会造成“先留缝、再瞬间靠拢”。
        return fullscreen
    }

    private fun shouldStack(recents: ViewGroup): Boolean = try {
        (XposedHelpers.callMethod(recents, "showAsGrid") as? Boolean != true) &&
            (XposedHelpers.callMethod(recents, "getTaskViewCount") as? Int ?: 0) > 0
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
        }
    }

    private fun isLandscape(recents: ViewGroup): Boolean = try {
        val handler = XposedHelpers.callMethod(recents, "getPagedOrientationHandler")
        handler::class.java.name.contains("Landscape")
    } catch (_: Throwable) {
        false
    }

    private fun taskSizeInScrollDirection(recents: ViewGroup, landscape: Boolean): Int = try {
        val size = XposedHelpers.callMethod(recents, "getLastComputedTaskSize") as? Rect
        (if (landscape) size?.height() else size?.width())?.takeIf { it > 0 }
            ?: (if (landscape) recents.measuredHeight else recents.measuredWidth) / 2
    } catch (_: Throwable) {
        recents.measuredWidth / 2
    }

    private fun getRunningTaskView(recents: ViewGroup): View? = try {
        XposedHelpers.callMethod(recents, "getRunningTaskView") as? View
    } catch (_: Throwable) {
        null
    }

    private fun isRemoteRunningTask(recents: ViewGroup, task: View): Boolean {
        return recents.getTag(TAG_REMOTE_TARGETS) == true && task === getRunningTaskView(recents)
    }

    private fun isTransitionTask(recents: ViewGroup, task: View): Boolean {
        // 桌面路径不会创建远端 target，绝不能因旧的 runningTask 记录而跳过卡片。
        if (recents.getTag(TAG_REMOTE_TARGETS) != true) return false
        if (task.getTag(TAG_TRANSITION_STUB) == true) return true
        return isKnownTransitionTarget(recents, task)
    }

    /** 仅运行任务及 RecentsAnimationTargets.apps 中的 Surface 需要保持原生交接轨迹。 */
    private fun isKnownTransitionTarget(recents: ViewGroup, task: View): Boolean {
        if (task === getRunningTaskView(recents)) return true
        val transitionIds = recents.getTag(TAG_TRANSITION_TASK_IDS) as? IntArray ?: return false
        val taskIds = taskIdsOf(task)
        return taskIds.any { taskId -> transitionIds.any { it == taskId } }
    }

    private fun taskIdsOf(task: View): IntArray = try {
        XposedHelpers.callMethod(task, "getTaskIds") as? IntArray ?: IntArray(0)
    } catch (_: Throwable) {
        IntArray(0)
    }

    /** 从 RecentsAnimationTargets.apps 收集 Quick Switch / split 分支的全部远端任务 ID。 */
    private fun mergeTransitionTargetIds(recents: ViewGroup, targets: Any?) {
        try {
            val ids = LinkedHashSet<Int>()
            (recents.getTag(TAG_TRANSITION_TASK_IDS) as? IntArray)?.forEach { ids += it }
            val apps = XposedHelpers.getObjectField(targets, "apps") as? Array<*> ?: emptyArray<Any>()
            for (app in apps) {
                val taskId = app?.let { XposedHelpers.getIntField(it, "taskId") } ?: -1
                if (taskId >= 0) ids += taskId
            }
            if (ids.isNotEmpty()) recents.setTag(TAG_TRANSITION_TASK_IDS, ids.toIntArray())
        } catch (e: Throwable) {
            Logger.w(TAG, "读取远端任务 ID 失败: ${e.message}")
        }
    }

    /** TaskThumbnailViewDeprecated 没有 Bitmap 时会显示随深色模式变化的纯色底板。 */
    private fun hasNoThumbnail(task: View): Boolean = try {
        val container = XposedHelpers.callMethod(task, "getFirstTaskContainer") ?: return false
        val thumbnailView = XposedHelpers.callMethod(container, "getThumbnailViewDeprecated") ?: return false
        XposedHelpers.callMethod(thumbnailView, "getThumbnail") == null
    } catch (_: Throwable) {
        false
    }

    private fun setPlaceholderSuppressed(task: View, suppress: Boolean) {
        try {
            val container = XposedHelpers.callMethod(task, "getFirstTaskContainer") ?: return
            val thumbnail = XposedHelpers.callMethod(container, "getThumbnailViewDeprecated") as? View ?: return
            thumbnail.setTag(TAG_PLACEHOLDER_SUPPRESSED, suppress)
        } catch (_: Throwable) {
            // 缩略图容器在 TaskView 复用/回收帧中可能暂不可用，下一次刷新会重试。
        }
    }

    private fun clearPlaceholderSuppression(recents: ViewGroup, taskCl: Class<*>) {
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (taskCl.isInstance(task)) setPlaceholderSuppressed(task, suppress = false)
        }
    }

    private fun clearTransitionTask(recents: ViewGroup, taskCl: Class<*>, refresh: Boolean = true) {
        val hadTransition = recents.getTag(TAG_TRANSITION_TASK_IDS) != null
        recents.setTag(TAG_TRANSITION_TASK_IDS, null)
        for (i in 0 until recents.childCount) {
            val task = recents.getChildAt(i) ?: continue
            if (taskCl.isInstance(task)) task.setTag(TAG_TRANSITION_STUB, false)
        }
        if (hadTransition && refresh) refreshStack(recents, taskCl)
    }

    private fun syncLiveTile(recents: ViewGroup, primary: Float, secondary: Float) {
        try {
            if (XposedHelpers.callMethod(recents, "getEnableDrawingLiveTile") as? Boolean != true) return
            val handles = XposedHelpers.callMethod(recents, "getRemoteTargetHandles") as? Array<*> ?: return
            for (handle in handles) {
                val simulator = handle?.let { XposedHelpers.callMethod(it, "getTaskViewSimulator") } ?: continue
                val primaryFloat = XposedHelpers.getObjectField(simulator, "taskPrimaryTranslation") ?: continue
                val secondaryFloat = XposedHelpers.getObjectField(simulator, "taskSecondaryTranslation") ?: continue
                XposedHelpers.setFloatField(primaryFloat, "value", primary)
                XposedHelpers.setFloatField(secondaryFloat, "value", secondary)
            }
            XposedHelpers.callMethod(recents, "redrawLiveTile")
        } catch (e: Throwable) {
            Logger.w(TAG, "Live Tile 坐标同步失败: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findAdjacentPageOffsetProperty(recentsCl: Class<*>): FloatProperty<Any>? = try {
        recentsCl.fields.firstOrNull {
            it.name == "ADJACENT_PAGE_HORIZONTAL_OFFSET" && FloatProperty::class.java.isAssignableFrom(it.type)
        }?.get(null) as? FloatProperty<Any>
    } catch (e: Throwable) {
        Logger.w(TAG, "ADJACENT_PAGE_HORIZONTAL_OFFSET 未找到: ${e.message}")
        null
    }

    private fun readAdjacentPageOffset(recents: ViewGroup): Float = try {
        adjacentPageOffsetProperty?.get(recents) ?: 0f
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

    // --- cubic spline math (from Reverse-FlymeLauncher IosRecentsMath) ---
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

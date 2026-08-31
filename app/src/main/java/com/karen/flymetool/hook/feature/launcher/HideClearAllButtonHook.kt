package com.karen.flymetool.hook.feature.launcher

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 隐藏最近任务界面（Recents）的清空按钮。
 *
 * Flyme 在原生 AOSP 基础上同时保留了两个清空按钮：
 *  1. [mFlymeClearAllButton]：Flyme 定制的常驻底部按钮，在 [initExtraDecor] 里从父视图
 *     findViewById 取出后设为 VISIBLE，依靠 contentAlpha 控制透明度，是用户最常看到那个。
 *  2. [mClearAllButton]：AOSP 原生的"最后一页"清空按钮，构造函数里 inflate 出来初始 INVISIBLE，
 *     首次加载任务时通过 addView 作为 RecentsView 的最后一页加入，滑到最右才出现。
 *
 * 隐藏策略：在 [initExtraDecor] 的 afterHook 里把两个 View 都改为 [View.GONE]，
 * GONE 不参与 layout 累计也不渲染，page index（indexOfChild）仍然有效但页宽归零，
 * 滑到最右不会再出现空白页或可见按钮。反编译验证全文无后续 setVisibility(VISIBLE) 恢复路径，
 * 因此一次 GONE 即永久保持。
 *
 * 特征定位：所有类名、方法名、字段名均为语义化命名的公开契约，无混淆。
 */
object HideClearAllButtonHook : FeatureHook {

    private const val TAG = "HideClearAllButton"
    private const val TARGET_PACKAGE = "com.meizu.flyme.launcher"
    private const val FEATURE_KEY = "hide_clear_all_button"
    private const val RECENTS_VIEW_CLASS = "com.android.quickstep.views.RecentsView"

    /** Flyme 定制清空按钮字段名（带 Flyme 前缀特征）。 */
    private const val FIELD_FLYME_CLEAR_ALL = "mFlymeClearAllButton"
    /** AOSP 原生清空按钮字段名。 */
    private const val FIELD_AOSP_CLEAR_ALL = "mClearAllButton"

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != TARGET_PACKAGE) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        try {
            val recentsViewClass = Reflect.findClass(RECENTS_VIEW_CLASS, ctx.classLoader)
            if (!hookInitExtraDecor(ctx, recentsViewClass)) {
                fallbackHookInit(ctx, recentsViewClass)
            }
            Logger.i(TAG, "已挂载：隐藏最近任务清空按钮")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    /**
     * 主 hook：[initExtraDecor]，private void 无参，Flyme 12 定制方法。
     * @return true 表示成功挂载；false 表示找不到此方法，走 fallback。
     */
    private fun hookInitExtraDecor(ctx: HookContext, recentsViewClass: Class<*>): Boolean {
        return try {
            Reflect.hookMethodOn(
                ctx.api,
                recentsViewClass,
                "initExtraDecor",
            ) { chain ->
                val result = chain.proceed()
                hideClearButtons(chain.getThisObject())
                result
            }
            Logger.d(TAG) { "已挂载 initExtraDecor" }
            true
        } catch (_: NoSuchMethodError) {
            Logger.w(TAG, "未找到 initExtraDecor，尝试 fallback")
            false
        } catch (e: Throwable) {
            Logger.e(TAG, "挂钩 initExtraDecor 失败", e)
            false
        }
    }

    /**
     * 兜底 hook：[init]，public void 三参数（OverviewActionsView、SplitSelectStateController、
     * DesktopRecentsTransitionController）。该方法体末尾调用 initExtraDecor，挂载点等价。
     * 通过反射按方法签名特征查找（名字 + 参数数 + void 返回值），不依赖具体参数类名字符串。
     */
    private fun fallbackHookInit(ctx: HookContext, recentsViewClass: Class<*>) {
        try {
            val initMethod: Method? = recentsViewClass.declaredMethods.firstOrNull { method ->
                method.name == "init" &&
                    method.parameterTypes.size == 3 &&
                    method.returnType == Void.TYPE &&
                    !Modifier.isStatic(method.modifiers)
            }?.also { it.isAccessible = true }

            if (initMethod == null) {
                Logger.w(TAG, "fallback 找不到 init(三参) 方法，放弃挂载")
                return
            }
            Reflect.hookMethod(ctx.api, initMethod) { chain ->
                val result = chain.proceed()
                hideClearButtons(chain.getThisObject())
                result
            }
            Logger.i(TAG, "已挂载 init(${initMethod.parameterTypes.joinToString { it.simpleName }})")
        } catch (e: Throwable) {
            Logger.e(TAG, "fallback 挂载失败", e)
        }
    }

    /**
     * 把两个清空按钮都设为 [View.GONE] + alpha=0。
     * 对 mFlymeClearAllButton GONE 让它完全不占布局位置；
     * 对 mClearAllButton GONE 让其页宽归零但仍保留 childIndex（避免 indexOfChild 返回 -1 导致下游崩溃）。
     */
    private fun hideClearButtons(recentsView: Any?) {
        if (recentsView == null) return
        hideView(recentsView, FIELD_FLYME_CLEAR_ALL)
        hideView(recentsView, FIELD_AOSP_CLEAR_ALL)
    }

    private fun hideView(recentsView: Any, fieldName: String) {
        try {
            val view = Reflect.getObjectField(recentsView, fieldName) as? View
            if (view != null) {
                view.visibility = View.GONE
                view.alpha = 0f
                Logger.d(TAG) { "$fieldName -> GONE alpha=0" }
            } else {
                Logger.w(TAG, "$fieldName 字段为空或非 View 类型")
            }
        } catch (e: Throwable) {
            // NoSuchFieldError 等：字段不存在表示该 Flyme 版本可能没有对应清空按钮，按预期降级
            Logger.e(TAG, "取 $fieldName 字段失败", e)
        }
    }
}
package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object HideKeyguardShortcutsHook : FeatureHook {

    private const val KEYGUARD_BOTTOM_AREA_VIEW = "com.flyme.systemui.affordance.MZKeyguardBottomAreaView"
    private const val TAG = "HideKeyguardShortcut"

    private var hideFlashlight: Boolean = false
    private var hideCamera: Boolean = false

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return

        val flashlight = ctx.featureEnabled("hide_keyguard_flashlight")
        val camera = ctx.featureEnabled("hide_keyguard_camera")
        if (!flashlight && !camera) return

        hideFlashlight = flashlight
        hideCamera = camera

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(ctx)
            FlymeVersionUtils.isFlyme11() -> hookFlyme11(ctx)
            FlymeVersionUtils.isFlyme10() -> hookFlyme10(ctx)
            else -> hookFlyme10(ctx)
        }
        Logger.i(TAG, "已加载, hideFlashlight=$hideFlashlight, hideCamera=$hideCamera")
    }

    private fun hookFlyme12(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(KEYGUARD_BOTTOM_AREA_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "updateLeftRightClickVisibility",
            ) { chain ->
                val view = chain.getThisObject() as? View
                if (view == null) {
                    chain.proceed()
                } else {
                    if (hideFlashlight) {
                        val leftView = Reflect.getObjectField(view, "mLeftClickAffordanceView")
                        (leftView as? View)?.visibility = View.GONE
                    }

                    if (hideCamera) {
                        val rightView = Reflect.getObjectField(view, "mRightClickAffordanceView")
                        (rightView as? View)?.visibility = View.GONE
                    }

                    null
                }
            }

            Logger.i(TAG, "已挂载 updateLeftRightClickVisibility（Flyme 12）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Flyme 12 失败", e)
        }
    }

    private fun hookFlyme11(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(KEYGUARD_BOTTOM_AREA_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "updateLeftRightClickVisibility",
            ) { chain ->
                val view = chain.getThisObject() as? View
                if (view == null) {
                    chain.proceed()
                } else {
                    if (hideFlashlight) {
                        val leftView = Reflect.getObjectField(view, "mLeftClickAffordanceView")
                        (leftView as? View)?.visibility = View.GONE
                    }

                    if (hideCamera) {
                        val rightView = Reflect.getObjectField(view, "mRightClickAffordanceView")
                        (rightView as? View)?.visibility = View.GONE
                    }

                    null
                }
            }

            Logger.i(TAG, "已挂载 updateLeftRightClickVisibility（Flyme 11）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Flyme 11 失败", e)
        }
    }

    private fun hookFlyme10(ctx: HookContext) {
        hookUpdateLeftClickVisibility(ctx)
        hookUpdateRightClickVisibility(ctx)
    }

    private fun hookUpdateLeftClickVisibility(ctx: HookContext) {
        if (!hideFlashlight) return
        try {
            val clazz = Reflect.findClass(KEYGUARD_BOTTOM_AREA_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "updateLeftClickVisibility",
            ) { chain ->
                val leftClickAffordanceView = Reflect.getObjectField(chain.getThisObject(), "mLeftClickAffordanceView")
                if (leftClickAffordanceView != null) {
                    val view = leftClickAffordanceView as? View
                    view?.visibility = View.GONE
                    null
                } else {
                    chain.proceed()
                }
            }

            Logger.i(TAG, "已挂载 updateLeftClickVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateLeftClickVisibility 失败", e)
        }
    }

    private fun hookUpdateRightClickVisibility(ctx: HookContext) {
        if (!hideCamera) return
        try {
            val clazz = Reflect.findClass(KEYGUARD_BOTTOM_AREA_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "updateRightVisibility",
            ) { chain ->
                val rightClickAffordanceView = Reflect.getObjectField(chain.getThisObject(), "mRightClickAffordanceView")
                if (rightClickAffordanceView != null) {
                    val view = rightClickAffordanceView as? View
                    view?.visibility = View.GONE
                    null
                } else {
                    chain.proceed()
                }
            }

            Logger.i(TAG, "已挂载 updateRightVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateRightVisibility 失败", e)
        }
    }
}
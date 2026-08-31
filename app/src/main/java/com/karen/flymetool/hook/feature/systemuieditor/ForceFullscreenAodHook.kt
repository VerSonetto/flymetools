package com.karen.flymetool.hook.feature.systemuieditor

import android.content.Context
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 通过 AOD 效果项的数据结构定位全屏项，而非依赖外观编辑器中会随版本变化的混淆类名。
 *
 * 外观编辑器会按设备能力从效果列表过滤 FULLSCREEN。本 Hook 在列表提交给 RecyclerView
 * 后补回同一数据类型的 FULLSCREEN 项；原有点击逻辑会继续把对应效果值写入系统。
 */
object ForceFullscreenAodHook : FeatureHook {

    private const val TAG = "FullscreenAod"
    private const val FEATURE_KEY = "force_fullscreen_aod"
    private const val FULLSCREEN = "FULLSCREEN"
    private const val BASE_EFFECT = "SET_SAIL"
    private const val FULLSCREEN_DRAWABLE = "alive_photo_wallpaper_aod_fullscreen"
    private const val FULLSCREEN_LABEL = "full_image"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        try {
            Reflect.hookAllMethods(
                ctx.api,
                Reflect.findClass("androidx.recyclerview.widget.RecyclerView\$Adapter", ctx.classLoader),
                "notifyItemRangeInserted",
                block = { chain ->
                    val result = chain.proceed()
                    val adapter = chain.getThisObject() ?: return@hookAllMethods result
                    restoreFullscreenEffect(adapter)
                    result
                }
            )
            Logger.i(TAG, "已挂载 AOD 效果列表监听")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AOD 效果列表监听失败", e)
        }
    }

    private fun restoreFullscreenEffect(adapter: Any) {
        try {
            val slot = findEffectList(adapter) ?: return
            if (slot.items.any { effectName(it) == FULLSCREEN }) return

            val prototype = slot.items.firstOrNull { isAodEffectItem(it) } ?: return
            val effect = effectOf(prototype) ?: return
            val fullscreenEffect = effect.javaClass.enumConstants
                ?.filterIsInstance<Enum<*>>()
                ?.firstOrNull { it.name == FULLSCREEN }
                ?: return
            val context = findContext(adapter) ?: return
            val drawable = context.resources.getIdentifier(
                FULLSCREEN_DRAWABLE,
                "drawable",
                context.packageName,
            )
            val label = context.resources.getIdentifier(
                FULLSCREEN_LABEL,
                "string",
                context.packageName,
            )
            if (drawable == 0 || label == 0) {
                Logger.w(TAG, "未找到全屏 AOD 所需资源 (drawable=$drawable, label=$label)")
                return
            }

            val item = createEffectItem(prototype, fullscreenEffect, drawable, label) ?: return
            val insertAt = slot.items.indexOfLast { isAodEffectItem(it) } + 1
            replaceOrAppend(slot, item, insertAt)
            adapter.javaClass.methods.firstOrNull { method ->
                method.name == "notifyItemInserted" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.invoke(adapter, insertAt) ?: return
            Logger.i(TAG, "已补回全屏 AOD 效果项")
        } catch (e: Throwable) {
            Logger.e(TAG, "补回全屏 AOD 效果项失败", e)
        }
    }

    private fun findEffectList(adapter: Any): ListSlot? {
        return adapter.javaClass.declaredFields.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) || !List::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                val items = field.get(adapter) as? List<Any?> ?: return@mapNotNull null
                if (items.any { isAodEffectItem(it) }) ListSlot(field, adapter, items) else null
            }
            .firstOrNull()
    }

    private fun isAodEffectItem(item: Any?): Boolean {
        val effect = effectOf(item) ?: return false
        val constants = effect.javaClass.enumConstants?.filterIsInstance<Enum<*>>() ?: return false
        return constants.any { it.name == BASE_EFFECT } && constants.any { it.name == FULLSCREEN }
    }

    private fun effectName(item: Any?): String? = effectOf(item)?.name

    private fun effectOf(item: Any?): Enum<*>? {
        if (item == null) return null
        return item.javaClass.declaredFields.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(item) as? Enum<*>
            }
            .firstOrNull()
    }

    private fun createEffectItem(
        prototype: Any,
        effect: Enum<*>,
        drawable: Int,
        label: Int,
    ): Any? {
        val constructor = prototype.javaClass.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 3 &&
                types[0] == effect.javaClass &&
                types[1] == Int::class.javaPrimitiveType &&
                types[2] == Int::class.javaPrimitiveType
        } ?: return null
        constructor.isAccessible = true
        return constructor.newInstance(effect, drawable, label)
    }

    private fun replaceOrAppend(slot: ListSlot, item: Any, insertAt: Int) {
        val updated = ArrayList(slot.items)
        updated.add(insertAt, item)
        slot.field.set(slot.adapter, updated)
    }

    private fun findContext(adapter: Any): Context? {
        return adapter.javaClass.declaredFields.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) || !Context::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(adapter) as? Context
            }
            .firstOrNull()
    }

    private data class ListSlot(
        val field: Field,
        val adapter: Any,
        val items: List<Any?>,
    )
}
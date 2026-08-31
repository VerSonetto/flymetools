package com.karen.flymetool.hook.feature.settings

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object NeverLockScreenHook : FeatureHook {

    private const val TAG = "NeverLockScreen"
    private const val NEVER_LOCK_VALUE = Int.MAX_VALUE.toString()

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("never_lock_screen")) return
        if (ctx.packageName != "com.android.settings") return

        Logger.i(TAG, "Hook 设置应用，添加永不锁屏选项")

        val clazz = Reflect.findClass(
            "com.meizu.settings.widget.TimeoutListPreference",
            ctx.classLoader
        )
        Reflect.hookConstructorOn(
            ctx.api,
            clazz,
            android.content.Context::class.java,
            android.util.AttributeSet::class.java,
        ) { chain ->
            val result = chain.proceed()

            val preference = chain.getThisObject()

            try {
                val initialEntriesField = preference.javaClass.getDeclaredField("mInitialEntries")
                initialEntriesField.isAccessible = true
                val entries = initialEntriesField.get(preference) as Array<CharSequence>

                val initialValuesField = preference.javaClass.getDeclaredField("mInitialValues")
                initialValuesField.isAccessible = true
                val values = initialValuesField.get(preference) as Array<CharSequence>

                val newEntries = entries.copyOf(entries.size + 1)
                newEntries[entries.size] = "永不锁屏"
                initialEntriesField.set(preference, newEntries)

                val newValues = values.copyOf(values.size + 1)
                newValues[values.size] = NEVER_LOCK_VALUE
                initialValuesField.set(preference, newValues)

                Reflect.callMethod(ctx.api, preference, "setEntries", newEntries)
                Reflect.callMethod(ctx.api, preference, "setEntryValues", newValues)

                Logger.i(TAG, "添加永不锁屏选项成功, entries=${entries.size} -> ${newEntries.size}")
            } catch (e: Exception) {
                Logger.e(TAG, "添加永不锁屏选项失败", e)
            }

            return@hookConstructorOn result
        }
    }
}
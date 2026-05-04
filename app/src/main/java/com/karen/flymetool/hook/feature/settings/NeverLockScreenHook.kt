package com.karen.flymetool.hook.feature.settings

import com.karen.flymetool.hook.base.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NeverLockScreenHook {

    private const val TAG = "NeverLockScreen"
    private const val NEVER_LOCK_VALUE = Int.MAX_VALUE.toString()

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        Logger.i(TAG, "Hook 设置应用，添加永不锁屏选项")

        XposedHelpers.findAndHookConstructor(
            "com.meizu.settings.widget.TimeoutListPreference",
            lpparam.classLoader,
            android.content.Context::class.java,
            android.util.AttributeSet::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val preference = param.thisObject
                    
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
                        
                        XposedHelpers.callMethod(preference, "setEntries", newEntries)
                        XposedHelpers.callMethod(preference, "setEntryValues", newValues)
                        
                        Logger.i(TAG, "添加永不锁屏选项成功, entries=${entries.size} -> ${newEntries.size}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "添加永不锁屏选项失败", e)
                    }
                }
            }
        )
    }
}

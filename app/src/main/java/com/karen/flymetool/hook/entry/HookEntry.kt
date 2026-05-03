package com.karen.flymetool.hook.entry

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook 入口接口
 * 每个作用域应用实现此接口，管理该应用下的所有 Hook
 */
interface HookEntry {
    /**
     * 目标包名
     */
    val targetPackage: String

    /**
     * 初始化该应用下的所有 Hook
     */
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam)
}

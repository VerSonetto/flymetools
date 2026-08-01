package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
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

/**
 * 按 supplier 惰性创建并执行 Hook。
 * 单个 Hook 的 object <clinit> 失败时只跳过该功能，不影响同作用域其它 Hook。
 */
fun safeInitHooks(
    targetPackage: String,
    lpparam: XC_LoadPackage.LoadPackageParam,
    hookFactories: List<Pair<String, () -> FeatureHook>>
) {
    for ((name, factory) in hookFactories) {
        try {
            val hook = factory()
            hook.handle(lpparam, targetPackage)
        } catch (e: Throwable) {
            Logger.e(targetPackage, "Hook $name failed", e)
        }
    }
}

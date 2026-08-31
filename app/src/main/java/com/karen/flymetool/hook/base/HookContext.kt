package com.karen.flymetool.hook.base

import io.github.libxposed.api.XposedInterface

/**
 * 单个功能 Hook 的执行上下文，替代经典 API 的 XC_LoadPackage.LoadPackageParam。
 *
 * 统一携带框架接口与本作用域信息；Hook 内部不再直接接触框架回调参数，
 * 只通过 [api] 挂 hook / 调用方法，通过各 feature* 快捷方法读配置。
 */
class HookContext(
    /** 框架接口（模块入口即 XposedInterface 实现） */
    val api: XposedInterface,
    /** 当前作用域包名（system_server 场景固定为 "android"） */
    val packageName: String,
    /** 当前作用域的类加载器（包回调 = defaultClassLoader；system_server = 框架加载器） */
    val classLoader: ClassLoader,
) {
    fun featureEnabled(key: String): Boolean = XposedPrefs.isFeatureEnabled(packageName, key)
    fun featureValue(key: String, defaultValue: Int): Int = XposedPrefs.getFeatureValue(packageName, key, defaultValue)
    fun featureExtraValue(key: String, suffix: String, defaultValue: Int): Int =
        XposedPrefs.getFeatureExtraValue(packageName, key, suffix, defaultValue)
    fun featureStringSet(key: String, defaultValue: Set<String>): Set<String> =
        XposedPrefs.getFeatureStringSet(packageName, key, defaultValue)
    fun featureString(key: String, defaultValue: String): String =
        XposedPrefs.getFeatureString(packageName, key, defaultValue)
}
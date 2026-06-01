package com.karen.flymetool.hook.base

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 单个功能 Hook 的统一接口
 * 每个 hook 自己负责检查开关、读参数、挂载
 */
interface FeatureHook {
    fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String)
}

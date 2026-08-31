package com.karen.flymetool.hook.base

/**
 * 单个功能 Hook 的统一接口
 * 每个 hook 自己负责检查开关、读参数、挂载
 */
interface FeatureHook {
    fun handle(ctx: HookContext)
}
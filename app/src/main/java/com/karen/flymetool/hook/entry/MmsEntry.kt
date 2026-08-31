package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object MmsEntry : HookEntry {
    override val targetPackage = "com.android.mms"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "AutoCopyVerifyCodeHook" to { com.karen.flymetool.hook.feature.mms.AutoCopyVerifyCodeHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}

package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object ConnectivitySettingsEntry : HookEntry {
    override val targetPackage = "com.meizu.connectivitysettings"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "ShowWifiPasswordHook" to { com.karen.flymetool.hook.feature.connectivitysettings.ShowWifiPasswordHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}

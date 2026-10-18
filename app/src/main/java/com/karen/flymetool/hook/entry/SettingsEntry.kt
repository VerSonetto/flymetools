package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object SettingsEntry : HookEntry {
    override val targetPackage = "com.android.settings"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "NeverLockScreenHook" to { com.karen.flymetool.hook.feature.settings.NeverLockScreenHook },
        "ForceNotificationEnableHook" to { com.karen.flymetool.hook.feature.settings.ForceNotificationEnableHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}

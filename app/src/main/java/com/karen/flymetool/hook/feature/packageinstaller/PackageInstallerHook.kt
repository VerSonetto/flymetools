package com.karen.flymetool.hook.feature.packageinstaller

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object PackageInstallerHook : FeatureHook {

    private const val ACTIVITY_CLASS = "com.android.packageinstaller.FlymePackageInstallerActivity"
    private const val TAG = "PackageInstaller"

    private var autoInstallEnabled = false

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("skip_install_scan")) return
        if (ctx.packageName != "com.android.packageinstaller") return

        autoInstallEnabled = ctx.featureEnabled("auto_install")

        try {
            hookStartInstallScan(ctx)
            Logger.i(TAG, "已成功安装 Hook")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun hookStartInstallScan(ctx: HookContext) {
        val clazz = Reflect.findClass(ACTIVITY_CLASS, ctx.classLoader)

        Reflect.hookMethodOn(
            ctx.api,
            clazz,
            "startInstallScan",
        ) { chain ->
            val thisObject = chain.getThisObject()

            Reflect.setBooleanField(thisObject, "mIsVirusCheckFinish", true)
            Reflect.setBooleanField(thisObject, "mIsVirusCheckResultSafe", true)
            Reflect.setBooleanField(thisObject, "receivedMzStoreInfo", true)
            Reflect.setIntField(thisObject, "isDisposaled", 0)
            Reflect.setBooleanField(thisObject, "isBlackApp", false)

            val mzStoreAppInfo = Reflect.getObjectField(thisObject, "mzStoreAppInfo")
            if (mzStoreAppInfo != null) {
                Reflect.setBooleanField(mzStoreAppInfo, "querySuccess", false)
                Reflect.setBooleanField(mzStoreAppInfo, "showConfirm", false)
                Reflect.setBooleanField(mzStoreAppInfo, "icpStatus", false)
                Reflect.setBooleanField(mzStoreAppInfo, "isDisposalApp", false)
                Reflect.setBooleanField(mzStoreAppInfo, "isBlackApp", false)
            }

            Logger.d(TAG) { "跳过安装扫描" }

            if (autoInstallEnabled) {
                Reflect.callMethod(ctx.api, thisObject, "doInstallFlyme")
                Logger.d(TAG) { "已触发自动安装" }
            } else {
                Reflect.callMethod(ctx.api, thisObject, "updateViewForNewState", 3)
                Logger.d(TAG) { "显示安装确认界面" }
            }

            null
        }

        Logger.i(TAG, "已挂载 startInstallScan")
    }
}
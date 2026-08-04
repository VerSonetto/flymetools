package com.karen.flymetool.hook.feature.packageinstaller

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object PackageInstallerHook : FeatureHook {

    private const val ACTIVITY_CLASS = "com.android.packageinstaller.FlymePackageInstallerActivity"
    private const val TAG = "PackageInstaller"

    private var autoInstallEnabled = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "skip_install_scan")) return
        if (lpparam.packageName != "com.android.packageinstaller") return

        autoInstallEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "auto_install")

        try {
            hookStartInstallScan(lpparam)
            Logger.i(TAG, "已成功安装 Hook")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun hookStartInstallScan(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(ACTIVITY_CLASS, lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            clazz,
            "startInstallScan",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val thisObject = param.thisObject

                    XposedHelpers.setBooleanField(thisObject, "mIsVirusCheckFinish", true)
                    XposedHelpers.setBooleanField(thisObject, "mIsVirusCheckResultSafe", true)
                    XposedHelpers.setBooleanField(thisObject, "receivedMzStoreInfo", true)
                    XposedHelpers.setIntField(thisObject, "isDisposaled", 0)
                    XposedHelpers.setBooleanField(thisObject, "isBlackApp", false)

                    val mzStoreAppInfo = XposedHelpers.getObjectField(thisObject, "mzStoreAppInfo")
                    if (mzStoreAppInfo != null) {
                        XposedHelpers.setBooleanField(mzStoreAppInfo, "querySuccess", false)
                        XposedHelpers.setBooleanField(mzStoreAppInfo, "showConfirm", false)
                        XposedHelpers.setBooleanField(mzStoreAppInfo, "icpStatus", false)
                        XposedHelpers.setBooleanField(mzStoreAppInfo, "isDisposalApp", false)
                        XposedHelpers.setBooleanField(mzStoreAppInfo, "isBlackApp", false)
                    }

                    Logger.d(TAG) { "跳过安装扫描" }

                    if (autoInstallEnabled) {
                        XposedHelpers.callMethod(thisObject, "doInstallFlyme")
                        Logger.d(TAG) { "已触发自动安装" }
                    } else {
                        XposedHelpers.callMethod(thisObject, "updateViewForNewState", 3)
                        Logger.d(TAG) { "显示安装确认界面" }
                    }

                    param.result = null
                }
            }
        )

        Logger.i(TAG, "已挂载 startInstallScan")
    }
}

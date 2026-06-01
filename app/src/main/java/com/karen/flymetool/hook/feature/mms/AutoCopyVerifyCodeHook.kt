package com.karen.flymetool.hook.feature.mms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object AutoCopyVerifyCodeHook : FeatureHook {

    private const val TAG = "AutoCopyVerifyCode"
    private const val PACKAGE_NAME = "com.android.mms"

    private const val VERIFY_CODE_TYPE = 1
    private const val DEX_UTIL_CLASS = "cn.com.flyme.smartzip.DexUtil"
    private const val MMS_APP_CLASS = "com.android.mms.MmsApp"

    private var appContext: Context? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "auto_copy_verify_code")) return
        if (lpparam.packageName != PACKAGE_NAME) return

        hookMmsAppOnCreate(lpparam)
        hookDexUtilParse(lpparam)
    }

    private fun hookMmsAppOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mmsAppClass = lpparam.classLoader.loadClass(MMS_APP_CLASS)

            for (method in mmsAppClass.declaredMethods) {
                if (method.parameterTypes.isEmpty() &&
                    (method.name == "onCreate" || method.returnType == Void.TYPE)) {

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                appContext = param.thisObject as? Context
                                Logger.i(TAG, "Captured MmsApp context")
                            } catch (e: Throwable) {
                                Logger.e(TAG, "Failed to capture context", e)
                            }
                        }
                    })
                    Logger.i(TAG, "Hooked MmsApp.onCreate")
                    return
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook MmsApp onCreate", e)
        }
    }

    private fun hookDexUtilParse(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val dexUtilClass = lpparam.classLoader.loadClass(DEX_UTIL_CLASS)

            for (method in dexUtilClass.declaredMethods) {
                if (method.name == "parse" && method.parameterTypes.size == 1) {

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val parseResult = param.result ?: return

                                handleParseResult(parseResult)

                            } catch (e: Throwable) {
                                Logger.e(TAG, "Error in parse hook", e)
                            }
                        }
                    })

                    Logger.i(TAG, "Hooked DexUtil.parse")
                    return
                }
            }

            Logger.w(TAG, "parse method not found in DexUtil")

        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook DexUtil.parse", e)
        }
    }

    private fun handleParseResult(parseResult: Any) {
        try {
            val mType = XposedHelpers.getIntField(parseResult, "mType")

            if (mType != VERIFY_CODE_TYPE) {
                Logger.d(TAG, "Not verify code SMS, mType=$mType")
                return
            }

            val mContent = XposedHelpers.getObjectField(parseResult, "mContent") as? String
            if (mContent.isNullOrBlank()) {
                Logger.d(TAG, "Verify code content is empty")
                return
            }

            Logger.i(TAG, "Detected verify code SMS, content: $mContent")
            copyToClipboard(mContent)

        } catch (e: Throwable) {
            Logger.e(TAG, "Error handling ParseResult", e)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val context = appContext ?: run {
                Logger.e(TAG, "Context is null")
                return
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(null, text)
            clipboard.setPrimaryClip(clip)

            Logger.i(TAG, "Verify code copied to clipboard: $text")

        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}

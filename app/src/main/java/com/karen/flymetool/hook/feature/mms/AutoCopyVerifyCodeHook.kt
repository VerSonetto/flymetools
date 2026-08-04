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
                                Logger.i(TAG, "已捕获 MmsApp context")
                            } catch (e: Throwable) {
                                Logger.e(TAG, "捕获 context 失败", e)
                            }
                        }
                    })
                    Logger.i(TAG, "已挂载 MmsApp.onCreate")
                    return
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MmsApp onCreate 失败", e)
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
                                Logger.e(TAG, "parse Hook 异常", e)
                            }
                        }
                    })

                    Logger.i(TAG, "已挂载 DexUtil.parse")
                    return
                }
            }

            Logger.w(TAG, "DexUtil 中未找到 parse 方法")

        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 DexUtil.parse 失败", e)
        }
    }

    private fun handleParseResult(parseResult: Any) {
        try {
            val mType = XposedHelpers.getIntField(parseResult, "mType")

            if (mType != VERIFY_CODE_TYPE) {
                Logger.d(TAG) { "非验证码短信, mType=$mType" }
                return
            }

            val mContent = XposedHelpers.getObjectField(parseResult, "mContent") as? String
            if (mContent.isNullOrBlank()) {
                Logger.d(TAG) { "验证码内容为空" }
                return
            }

            Logger.i(TAG, "检测到验证码短信, content: $mContent")
            copyToClipboard(mContent)

        } catch (e: Throwable) {
            Logger.e(TAG, "处理 ParseResult 异常", e)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val context = appContext ?: run {
                Logger.e(TAG, "Context 为空")
                return
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(null, text)
            clipboard.setPrimaryClip(clip)

            Logger.i(TAG, "验证码已复制到剪贴板: $text")

        } catch (e: Throwable) {
            Logger.e(TAG, "复制到剪贴板失败", e)
        }
    }
}

package com.karen.flymetool.hook.feature.share

import android.app.Activity
import android.app.Dialog
import android.widget.Button
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object AutoAcceptShareHook : FeatureHook {

    private const val TAG = "AutoAcceptShare"
    private const val BASE_ACTIVITY_CLASS = "com.meizu.share.base.BaseBluetoothPermissionActivity"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "auto_accept_share")) return
        if (lpparam.packageName != "com.meizu.share") return

        hookOnResume(lpparam)
    }

    private fun hookOnResume(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseClass = XposedHelpers.findClass(BASE_ACTIVITY_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(baseClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return

                    val className = activity.javaClass.name
                    if (className != "com.meizu.share.mutual.MzShareIncomingConfirmActivity" &&
                        className != "com.meizu.share.BluetoothOppIncomingFileConfirmActivity"
                    ) {
                        return
                    }

                    Logger.i(TAG, "收到确认弹窗 Activity: $className")

                    activity.window.decorView.postDelayed({
                        try {
                            autoAccept(activity)
                        } catch (e: Throwable) {
                            Logger.e(TAG, "自动接受失败", e)
                        }
                    }, 0)
                }
            })

            Logger.i(TAG, "已挂载 BaseBluetoothPermissionActivity.onResume")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 onResume 失败", e)
        }
    }

    private fun autoAccept(activity: Activity) {
        val dialog = findDialog(activity)
        if (dialog == null) {
            Logger.w(TAG, "Activity 中未找到对话框")
            return
        }

        if (!dialog.isShowing) {
            Logger.d(TAG) { "对话框尚未显示，跳过" }
            return
        }

        val button = findPositiveButton(dialog)
        if (button == null) {
            Logger.w(TAG, "对话框中未找到确认按钮")
            return
        }

        Logger.i(TAG, "正在自动点击确认按钮")
        button.performClick()
    }

    private fun findDialog(activity: Any): Dialog? {
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (Dialog::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val dialog = field.get(activity) as? Dialog
                        if (dialog != null) {
                            return dialog
                        }
                    } catch (e: Throwable) {
                        Logger.d(TAG) { "访问对话框字段失败: ${field.name}" }
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findPositiveButton(dialog: Dialog): Button? {
        var clazz: Class<*>? = dialog.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (method in clazz.declaredMethods) {
                if (method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.returnType == Button::class.java
                ) {
                    try {
                        method.isAccessible = true
                        val button = method.invoke(dialog, DialogInterface.BUTTON_POSITIVE) as? Button
                        if (button != null) {
                            return button
                        }
                    } catch (e: Throwable) {
                        Logger.d(TAG) { "调用 getButton 方法失败: ${method.name}" }
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private object DialogInterface {
        const val BUTTON_POSITIVE = -1
    }
}

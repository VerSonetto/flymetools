package com.karen.flymetool.ui.screen

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.util.RootUtils

fun restartScopedApp(context: Context, app: ScopedApp) {
    Thread {
        if (!RootUtils.isRootGranted()) {
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(context, "未授予 Root 权限，无法重启", Toast.LENGTH_SHORT).show()
            }
            return@Thread
        }

        if (app.packageName == "android") {
            val result = RootUtils.reboot()
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    if (result) "正在重启系统..." else "重启系统失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return@Thread
        }

        val result = RootUtils.killPackage(app.packageName)
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(
                context,
                if (result) "重启 ${app.name} 成功" else "重启 ${app.name} 失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}

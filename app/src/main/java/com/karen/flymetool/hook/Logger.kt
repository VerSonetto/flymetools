package com.karen.flymetool.hook

import de.robv.android.xposed.XposedBridge

object Logger {

    private const val TAG = "FlymeTool"

    var debugEnabled = false

    private val loggedOnce = mutableSetOf<String>()

    fun d(hook: String, message: String) {
        if (debugEnabled) {
            XposedBridge.log("[$TAG][$hook] $message")
        }
    }

    fun i(hook: String, message: String) {
        XposedBridge.log("[$TAG][$hook] $message")
    }

    fun w(hook: String, message: String) {
        XposedBridge.log("[$TAG][$hook] WARN: $message")
    }

    fun e(hook: String, message: String, throwable: Throwable? = null) {
        val errorMsg = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        XposedBridge.log("[$TAG][$hook] ERROR: $errorMsg")
    }

    fun once(hook: String, message: String) {
        val key = "$hook:$message"
        if (key !in loggedOnce) {
            loggedOnce.add(key)
            i(hook, message)
        }
    }

    fun clearOnceLogs() {
        loggedOnce.clear()
    }
}

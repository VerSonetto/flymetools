package com.karen.flymetool.util

import java.io.BufferedReader
import java.io.InputStreamReader

object FlymeVersionUtils {

    private var cachedFullVersion: String? = null

    fun getFullVersion(): String {
        cachedFullVersion?.let { return it }

        val version = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, "ro.build.display.id") as? String ?: ""
        } catch (_: Exception) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop ro.build.display.id"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                reader.readLine()?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        cachedFullVersion = version
        return version
    }

    fun isFlyme10(): Boolean {
        return getFullVersion().startsWith("Flyme 10")
    }

    fun isFlyme11(): Boolean {
        return getFullVersion().startsWith("Flyme 11")
    }

    fun isFlyme12(): Boolean {
        return getFullVersion().startsWith("Flyme 12")
    }

    // 包名 -> 该作用域可见的版本前缀列表（不在列表中的作用域默认全版本可见）
    private val scopeVisibleOn: Map<String, List<String>> = mapOf(
        "com.meizu.share" to listOf("Flyme 10"),
        "com.meizu.battery" to listOf("Flyme 12"),
    )

    fun isScopeAvailable(packageName: String): Boolean {
        val versions = scopeVisibleOn[packageName] ?: return true
        val current = getFullVersion()
        return versions.any { current.startsWith(it) }
    }
}

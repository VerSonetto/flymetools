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
}

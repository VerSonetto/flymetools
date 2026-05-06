package com.karen.flymetool.util

import java.util.concurrent.TimeUnit

object RootUtils {

    fun runRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (finished) {
                process.exitValue() == 0
            } else {
                process.destroy()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun killPackage(packageName: String): Boolean {
        return runRootCommand("pid=\$(pidof $packageName); if [ -n \"\$pid\" ]; then kill -9 \$pid; fi")
    }

    fun reboot(): Boolean {
        return runRootCommand("reboot")
    }
}

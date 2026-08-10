package com.karen.flymetool.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object RootUtils {

    private const val MAX_STDERR_CHARS = 4096

    data class RootCommandResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val stderr: String
    ) {
        val isSuccess: Boolean
            get() = !timedOut && exitCode == 0
    }

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

    fun isRootGranted(): Boolean {
        return runRootCommand("id")
    }

    /**
     * 以 Root 权限流式读取命令输出，避免日志较大时因 stdout 管道塞满而阻塞。
     */
    fun streamRootCommand(
        command: String,
        timeoutSeconds: Long = 30,
        onStdoutLine: (String) -> Unit
    ): RootCommandResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdoutFailure = AtomicReference<Throwable?>(null)
        val stderrText = StringBuilder()

        val stdoutThread = thread(
            name = "FlymeToolRootStdout",
            isDaemon = true
        ) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(onStdoutLine)
                }
            } catch (throwable: Throwable) {
                stdoutFailure.compareAndSet(null, throwable)
                process.destroyForcibly()
            }
        }
        val stderrThread = thread(
            name = "FlymeToolRootStderr",
            isDaemon = true
        ) {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val remaining = MAX_STDERR_CHARS - stderrText.length
                        if (remaining > 0) {
                            stderrText.append(line.take(remaining)).append('\n')
                        }
                    }
                }
            } catch (_: Throwable) {
                // 命令结束或超时后流可能被关闭，无需额外处理。
            }
        }

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        }

        if (!finished) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }

        // 进程已退出或被强制结束，两个流均会到达 EOF；等待消费完成后再返回，
        // 避免调用方在最后几行仍写入 ZIP 时提前关闭条目。
        stdoutThread.join()
        stderrThread.join()

        val timedOut = !finished
        if (!timedOut) {
            stdoutFailure.get()?.let { throw it }
        }

        return RootCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = timedOut,
            stderr = stderrText.toString().trim()
        )
    }

    fun killPackage(packageName: String): Boolean {
        return runRootCommand("pid=\$(pidof $packageName); if [ -n \"\$pid\" ]; then kill -9 \$pid; fi")
    }

    fun reboot(): Boolean {
        return runRootCommand("reboot")
    }
}

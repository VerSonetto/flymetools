package com.karen.flymetool.hook.base

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一日志系统（v3）。
 *
 * 设计目标：让 AI / 开发者拿到日志后能快速精准定位问题。
 *
 * 输出格式（双通道：Logcat tag=FlymeTool + 框架日志 api.log）：
 *   [FlymeTool][v2.7][E][IosStackedRecents] IosStackedRecentsHook#mount:72 | 挂载失败 | pkg=com.meizu.flyme.launcher
 *       java.lang.ClassNotFoundException: ...
 *           at ...
 *
 * 特性：
 * - 每条日志自动附带调用源定位（类#方法:行号），R8 不混淆模块类且保留行号，Release 下同样可靠
 * - 错误日志必带完整堆栈：传 throwable 用其堆栈；未传自动抓当前调用链伪堆栈（包内最多 5 帧）
 * - 调试日志 [d] 懒求值，由 debugEnabled 控制（UI「关于」页 __debug__ 开关 + logcat 命令热切换）
 * - 关键上下文用 kv 附加参数输出，便于机器解析
 * - once 去重 FIFO 有界（512），防止长时间运行内存增长
 */
object Logger {

    /** Logcat tag，可用 adb logcat -s FlymeTool 过滤 */
    const val TAG = "FlymeTool"

    /** logcat 热切换命令通道 tag */
    const val CMD_TAG = "FlymeToolCmd"

    @Volatile
    var debugEnabled = false

    @Volatile
    var moduleVersion: String = "unknown"

    /** 框架日志通道（libxposed API 101 的 api.log），由 XposedInit 在 onModuleLoaded 注入 */
    @Volatile
    private var frameworkApi: XposedInterface? = null

    private const val LEVEL_D = 'D'
    private const val LEVEL_I = 'I'
    private const val LEVEL_W = 'W'
    private const val LEVEL_E = 'E'

    /** once() 去重上限 */
    private const val ONCE_MAX = 512

    /** FIFO 有界去重集合（插入序，超限淘汰最旧） */
    private val loggedOnce = object : LinkedHashMap<String, Boolean>(ONCE_MAX + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > ONCE_MAX
    }

    private val commandListenerStarted = AtomicBoolean(false)

    /** 由 XposedInit 进程启动时调用。 */
    fun init(version: String, debug: Boolean) {
        moduleVersion = version
        debugEnabled = debug
        if (debug) {
            i("Boot", "调试日志已通过 prefs 开启")
        }
    }

    /** 注入框架接口（现代 API 的日志输出通道）。 */
    fun attachApi(interface_: XposedInterface) {
        frameworkApi = interface_
    }

    // ---------- 业务 API ----------

    /** 调试日志（懒求值）：热路径 / 高频细节，受 [debugEnabled] 控制。 */
    fun d(tag: String, message: () -> String) {
        if (debugEnabled) {
            log(LEVEL_D, tag, message(), null, null)
        }
    }

    /** 普通信息：挂载成功、状态变化、配置结果。 */
    fun i(tag: String, message: String) {
        log(LEVEL_I, tag, message, null, null)
    }

    /** 警告：预期内降级（未找到方法/类/字段）、可恢复分支。 */
    fun w(tag: String, message: String, vararg extra: Pair<String, Any?>) {
        log(LEVEL_W, tag, message, null, extra)
    }

    /**
     * 错误：真正的失败，必须传 [throwable]；
     * 未传时自动抓当前调用链伪堆栈（包内最多 5 帧）。
     * 关键上下文通过 [extra] 以 key=value 形式附加。
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        vararg extra: Pair<String, Any?>
    ) {
        log(LEVEL_E, tag, message, throwable, extra)
    }

    /** 只打一次的日志；[key] 显式指定去重键，FIFO 有界。 */
    fun once(tag: String, key: String, message: String) {
        val dedupKey = "$tag:$key"
        val shouldLog = synchronized(loggedOnce) {
            if (dedupKey in loggedOnce) {
                false
            } else {
                loggedOnce[dedupKey] = true
                true
            }
        }
        if (shouldLog) {
            i(tag, message)
        }
    }

    /**
     * 启动 logcat 命令监听线程（守护线程），支持运行时热切换调试开关：
     *   adb shell log -t FlymeToolCmd "debug=on"   // 热开启
     *   adb shell log -t FlymeToolCmd "debug=off"  // 热关闭
     * 本进程写的确认回执也通过 FlymeToolCmd 通道返回，`adb logcat -s FlymeToolCmd` 可见。
     * 环境受限（SELinux 等）时静默降级，不影响任何功能。
     */
    fun startCommandListener() {
        if (!commandListenerStarted.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-s", CMD_TAG))
                process.inputStream.bufferedReader().forEachLine { line ->
                    val cmd = line.trim()
                    when (cmd) {
                        "debug=on" -> applyDebugCommand(true)
                        "debug=off" -> applyDebugCommand(false)
                    }
                }
            } catch (_: Throwable) {
                // SELinux / 环境限制无法读取 logcat 命令，静默降级
            }
        }, "FlymeToolCmdListener")
        thread.isDaemon = true
        thread.start()
    }

    private fun applyDebugCommand(enabled: Boolean) {
        debugEnabled = enabled
        Log.i(CMD_TAG, if (enabled) "debug=on applied" else "debug=off applied")
        val status = if (enabled) "热开启" else "热关闭"
        frameworkApi?.log(Log.INFO, TAG, "[$TAG][v$moduleVersion][I][Boot] 调试日志已$status (logcat 命令)")
    }

    // ---------- 内部实现 ----------

    private fun log(
        level: Char,
        tag: String,
        message: String,
        throwable: Throwable?,
        extra: Array<out Pair<String, Any?>>?
    ) {
        val caller = findCaller()
        val text = format(level, tag, message, caller, extra)
        when (level) {
            LEVEL_D -> Log.d(TAG, text)
            LEVEL_I -> Log.i(TAG, text)
            LEVEL_W -> Log.w(TAG, text)
            LEVEL_E -> Log.e(TAG, text, throwable)
        }
        if (level == LEVEL_E) {
            val stack = if (throwable != null) {
                throwable.stackTraceToString()
            } else {
                pseudoStack()
            }
            frameworkApi?.log(Log.ERROR, TAG, text, throwable)
            frameworkApi?.log(Log.ERROR, TAG, stack)
        } else {
            val lg = when (level) {
                LEVEL_D -> Log.DEBUG
                LEVEL_W -> Log.WARN
                else -> Log.INFO
            }
            frameworkApi?.log(lg, TAG, text)
        }
    }

    private fun format(
        level: Char,
        tag: String,
        message: String,
        caller: StackTraceElement?,
        extra: Array<out Pair<String, Any?>>?
    ): String {
        val sb = StringBuilder()
        sb.append('[').append(TAG)
            .append("][v").append(moduleVersion)
            .append("][").append(level)
            .append("][").append(tag).append("] ")
        sb.append(formatCaller(caller))
        sb.append(" | ").append(message)
        if (!extra.isNullOrEmpty()) {
            sb.append(" | ")
            sb.append(extra.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" })
        }
        return sb.toString()
    }

    /** 定位调用源：从当前堆栈找第一个非 Logger 的模块内帧。 */
    private fun findCaller(): StackTraceElement? {
        return try {
            val stack = Throwable().stackTrace
            for (frame in stack) {
                val className = frame.className
                if (className.startsWith("com.karen.flymetool.") &&
                    className != "com.karen.flymetool.hook.base.Logger" &&
                    !className.startsWith("com.karen.flymetool.hook.base.Logger$")
                ) {
                    return frame
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 输出可读调用源：IosStackedRecentsHook#mount:72（lambda 帧归一到外层类名，行号保留）。 */
    private fun formatCaller(frame: StackTraceElement?): String {
        if (frame == null) return "unknown#?:?"
        val simple = frame.className.substringAfterLast('.').substringBefore('$')
        return "$simple#${frame.methodName}:${frame.lineNumber}"
    }

    /** 未传 throwable 时的调用链伪堆栈：包内最多 5 帧。 */
    private fun pseudoStack(): String {
        return try {
            val stack = Throwable().stackTrace
            val sb = StringBuilder()
            var count = 0
            var passedLogger = false
            for (frame in stack) {
                val className = frame.className
                if (!passedLogger) {
                    if (className.startsWith("com.karen.flymetool.hook.base.Logger")) continue
                    passedLogger = true
                }
                if (!className.startsWith("com.karen.flymetool.")) continue
                sb.append("\n    at ")
                    .append(className).append('.').append(frame.methodName)
                    .append('(').append(frame.fileName ?: "?").append(':').append(frame.lineNumber).append(')')
                count++
                if (count >= 5) break
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun formatValue(v: Any?): String {
        return if (v is Throwable) {
            "${v.javaClass.simpleName}: ${v.message}"
        } else {
            v?.toString() ?: "null"
        }
    }
}

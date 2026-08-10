package com.karen.flymetool.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.karen.flymetool.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExportManager {

    private const val LSPOSED_LOG_COMMAND =
        "cat /data/adb/lspd/log/modules_*.log 2>/dev/null"
    private const val LSPOSED_VERSION_COMMAND =
        "cat /data/adb/modules/zygisk_lsposed/module.prop 2>/dev/null"
    private const val LOGCAT_COMMAND =
        "logcat -b all -d -v threadtime -s FlymeTool FlymeToolCmd"
    private const val EXPORT_DIR = "log_exports"
    private const val ROOT_COMMAND_TIMEOUT_SECONDS = 30L

    private val fileTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val displayTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val newline = "\n".toByteArray(Charsets.UTF_8)

    suspend fun createArchive(context: Context): File = withContext(Dispatchers.IO) {
        if (!RootUtils.isRootGranted()) {
            throw IllegalStateException("未获得 Root 权限，无法读取 LSPosed 模块日志")
        }

        val exportTime = LocalDateTime.now()
        val exportDir = File(context.cacheDir, EXPORT_DIR)
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IllegalStateException("无法创建日志导出目录")
        }

        exportDir.listFiles()
            ?.filter { it.isFile }
            ?.forEach { it.delete() }

        val baseName = "FlymeTool-logs-${exportTime.format(fileTimeFormatter)}"
        val tempFile = File(exportDir, "$baseName.tmp")
        val archiveFile = File(exportDir, "$baseName.zip")

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                writeDeviceInfo(zip, exportTime)
                writeLsposedModuleLog(zip)
                writeLogcat(zip)
            }

            if (archiveFile.exists() && !archiveFile.delete()) {
                throw IllegalStateException("无法覆盖同名日志文件")
            }
            if (!tempFile.renameTo(archiveFile)) {
                tempFile.copyTo(archiveFile, overwrite = true)
                tempFile.delete()
            }
            archiveFile
        } catch (throwable: Throwable) {
            tempFile.delete()
            archiveFile.delete()
            throw throwable
        }
    }

    fun shareArchive(context: Context, archiveFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.file_provider",
            archiveFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FlymeTool 模块日志")
            clipData = ClipData.newRawUri(archiveFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "导出 FlymeTool 模块日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    private fun writeDeviceInfo(zip: ZipOutputStream, exportTime: LocalDateTime) {
        val lsposedVersion = readLsposedVersion()
        val deviceInfo = buildString {
            appendLine("导出时间: ${exportTime.format(displayTimeFormatter)}")
            appendLine("FlymeTool 版本: ${BuildConfig.VERSION_NAME}")
            appendLine("模块包名: ${BuildConfig.APPLICATION_ID}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("系统版本: ${FlymeVersionUtils.getFullVersion().ifBlank { Build.DISPLAY }}")
            appendLine("LSPosed: $lsposedVersion")
            appendLine()
            appendLine("日志范围:")
            appendLine("- 当前 LSPosed 会话中属于 FlymeTool 的模块日志")
            appendLine("- FlymeTool/FlymeToolCmd Logcat")
        }
        zip.putNextEntry(ZipEntry("device-info.txt"))
        zip.write(deviceInfo.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeLsposedModuleLog(zip: ZipOutputStream) {
        val filter = LsposedModuleLogFilter()
        val lineCount = AtomicInteger(0)

        zip.putNextEntry(ZipEntry("lsposed-current.txt"))
        val result = RootUtils.streamRootCommand(
            command = LSPOSED_LOG_COMMAND,
            timeoutSeconds = ROOT_COMMAND_TIMEOUT_SECONDS
        ) { line ->
            if (filter.shouldKeep(line)) {
                writeLine(zip, line)
                lineCount.incrementAndGet()
            }
        }
        zip.closeEntry()

        when {
            result.timedOut -> throw IllegalStateException("读取 LSPosed 模块日志超时")
            !result.isSuccess -> throw IllegalStateException("未找到 LSPosed 当前模块日志")
            lineCount.get() == 0 -> throw IllegalStateException("当前 LSPosed 日志中没有 FlymeTool 记录")
        }
    }

    private fun writeLogcat(zip: ZipOutputStream) {
        val lineCount = AtomicInteger(0)

        zip.putNextEntry(ZipEntry("logcat-current.txt"))
        val result = RootUtils.streamRootCommand(
            command = LOGCAT_COMMAND,
            timeoutSeconds = ROOT_COMMAND_TIMEOUT_SECONDS
        ) { line ->
            writeLine(zip, line)
            lineCount.incrementAndGet()
        }
        if (!result.isSuccess || lineCount.get() == 0) {
            writeLine(zip, "# 当前未读取到 FlymeTool Logcat，LSPosed 模块日志仍可用于排查。")
        }
        zip.closeEntry()
    }

    private fun readLsposedVersion(): String {
        var version = "未知"
        val result = RootUtils.streamRootCommand(
            command = LSPOSED_VERSION_COMMAND,
            timeoutSeconds = 10
        ) { line ->
            if (line.startsWith("version=")) {
                version = line.substringAfter('=').trim().ifBlank { "未知" }
            }
        }
        return if (result.isSuccess) version else "未知"
    }

    private fun writeLine(zip: ZipOutputStream, line: String) {
        zip.write(line.toByteArray(Charsets.UTF_8))
        zip.write(newline)
    }
}

internal class LsposedModuleLogFilter {

    private var keepCurrentRecord = false

    fun shouldKeep(line: String): Boolean {
        if (line.startsWith("----part ")) {
            keepCurrentRecord = false
            return false
        }

        val isRecordHeader = line.startsWith("[ ") && line.contains("/LSPosedFramework ]")
        if (isRecordHeader) {
            keepCurrentRecord = line.contains("[com.karen.flymetool,XposedBridge,")
        }
        return keepCurrentRecord
    }
}

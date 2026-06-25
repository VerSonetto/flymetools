package com.karen.flymetool.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSize: Long,
)

object UpdateManager {

    private const val API = "https://api.github.com/repos/sonettoTK/flymetools/releases/latest"
    private var cached: UpdateInfo? = null
    private var localVer: String? = null

    private fun localVersion(context: Context): String {
        if (localVer != null) return localVer!!
        localVer = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { null }
        return localVer ?: "1.0"
    }

    suspend fun check(context: Context): UpdateInfo? {
        if (cached != null) return cached
        val current = localVersion(context)
        val result = runCatching { doCheck(current) }.getOrNull()
        cached = result
        return result
    }

    private suspend fun doCheck(currentVersion: String) = withContext(Dispatchers.IO) {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "FlymeTool")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode != 200) return@withContext null

        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val tag = json.getString("tag_name").removePrefix("v")
        val notes = json.optString("body", "").trim()

        val assets = json.getJSONArray("assets")
        var downloadUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == "app-release.apk") {
                downloadUrl = a.getString("browser_download_url")
                apkSize = a.optLong("size", 0)
                break
            }
        }
        if (downloadUrl.isBlank() || !isNewer(currentVersion, tag)) return@withContext null

        UpdateInfo(tag, downloadUrl, notes, apkSize)
    }

    private fun isNewer(local: String, remote: String): Boolean {
        val l = Regex("\\d+").findAll(local).map { it.value.toInt() }.toList()
        val r = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(l.size, r.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = r.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    private val MIRRORS = listOf(
        "https://gh.monlor.com/https://%s",
        "https://github.geekery.cn/https://%s",
        "https://git.yylx.win/https://%s",
        "https://cors.isteed.cc/%s",
    )

    suspend fun download(context: Context, originalUrl: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val rest = originalUrl.removePrefix("https://")
            val urls = MIRRORS.map { it.format(rest) } + originalUrl

            val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
            val file = File(dir, "FlymeTool.apk")
            var lastError: Exception? = null

            for (url in urls) {
                try {
                    return@withContext doDownload(url, file, onProgress)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: Exception("所有镜像源均不可用")
        }

    private fun doDownload(url: String, file: File, onProgress: (Int) -> Unit): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.connect()

        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(file).use { output ->
                val buf = ByteArray(8192)
                var read = 0L
                var lastPct = -1
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        return file
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.file_provider", file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun cleanup(context: Context) {
        val dir = File(context.cacheDir, "updates")
        if (dir.exists()) dir.deleteRecursively()
    }
}

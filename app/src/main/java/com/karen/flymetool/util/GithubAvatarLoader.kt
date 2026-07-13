package com.karen.flymetool.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object GithubAvatarLoader {
    const val profileUrl = "https://github.com/Ruyue-Kinsenka"
    private const val avatarUrl = "$profileUrl.png?size=160"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    @Volatile
    private var cachedBitmap: Bitmap? = null

    fun preload() {
        if (cachedBitmap != null) return
        scope.launch { load() }
    }

    suspend fun load(): Bitmap? {
        cachedBitmap?.let { return it }
        return loadMutex.withLock {
            cachedBitmap?.let { return@withLock it }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(avatarUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        connection.disconnect()
                        return@withContext null
                    }
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        ?.also { connection.disconnect() }
                }.getOrNull()
            }
            cachedBitmap = bitmap
            bitmap
        }
    }
}

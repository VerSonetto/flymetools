package com.karen.flymetool.ui.component

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val ICON_BITMAP_SIZE = 192
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pm = context.packageManager

    val bitmap by produceState<ImageBitmap?>(
        initialValue = iconCache[packageName],
        key1 = packageName,
        key2 = pm
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    iconCache.getOrPut(packageName) {
                        pm.getApplicationIcon(packageName)
                            .toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE)
                            .asImageBitmap()
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    }

    Box(modifier = modifier) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

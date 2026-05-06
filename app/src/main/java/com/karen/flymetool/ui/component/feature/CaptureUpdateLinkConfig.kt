package com.karen.flymetool.ui.component.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CaptureUpdateLinkConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    val info = remember { readUpdateInfo(context) }

    if (info.url.isEmpty()) {
        NoDataHint()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        InfoRow(label = "版本", value = info.latestVersion)
        InfoRow(label = "系统版本", value = info.systemVersion)
        InfoRow(label = "包大小", value = info.fileSize)
        InfoRow(label = "类型", value = formatVerType(info.verType, info.packageType))
        if (info.captureTime > 0) {
            InfoRow(label = "抓取时间", value = formatTimestamp(info.captureTime))
        }

        Column {
            Text(
                text = "下载链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = info.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = { copyToClipboard(context, info.url) },
                modifier = Modifier.height(36.dp)
            ) {
                Text("复制链接", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { openInBrowser(context, info.url) },
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("浏览器打开", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun NoDataHint() {
    Text(
        text = "暂未抓取到更新包链接，请在系统更新中检查更新后返回查看",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(200.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class UpdateInfo(
    val url: String = "",
    val latestVersion: String = "",
    val fileSize: String = "",
    val systemVersion: String = "",
    val verType: String = "",
    val packageType: Int = 0,
    val captureTime: Long = 0
)

private const val PROVIDER_AUTHORITY = "com.karen.flymetool.captured_update_provider"

private fun readUpdateInfo(context: Context): UpdateInfo {
    var cursor: android.database.Cursor? = null
    return try {
        val uri = Uri.parse("content://$PROVIDER_AUTHORITY/update")
        cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            UpdateInfo(
                url = cursor.getString(cursor.getColumnIndexOrThrow("updateUrl")) ?: "",
                latestVersion = cursor.getString(cursor.getColumnIndexOrThrow("latestVersion")) ?: "",
                fileSize = cursor.getString(cursor.getColumnIndexOrThrow("fileSize")) ?: "",
                systemVersion = cursor.getString(cursor.getColumnIndexOrThrow("systemVersion")) ?: "",
                verType = cursor.getString(cursor.getColumnIndexOrThrow("verType")) ?: "",
                packageType = cursor.getInt(cursor.getColumnIndexOrThrow("packageType")) ?: 0,
                captureTime = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")) ?: 0L
            )
        } else {
            UpdateInfo()
        }
    } catch (_: Throwable) {
        UpdateInfo()
    } finally {
        cursor?.close()
    }
}

private fun formatVerType(verType: String, packageType: Int): String {
    val typeLabel = when {
        verType.isEmpty() -> "稳定版"
        verType == "stable" -> "稳定版"
        verType == "beta" -> "体验版"
        verType == "daily" -> "日更版"
        else -> verType
    }
    val packageLabel = if (packageType == 1) " (差分包)" else ""
    return typeLabel + packageLabel
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (_: Throwable) {
        ""
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("update_url", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun openInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: Throwable) {
        Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
    }
}

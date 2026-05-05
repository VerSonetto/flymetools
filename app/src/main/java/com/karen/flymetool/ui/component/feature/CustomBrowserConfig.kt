package com.karen.flymetool.ui.component.feature

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppIcon

data class BrowserApp(
    val packageName: String,
    val label: String
)

@Composable
fun CustomBrowserConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var selectedBrowser by remember {
        mutableStateOf(
            PrefsHelper.getFeatureString(context, packageName, featureKey, "")
        )
    }

    val browsers = remember { getInstalledBrowsers(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "选择浏览器",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Aicy 建议打开链接时将使用选择的浏览器",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrowserItem(
                packageName = "",
                label = "系统默认浏览器",
                isSelected = selectedBrowser.isEmpty(),
                onClick = {
                    selectedBrowser = ""
                    PrefsHelper.setFeatureString(context, packageName, featureKey, "")
                }
            )

            browsers.forEach { browser ->
                BrowserItem(
                    packageName = browser.packageName,
                    label = browser.label,
                    isSelected = selectedBrowser == browser.packageName,
                    onClick = {
                        selectedBrowser = browser.packageName
                        PrefsHelper.setFeatureString(context, packageName, featureKey, browser.packageName)
                    }
                )
            }

            if (browsers.isEmpty()) {
                Text(
                    text = "未检测到已安装的浏览器",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun BrowserItem(
    packageName: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (packageName.isNotEmpty()) {
            AppIcon(
                packageName = packageName,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Spacer(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (packageName.isNotEmpty()) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun getInstalledBrowsers(context: Context): List<BrowserApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
    val resolveInfoList = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

    return resolveInfoList
        .filter { it.activityInfo != null }
        .map { resolveInfo ->
            BrowserApp(
                packageName = resolveInfo.activityInfo.packageName,
                label = resolveInfo.loadLabel(pm).toString()
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

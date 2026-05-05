package com.karen.flymetool.ui.component.feature

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionList
import com.karen.flymetool.ui.component.SelectionOption

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

    val options = browsers.map { browser ->
        SelectionOption(
            value = browser.packageName,
            label = browser.label,
            subLabel = browser.packageName,
            iconContent = {
                AppIcon(
                    packageName = browser.packageName,
                    modifier = Modifier.size(36.dp)
                )
            }
        )
    }

    val defaultOption = SelectionOption(
        value = "",
        label = "系统默认浏览器",
        subLabel = null,
        iconContent = null
    )

    SelectionGroup(
        title = "选择浏览器",
        description = "Aicy 建议打开链接时将使用选择的浏览器"
    ) {
        SelectionList(
            options = listOf(defaultOption) + options,
            selectedValue = selectedBrowser,
            onSelected = { browserPackage ->
                selectedBrowser = browserPackage
                PrefsHelper.setFeatureString(context, packageName, featureKey, browserPackage)
            },
            emptyText = if (browsers.isEmpty()) "未检测到已安装的浏览器" else null
        )
    }
}

private data class BrowserApp(
    val packageName: String,
    val label: String
)

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

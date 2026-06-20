package com.karen.flymetool.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.ui.component.SelectionItemWithIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveNotificationForceAppSelectScreen(
    packageName: String,
    featureKey: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedPackages by remember {
        mutableStateOf(
            PrefsHelper.getFeatureStringSet(context, packageName, featureKey, emptySet())
        )
    }

    val apps = remember { getInstalledApps(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "选择应用",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "选中应用的通知将以胶囊形式显示在状态栏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selectedPackages
                    SelectionItemWithIcon(
                        label = app.label,
                        subLabel = app.packageName,
                        isSelected = isSelected,
                        onClick = {
                            val newSet = selectedPackages.toMutableSet()
                            if (isSelected) newSet.remove(app.packageName) else newSet.add(app.packageName)
                            selectedPackages = newSet
                            PrefsHelper.setFeatureStringSet(context, packageName, featureKey, newSet)
                        },
                        iconContent = {
                            AppIcon(
                                packageName = app.packageName,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

private data class AppInfo(
    val packageName: String,
    val label: String
)

private fun getInstalledApps(context: android.content.Context): List<AppInfo> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { it.enabled && pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { appInfo ->
            AppInfo(
                packageName = appInfo.packageName,
                label = appInfo.loadLabel(pm).toString()
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

package com.karen.flymetool.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.ui.component.FeatureListRow
import com.karen.flymetool.ui.component.visibleFeatures
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FeatureGroupScreen(
    app: ScopedApp,
    groupName: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val groupFeatures = remember(app.packageName, groupName) {
        AppData.getFeaturesByGroup(app.packageName, groupName)
    }
    // 组内 dependsOn / exclusiveWith 可能指向同包其他功能，状态需覆盖整包
    val allFeatures = remember(app.packageName) { AppData.getFeatures(app.packageName) }
    val featureStates = remember(app.packageName, allFeatures) {
        allFeatures.associate { feature ->
            feature.key to mutableStateOf(
                PrefsHelper.isFeatureEnabled(context, app.packageName, feature.key)
            )
        }
    }
    val visible = visibleFeatures(groupFeatures, featureStates)

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.exclude(WindowInsets.navigationBars),
        topBar = {
            SmallTopAppBar(
                title = groupName,
                color = MiuixTheme.colorScheme.background,
                titleColor = MiuixTheme.colorScheme.onBackground,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { restartScopedApp(context, app) }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visible.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (groupFeatures.isEmpty()) "该分组暂无功能" else "暂无可见功能",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                    }
                }
            } else {
                items(
                    items = visible,
                    key = { it.key }
                ) { feature ->
                    FeatureListRow(
                        packageName = app.packageName,
                        feature = feature,
                        featureStates = featureStates,
                        context = context,
                        onNavigate = onNavigate
                    )
                }
            }
        }
    }
}

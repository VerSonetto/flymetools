package com.karen.flymetool.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.ui.component.FeatureGroupCard
import com.karen.flymetool.ui.component.FeatureListRow
import com.karen.flymetool.ui.component.visibleFeatures
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppDetailScreen(
    app: ScopedApp,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sections = remember(app.packageName) { AppData.getFeatureSections(app.packageName) }
    val allFeatures = remember(app.packageName) { AppData.getFeatures(app.packageName) }

    val featureStates = remember(app.packageName, allFeatures) {
        allFeatures.associate { feature ->
            feature.key to mutableStateOf(
                PrefsHelper.isFeatureEnabled(context, app.packageName, feature.key)
            )
        }
    }

    val visibleUngrouped = visibleFeatures(sections.ungrouped, featureStates)

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.exclude(WindowInsets.navigationBars),
        topBar = {
            // 顶栏不放应用名，避免与下方身份区重复；身份信息只在内容区出现一次
            SmallTopAppBar(
                title = "",
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
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AppIdentityHeader(app = app)
            }

            if (sections.groups.isNotEmpty()) {
                item {
                    SectionTitle(text = "功能分组")
                }
                items(
                    items = sections.groups,
                    key = { "group_$it" }
                ) { group ->
                    FeatureGroupCard(
                        title = group,
                        onClick = {
                            onNavigate(Screen.FeatureGroup.createRoute(app.packageName, group))
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (visibleUngrouped.isNotEmpty()) {
                item {
                    SectionTitle(
                        text = if (sections.groups.isNotEmpty()) "其他功能" else "功能配置"
                    )
                }
                items(
                    items = visibleUngrouped,
                    key = { it.key }
                ) { feature ->
                    FeatureListRow(
                        packageName = app.packageName,
                        feature = feature,
                        featureStates = featureStates,
                        context = context,
                        onNavigate = onNavigate,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (allFeatures.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "暂无可配置功能",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.title4,
        color = MiuixTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

/**
 * 应用身份唯一展示：图标 + 名称 + 包名，顶栏不再重复名称。
 */
@Composable
private fun AppIdentityHeader(app: ScopedApp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            packageName = app.packageName,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = app.packageName,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

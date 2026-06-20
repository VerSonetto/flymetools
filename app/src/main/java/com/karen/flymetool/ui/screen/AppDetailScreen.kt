package com.karen.flymetool.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.HookFeature
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.ui.component.FeatureSwitch
import com.karen.flymetool.ui.component.feature.FeatureConfig
import com.karen.flymetool.util.RootUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    app: ScopedApp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val features = AppData.getFeatures(app.packageName)
    val groups = AppData.getFeatureGroups(app.packageName)
    val ungroupedFeatures = AppData.getUngroupedFeatures(app.packageName)

    val featureStates = remember {
        features.associate { feature ->
            feature.key to mutableStateOf(PrefsHelper.isFeatureEnabled(context, app.packageName, feature.key))
        }
    }

    val expandedStates = remember {
        groups.associateWith { mutableStateOf(false) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppHeaderSection(
                    app = app,
                    onBack = onBack,
                    onRestart = { restartScopedApp(context, app) }
                )
            }

            if (groups.isNotEmpty()) {
                item {
                    Text(
                        text = "功能分组",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                groups.forEach { group ->
                    item(key = "group_$group") {
                        val groupFeatures = AppData.getFeaturesByGroup(app.packageName, group)

                        ExpandableGroupCard(
                            title = group,
                            isExpanded = expandedStates[group]?.value ?: false,
                            onToggle = { expandedStates[group]?.value = !(expandedStates[group]?.value ?: false) },
                            packageName = app.packageName,
                            groupFeatures = groupFeatures,
                            featureStates = featureStates,
                            context = context
                        )
                    }
                }
            }

            if (ungroupedFeatures.isNotEmpty()) {
                item {
                    Text(
                        text = if (groups.isNotEmpty()) "其他功能" else "功能配置",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                items(ungroupedFeatures) { feature ->
                    FeatureEntry(
                        packageName = app.packageName,
                        feature = feature,
                        featureStates = featureStates,
                        context = context,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            if (features.isEmpty()) {
                item {
                    EmptyState(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeaderSection(
    app: ScopedApp,
    onBack: () -> Unit,
    onRestart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRestart,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    packageName = app.packageName,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = 24.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun ExpandableGroupCard(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    packageName: String,
    groupFeatures: List<HookFeature>,
    featureStates: Map<String, MutableState<Boolean>>,
    context: Context,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "arrow_rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .graphicsLayer { clip = true },
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                groupFeatures.forEachIndexed { index, feature ->
                    FeatureEntryCompact(
                        packageName = packageName,
                        feature = feature,
                        featureStates = featureStates,
                        context = context,
                        isLast = index == groupFeatures.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureEntryCompact(
    packageName: String,
    feature: HookFeature,
    featureStates: Map<String, MutableState<Boolean>>,
    context: Context,
    isLast: Boolean = false
) {
    val dependsOnKey = feature.dependsOn
    val dependencyEnabled = if (dependsOnKey != null) {
        featureStates[dependsOnKey]?.value ?: false
    } else {
        true
    }

    val visibleUnlessKey = feature.visibleUnless
    val hiddenByVisibleUnless = if (visibleUnlessKey != null) {
        featureStates[visibleUnlessKey]?.value ?: false
    } else {
        false
    }

    val shouldShow = (dependsOnKey == null || dependencyEnabled) && !hiddenByVisibleUnless

    if (shouldShow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
            FeatureSwitch(
                title = feature.label,
                description = feature.description,
                checked = featureStates[feature.key]?.value ?: false,
                onCheckedChange = { newValue ->
                    featureStates[feature.key]?.value = newValue
                    PrefsHelper.setFeatureEnabled(context, packageName, feature.key, newValue)
                }
            )

            AnimatedVisibility(
                visible = featureStates[feature.key]?.value == true,
                enter = expandVertically(tween(180)) + fadeIn(tween(120)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(100))
            ) {
                FeatureConfig(
                    featureKey = feature.key,
                    packageName = packageName
                )
            }
            
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun FeatureEntry(
    packageName: String,
    feature: HookFeature,
    featureStates: Map<String, MutableState<Boolean>>,
    context: Context,
    modifier: Modifier = Modifier
) {
    val dependsOnKey = feature.dependsOn
    val dependencyEnabled = if (dependsOnKey != null) {
        featureStates[dependsOnKey]?.value ?: false
    } else {
        true
    }

    val visibleUnlessKey = feature.visibleUnless
    val hiddenByVisibleUnless = if (visibleUnlessKey != null) {
        featureStates[visibleUnlessKey]?.value ?: false
    } else {
        false
    }

    val shouldShow = (dependsOnKey == null || dependencyEnabled) && !hiddenByVisibleUnless

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150))
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            FeatureSwitch(
                title = feature.label,
                description = feature.description,
                checked = featureStates[feature.key]?.value ?: false,
                onCheckedChange = { newValue ->
                    featureStates[feature.key]?.value = newValue
                    PrefsHelper.setFeatureEnabled(context, packageName, feature.key, newValue)
                }
            )

            AnimatedVisibility(
                visible = featureStates[feature.key]?.value == true,
                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
            ) {
                FeatureConfig(
                    featureKey = feature.key,
                    packageName = packageName
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "暂无可配置功能",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun restartScopedApp(context: android.content.Context, app: ScopedApp) {
    Thread {
        if (!RootUtils.isRootGranted()) {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "未授予 Root 权限，无法重启", Toast.LENGTH_SHORT).show()
            }
            return@Thread
        }

        if (app.packageName == "android") {
            val result = RootUtils.reboot()
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    if (result) "正在重启系统..." else "重启系统失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return@Thread
        }

        val result = RootUtils.killPackage(app.packageName)
        (context as? android.app.Activity)?.runOnUiThread {
            Toast.makeText(
                context,
                if (result) "重启 ${app.name} 成功" else "重启 ${app.name} 失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}

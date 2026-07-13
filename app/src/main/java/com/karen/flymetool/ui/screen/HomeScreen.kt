package com.karen.flymetool.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.util.RootUtils
import com.karen.flymetool.util.UpdateManager
import com.karen.flymetool.util.UpdateInfo
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import java.io.File
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.state.ToggleableState

@Composable
fun HomeScreen(
    onAppClick: (ScopedApp) -> Unit
) {
    val context = LocalContext.current
    val apps = AppData.getScopedApps(context)
    var showRestartDialog by remember { mutableStateOf(false) }
    var showIntroDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(true) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    var updateChecked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!PrefsHelper.isIntroShown(context)) {
            showIntroDialog = true
        }

        if (!updateChecked) {
            updateChecked = true
            val info = UpdateManager.check(context)
            checkingUpdate = false
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            } else {
                UpdateManager.cleanup(context)
            }
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showRestartDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HeaderSection(
                updateInfo = updateInfo,
                checkingUpdate = checkingUpdate,
                onCheckUpdate = { showUpdateDialog = true },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(apps) { index, app ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(300, delayMillis = index * 80)) +
                                slideInVertically(tween(300, delayMillis = index * 80)) { it / 2 }
                    ) {
                        AppListItem(
                            app = app,
                            onClick = { onAppClick(app) }
                        )
                    }
                }
            }
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            info = updateInfo!!,
            downloading = downloading,
            progress = downloadProgress,
            error = downloadError,
            onDismiss = { showUpdateDialog = false },
            onDownload = {
                downloading = true
                downloadError = null
                scope.launch {
                    try {
                        val file = UpdateManager.download(
                            context, updateInfo!!.downloadUrl
                        ) { pct -> downloadProgress = pct }
                        downloading = false
                        showUpdateDialog = false
                        downloadedFile = file
                    } catch (e: Exception) {
                        downloadError = e.message
                        downloading = false
                    }
                }
            },
        )
    }

    if (downloadedFile != null) {
        InstallConfirmDialog(
            fileName = downloadedFile!!.name,
            onInstall = {
                UpdateManager.install(context, downloadedFile!!)
                downloadedFile = null
            },
            onDismiss = { downloadedFile = null },
        )
    }

    if (showRestartDialog) {
        RestartScopeDialog(
            apps = apps,
            onDismiss = { showRestartDialog = false },
            onConfirm = { selectedApps ->
                showRestartDialog = false
                restartScopedApps(context, selectedApps)
            }
        )
    }

    if (showIntroDialog) {
        IntroDialog(
            onDismiss = {
                PrefsHelper.markIntroShown(context)
                showIntroDialog = false
            }
        )
    }
}

@Composable
private fun RestartScopeDialog(
    apps: List<ScopedApp>,
    onDismiss: () -> Unit,
    onConfirm: (List<ScopedApp>) -> Unit
) {
    val selectedApps = remember { mutableStateListOf<ScopedApp>() }
    val allSelected = selectedApps.size == apps.size

    WindowDialog(
        show = true,
        title = "重启作用域",
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        Column(
            modifier = Modifier.heightIn(max = 480.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (allSelected) {
                                selectedApps.clear()
                            } else {
                                selectedApps.clear()
                                selectedApps.addAll(apps)
                            }
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    state = if (allSelected) ToggleableState.On else ToggleableState.Off,
                    onClick = {
                        if (allSelected) {
                            selectedApps.clear()
                        } else {
                            selectedApps.clear()
                            selectedApps.addAll(apps)
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "全选",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (selectedApps.contains(app)) {
                                        selectedApps.remove(app)
                                    } else {
                                        selectedApps.add(app)
                                    }
                                }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            state = if (selectedApps.contains(app)) ToggleableState.On else ToggleableState.Off,
                            onClick = {
                                if (selectedApps.contains(app)) {
                                    selectedApps.remove(app)
                                } else {
                                    selectedApps.add(app)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = app.name,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = "取消",
                onClick = { dismiss?.invoke() },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "重启 (${selectedApps.size})",
                onClick = {
                    onConfirm(selectedApps.toList())
                    dismiss?.invoke()
                },
                enabled = selectedApps.isNotEmpty(),
                colors = if (selectedApps.isNotEmpty())
                    ButtonDefaults.textButtonColorsPrimary()
                else ButtonDefaults.textButtonColors(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun IntroDialog(
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "FlymeTool",
        summary = "本模块基于 Flyme 10 开发，更高版本未经测试。\n使用前请备好救砖模块。",
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current
        TextButton(
            text = "我知道了",
            onClick = { dismiss?.invoke() },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    downloading: Boolean,
    progress: Int,
    error: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = "发现新版本 v${info.latestVersion}",
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        Column {
            if (info.releaseNotes.isNotBlank()) {
                Text(
                    text = info.releaseNotes,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            if (info.apkSize > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "大小: ${info.apkSize / 1024 / 1024} MB",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            if (downloading) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "下载中 $progress%",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "下载失败: $error",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!downloading) {
                TextButton(
                    text = "稍后再说",
                    onClick = { dismiss?.invoke() },
                    modifier = Modifier.weight(1f)
                )
            }
            if (error != null || !downloading) {
                TextButton(
                    text = if (error != null) "重试" else "下载更新",
                    onClick = onDownload,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    fileName: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = "下载完成",
        summary = "安装包已下载完毕，是否立即安装？",
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = "取消",
                onClick = { dismiss?.invoke() },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "安装",
                onClick = {
                    onInstall()
                    dismiss?.invoke()
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeaderSection(
    updateInfo: UpdateInfo?,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 8.dp)
    ) {
        Text(
            text = "FlymeTool",
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onBackground,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Flyme 系统增强工具",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.3f),
                            MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
        )
    }

    if (updateInfo != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCheckUpdate
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "发现新版本 v${updateInfo.latestVersion}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "点击更新",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AppListItem(
    app: ScopedApp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            packageName = app.packageName,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = app.name,
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = app.packageName,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun restartScopedApps(context: android.content.Context, apps: List<ScopedApp>) {
    if (apps.isEmpty()) return

    Thread {
        if (!RootUtils.isRootGranted()) {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "未授予 Root 权限，无法重启", Toast.LENGTH_SHORT).show()
            }
            return@Thread
        }

        if (apps.any { it.packageName == "android" }) {
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

        var successCount = 0
        var failCount = 0
        for (app in apps) {
            if (RootUtils.killPackage(app.packageName)) {
                successCount++
            } else {
                failCount++
            }
        }
        (context as? android.app.Activity)?.runOnUiThread {
            Toast.makeText(
                context,
                "成功重启 $successCount 个，失败 $failCount 个",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}

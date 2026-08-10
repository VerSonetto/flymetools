package com.karen.flymetool.ui.screen

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.karen.flymetool.R
import com.karen.flymetool.data.AppData
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.ScopedApp
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.ui.component.DonatePanel
import com.karen.flymetool.util.RootUtils
import com.karen.flymetool.util.UpdateManager
import com.karen.flymetool.util.UpdateInfo
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import java.io.File
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.state.ToggleableState

/**
 * 长内容弹窗内容区的高度上限：窗口高度的 1/2，超出部分滚动，
 * 保证底部操作按钮始终在可视区域内（高 DPI/大字号下内容更多也不顶满屏）。
 */
private val DialogContentMaxHeight: Dp
    @Composable get() = LocalConfiguration.current.screenHeightDp.dp * 0.5f

/** 为底部悬浮 Tab 栏预留空间，使 FAB 始终位于其上方。 */
private val HomeFabBottomPadding = 112.dp

@Composable
fun HomeScreen(
    onAppClick: (ScopedApp) -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apps = remember(context) { AppData.getScopedApps(context) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showIntroDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
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
        when {
            !PrefsHelper.isIntroShown(context) -> showIntroDialog = true
            !PrefsHelper.isDonateDialogShown(context) -> showDonateDialog = true
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

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            HomeHeader(
                updateInfo = updateInfo,
                checkingUpdate = checkingUpdate,
                onCheckUpdate = { showUpdateDialog = true }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = apps,
                    key = { app -> app.packageName },
                    contentType = { "scoped_app" }
                ) { app ->
                    AppListItem(
                        app = app,
                        onClick = { onAppClick(app) }
                    )
                }
            }
        }

        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = HomeFabBottomPadding),
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
                if (!PrefsHelper.isDonateDialogShown(context)) {
                    showDonateDialog = true
                }
            }
        )
    }

    if (showDonateDialog) {
        DonateDialog(
            onDismiss = {
                PrefsHelper.markDonateDialogShown(context)
                showDonateDialog = false
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
        outsideMargin = DpSize(12.dp, 8.dp),
        defaultWindowInsetsPadding = false,
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
                    .heightIn(max = DialogContentMaxHeight)
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

        Spacer(modifier = Modifier.height(12.dp))

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
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        Column {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = DialogContentMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "本模块最初基于 Flyme 10 开发，因此大部分功能理论上会更适配 Flyme 10。但也不能完全保证，因为随着系统更新以及被 Hook 应用本身的变化，即使同属 Flyme 10，不同版本之间也可能存在不兼容的情况。\n\n后来我升级到了 Flyme 12，所以后续新增的功能基本都是在 Flyme 12 上进行测试的。\n\n这个模块一开始只是做给自己玩的，后来顺手公开出来了。由于目前测试者基本只有我一个人，不可能覆盖所有机型、版本和使用场景，因此出现问题其实是正常情况，后续修复也可能比较随缘。\n\n另外，本模块几乎完全由 AI 辅助开发；如果介意，请勿使用，也请勿因此攻击或指责。",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                text = "我知道了",
                onClick = { dismiss?.invoke() },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DonateDialog(
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.donate_title),
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        DonatePanel(imageSize = 130)

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            text = stringResource(R.string.donate_dismiss),
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
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = DialogContentMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
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
private fun HomeHeader(
    updateInfo: UpdateInfo?,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)
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

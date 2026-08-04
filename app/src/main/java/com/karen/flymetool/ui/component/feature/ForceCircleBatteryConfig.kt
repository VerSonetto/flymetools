package com.karen.flymetool.ui.component.feature

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider
import com.karen.flymetool.ui.component.FeatureSwitch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DEFAULT_SIZE_SCALE = 100
private const val DEFAULT_OFFSET = 0
private const val EXTRA_CUSTOM_LAYOUT = "custom_layout"

@Composable
fun ForceCircleBatteryConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preview = remember { CircleBatteryPreviewOverlay(context) }

    var customEnabled by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, EXTRA_CUSTOM_LAYOUT, 0
            ) == 1
        )
    }
    /** 仅 UI 会话态，默认关；不写入 prefs，离开页面即关闭预览 */
    var previewEnabled by remember { mutableStateOf(false) }
    var sizeScale by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(
                context, packageName, featureKey, DEFAULT_SIZE_SCALE
            ).toFloat()
        )
    }
    var offsetXDp by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, "offset_x_dp", DEFAULT_OFFSET
            ).toFloat()
        )
    }
    var offsetYDp by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, "offset_y_dp", DEFAULT_OFFSET
            ).toFloat()
        )
    }
    var hasOverlayPermission by remember {
        mutableStateOf(preview.canDrawOverlays())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = preview.canDrawOverlays()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preview.dismiss()
        }
    }

    fun applyPreview() {
        if (!customEnabled || !previewEnabled || !hasOverlayPermission) {
            preview.dismiss()
            return
        }
        preview.show(sizeScale.toInt(), offsetXDp.toInt(), offsetYDp.toInt())
    }

    LaunchedEffect(
        customEnabled,
        previewEnabled,
        hasOverlayPermission,
        sizeScale,
        offsetXDp,
        offsetYDp
    ) {
        applyPreview()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        FeatureSwitch(
            title = "自定义大小与位置",
            description = "默认关闭，使用系统圆环尺寸并仅对齐前摄黑圈坐标。开启后可微调；修改后需重启系统界面。开启「环形电量替换电池图标」时无效。",
            checked = customEnabled,
            onCheckedChange = { enabled ->
                customEnabled = enabled
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, EXTRA_CUSTOM_LAYOUT, if (enabled) 1 else 0
                )
                if (!enabled) {
                    previewEnabled = false
                    preview.dismiss()
                }
            }
        )

        if (customEnabled) {
            FeatureSwitch(
                title = "显示悬浮预览",
                description = if (hasOverlayPermission) {
                    "在前摄孔位附近显示实时预览圆环，方便对照调整；用完请关闭。"
                } else {
                    "需要悬浮窗权限。开启时将跳转授权页，返回后即可预览。"
                },
                checked = previewEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        previewEnabled = false
                        preview.dismiss()
                        return@FeatureSwitch
                    }
                    hasOverlayPermission = preview.canDrawOverlays()
                    if (!hasOverlayPermission) {
                        Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) {
                        }
                        // 未授权时保持关闭，授权返回后再手动打开
                        previewEnabled = false
                        return@FeatureSwitch
                    }
                    previewEnabled = true
                }
            )

            if (previewEnabled && !hasOverlayPermission) {
                Text(
                    text = "未授予悬浮窗权限，预览无法显示。授予后返回本页并重新打开预览开关。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            AppSlider(
                label = "圆环大小",
                value = sizeScale,
                onValueChange = { sizeScale = it },
                onValueChangeFinished = {
                    PrefsHelper.setFeatureValue(
                        context, packageName, featureKey, sizeScale.toInt()
                    )
                },
                valueRange = 50f..150f,
                valueDisplay = "${sizeScale.toInt()}%"
            )
            AppSlider(
                label = "水平偏移",
                value = offsetXDp,
                onValueChange = { offsetXDp = it },
                onValueChangeFinished = {
                    PrefsHelper.setFeatureExtraValue(
                        context, packageName, featureKey, "offset_x_dp", offsetXDp.toInt()
                    )
                },
                valueRange = -24f..24f,
                valueDisplay = "${offsetXDp.toInt()} dp"
            )
            AppSlider(
                label = "垂直偏移",
                value = offsetYDp,
                onValueChange = { offsetYDp = it },
                onValueChangeFinished = {
                    PrefsHelper.setFeatureExtraValue(
                        context, packageName, featureKey, "offset_y_dp", offsetYDp.toInt()
                    )
                },
                valueRange = -24f..24f,
                valueDisplay = "${offsetYDp.toInt()} dp"
            )
        }
    }
}

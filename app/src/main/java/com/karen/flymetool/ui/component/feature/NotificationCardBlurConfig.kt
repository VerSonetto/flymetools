package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider
import com.karen.flymetool.ui.component.FeatureSwitch
import com.karen.flymetool.util.NotificationCardBlurMath

/** 与 Hook 一致：50 ≈ 系统原强度，100 ≈ 约两倍，上限见 MAX_INTENSITY */
private const val DEFAULT_INTENSITY = NotificationCardBlurMath.DEFAULT_INTENSITY
private const val MAX_INTENSITY = NotificationCardBlurMath.MAX_INTENSITY

/** 不透明度默认；同滑块值经幂映射后比线性更不透明 */
private const val DEFAULT_OPACITY = NotificationCardBlurMath.DEFAULT_OPACITY
private const val OPACITY_SUFFIX = "opacity"

/** 曲线联动子开关：package:feature:beautify → 0/1（key 保持兼容） */
private const val BEAUTIFY_SUFFIX = "beautify"
private const val DEFAULT_BEAUTIFY = 0

@Composable
fun NotificationCardBlurConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var intensity by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(
                context, packageName, featureKey, DEFAULT_INTENSITY
            ).coerceIn(0, MAX_INTENSITY).toFloat()
        )
    }
    var opacity by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, OPACITY_SUFFIX, DEFAULT_OPACITY
            ).toFloat()
        )
    }
    var beautify by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, BEAUTIFY_SUFFIX, DEFAULT_BEAUTIFY
            ) == 1
        )
    }

    Column {
        NotificationCardBlurPreview(
            intensity = intensity.toInt().coerceIn(0, MAX_INTENSITY),
            opacity = opacity.toInt().coerceIn(0, 100),
            beautify = beautify
        )
        Spacer(modifier = Modifier.height(4.dp))
        AppSlider(
            label = "模糊强度",
            value = intensity.coerceIn(0f, MAX_INTENSITY.toFloat()),
            onValueChange = { intensity = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureValue(
                    context, packageName, featureKey, intensity.toInt().coerceIn(0, MAX_INTENSITY)
                )
            },
            valueRange = 0f..MAX_INTENSITY.toFloat(),
            valueDisplay = "${intensity.toInt().coerceIn(0, MAX_INTENSITY)}"
        )
        AppSlider(
            label = "不透明度",
            value = opacity,
            onValueChange = { opacity = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, OPACITY_SUFFIX, opacity.toInt()
                )
            },
            valueRange = 0f..100f,
            valueDisplay = "${opacity.toInt()}%"
        )
        FeatureSwitch(
            title = "曲线联动",
            description = "作用于通知卡片与媒体播放器卡片。" +
                "开启后强度按曲线映射，不透明度随强度联动并带日夜轻微偏色；" +
                "关闭则半径与不透明度都按滑块取值。",
            checked = beautify,
            onCheckedChange = { enabled ->
                beautify = enabled
                PrefsHelper.setFeatureExtraValue(
                    context,
                    packageName,
                    featureKey,
                    BEAUTIFY_SUFFIX,
                    if (enabled) 1 else 0
                )
            }
        )
    }
}

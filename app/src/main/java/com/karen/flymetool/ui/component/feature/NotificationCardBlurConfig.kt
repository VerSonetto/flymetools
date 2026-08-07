package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

/** 与 Hook 一致：50 ≈ 系统原强度，100 ≈ 约两倍 */
private const val DEFAULT_INTENSITY = 50
private const val DEFAULT_OPACITY = 70
private const val OPACITY_SUFFIX = "opacity"

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
            ).toFloat()
        )
    }
    var opacity by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, OPACITY_SUFFIX, DEFAULT_OPACITY
            ).toFloat()
        )
    }

    Column {
        AppSlider(
            label = "模糊强度",
            value = intensity,
            onValueChange = { intensity = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureValue(
                    context, packageName, featureKey, intensity.toInt()
                )
            },
            valueRange = 0f..100f,
            valueDisplay = "${intensity.toInt()}%"
        )
        AppSlider(
            label = "遮罩不透明度",
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
    }
}

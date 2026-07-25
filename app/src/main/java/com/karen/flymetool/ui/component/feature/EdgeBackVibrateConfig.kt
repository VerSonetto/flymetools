package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

@Composable
fun EdgeBackVibrateConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var intensity by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 50).toFloat())
    }

    AppSlider(
        label = "震动强度",
        value = intensity,
        onValueChange = { intensity = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, intensity.toInt())
        },
        valueRange = 0f..100f,
        valueDisplay = if (intensity.toInt() == 0) "关闭" else "${intensity.toInt()}"
    )
}

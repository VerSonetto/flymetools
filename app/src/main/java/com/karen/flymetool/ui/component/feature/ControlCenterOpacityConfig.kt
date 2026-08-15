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
fun ControlCenterOpacityConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var opacityValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 100).toFloat())
    }

    AppSlider(
        label = "背景不透明度",
        value = opacityValue,
        onValueChange = { opacityValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, opacityValue.toInt())
        },
        valueRange = 0f..100f,
        valueDisplay = "${opacityValue.toInt()}%"
    )
}

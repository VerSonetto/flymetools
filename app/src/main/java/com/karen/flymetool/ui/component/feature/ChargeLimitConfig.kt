package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

@Composable
fun ChargeLimitConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var sliderValue by remember {
        mutableFloatStateOf(
            PrefsHelper.getFeatureValue(context, packageName, featureKey, 100).toFloat()
        )
    }

    AppSlider(
        label = "充电上限",
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, sliderValue.toInt())
        },
        valueRange = 50f..100f,
        valueDisplay = "${sliderValue.toInt()}%"
    )
}

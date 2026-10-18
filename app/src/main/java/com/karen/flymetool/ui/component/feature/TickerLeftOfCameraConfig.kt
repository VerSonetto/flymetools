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
fun TickerLeftOfCameraConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var percent by remember {
        mutableFloatStateOf(
            PrefsHelper.getFeatureValue(context, packageName, featureKey, 100).toFloat()
        )
    }

    AppSlider(
        label = "滚动速度",
        value = percent,
        onValueChange = { percent = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, percent.toInt())
        },
        valueRange = 50f..500f,
        valueDisplay = String.format("%.1fx", percent / 100f)
    )
}

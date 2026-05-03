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
fun MemoryDisplayConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var intervalValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 2000).toFloat())
    }

    AppSlider(
        label = "刷新间隔",
        value = intervalValue,
        onValueChange = { intervalValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, intervalValue.toInt())
        },
        valueRange = 500f..5000f,
        valueDisplay = "${(intervalValue / 1000).toInt()} 秒"
    )
}

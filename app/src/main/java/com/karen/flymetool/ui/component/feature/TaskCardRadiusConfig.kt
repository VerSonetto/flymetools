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
fun TaskCardRadiusConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var radiusValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 24).toFloat())
    }

    AppSlider(
        label = "圆角大小",
        value = radiusValue,
        onValueChange = { radiusValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, radiusValue.toInt())
        },
        valueRange = 0f..50f,
        valueDisplay = "${radiusValue.toInt()} px"
    )
}

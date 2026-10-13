package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

/** 与 Hook 的 SLIDE_SENSITIVITY_* 一致 */
private const val SLIDE_SENSITIVITY_SUFFIX = "sensitivity"
private const val DEFAULT_SLIDE_SENSITIVITY = 100

@Composable
fun RecentsSlideSensitivityConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var sensitivity by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, SLIDE_SENSITIVITY_SUFFIX, DEFAULT_SLIDE_SENSITIVITY
            ).toFloat()
        )
    }

    AppSlider(
        label = "左右滑动灵敏度",
        value = sensitivity,
        onValueChange = { sensitivity = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureExtraValue(
                context, packageName, featureKey, SLIDE_SENSITIVITY_SUFFIX, sensitivity.toInt()
            )
        },
        valueRange = 50f..300f,
        valueDisplay = "${sensitivity.toInt()}%"
    )
}

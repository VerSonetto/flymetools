package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

/** 与 Hook 默认 textSize 一致 */
private const val DEFAULT_TEXT_SIZE_SP = 12

@Composable
fun AODLyricConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var textSizeValue by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(
                context, packageName, featureKey, DEFAULT_TEXT_SIZE_SP
            ).toFloat()
        )
    }

    AppSlider(
        label = "文字大小",
        value = textSizeValue,
        onValueChange = { textSizeValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(
                context, packageName, featureKey, textSizeValue.toInt()
            )
        },
        valueRange = 8f..28f,
        valueDisplay = "${textSizeValue.toInt()} sp"
    )
}

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

private const val DEFAULT_MAX_LINES = 3

/** 与 Hook 默认 textSize 一致；存 extra，避免覆盖最大行数的 :value */
private const val TEXT_SIZE_SUFFIX = "text_size"
private const val DEFAULT_TEXT_SIZE_SP = 11

@Composable
fun AODNotificationConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var maxLinesValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, DEFAULT_MAX_LINES).toFloat())
    }
    var textSizeValue by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, TEXT_SIZE_SUFFIX, DEFAULT_TEXT_SIZE_SP
            ).toFloat()
        )
    }

    Column {
        AppSlider(
            label = "最大显示行数",
            value = maxLinesValue,
            onValueChange = { maxLinesValue = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureValue(context, packageName, featureKey, maxLinesValue.toInt())
            },
            valueRange = 1f..10f,
            valueDisplay = "${maxLinesValue.toInt()} 行"
        )
        AppSlider(
            label = "文字大小",
            value = textSizeValue,
            onValueChange = { textSizeValue = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, TEXT_SIZE_SUFFIX, textSizeValue.toInt()
                )
            },
            valueRange = 8f..28f,
            valueDisplay = "${textSizeValue.toInt()} sp"
        )
    }
}

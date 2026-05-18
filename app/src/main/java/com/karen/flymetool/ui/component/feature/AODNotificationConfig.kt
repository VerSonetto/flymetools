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
fun AODNotificationConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var maxLinesValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 3).toFloat())
    }

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
}

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
fun FolderOpenBlurConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var strengthValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 80).toFloat())
    }

    AppSlider(
        label = "模糊强度",
        value = strengthValue,
        onValueChange = { strengthValue = it },
        onValueChangeFinished = {
            PrefsHelper.setFeatureValue(context, packageName, featureKey, strengthValue.toInt())
        },
        valueRange = 0f..100f,
        valueDisplay = "${strengthValue.toInt()}"
    )
}

package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider

private const val DEFAULT_HOLD_MS = 1000
private const val DEFAULT_THRESHOLD_DP = 32

@Composable
fun EdgeBackHoldPreviousAppConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var holdMs by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(context, packageName, featureKey, DEFAULT_HOLD_MS).toFloat()
        )
    }
    var thresholdDp by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, "threshold_dp", DEFAULT_THRESHOLD_DP
            ).toFloat()
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AppSlider(
            label = "保持时长",
            value = holdMs,
            onValueChange = { holdMs = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureValue(
                    context, packageName, featureKey, holdMs.toInt()
                )
            },
            valueRange = 300f..2000f,
            valueDisplay = "${holdMs.toInt()} ms"
        )
        AppSlider(
            label = "触发幅度",
            value = thresholdDp,
            onValueChange = { thresholdDp = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, "threshold_dp", thresholdDp.toInt()
                )
            },
            valueRange = 16f..80f,
            valueDisplay = "${thresholdDp.toInt()} dp"
        )
    }
}

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
import kotlin.math.roundToInt

private const val DEFAULT_HOLD_MS = 1000
private const val DEFAULT_THRESHOLD_DP = 36
private const val DEFAULT_ICON_SIZE_DP = 20

/** 滑块区间（收窄）；实际触发距离见 [effectiveThresholdDp] */
private const val THRESHOLD_UI_MIN = 24
private const val THRESHOLD_UI_MAX = 56
private const val THRESHOLD_STEP = 4
private const val ICON_SIZE_MIN = 12
private const val ICON_SIZE_MAX = 36

/**
 * 滑块档位 → 实际触发 dp：24..56 映射到 22..70，拉开手感差。
 */
private fun effectiveThresholdDp(uiDp: Int): Int {
    val t = ((uiDp - THRESHOLD_UI_MIN).toFloat() /
        (THRESHOLD_UI_MAX - THRESHOLD_UI_MIN)).coerceIn(0f, 1f)
    return (22f + t * (70f - 22f)).roundToInt()
}

private fun snapThreshold(raw: Float): Float {
    val stepped = (raw / THRESHOLD_STEP).roundToInt() * THRESHOLD_STEP
    return stepped.coerceIn(THRESHOLD_UI_MIN, THRESHOLD_UI_MAX).toFloat()
}

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
            snapThreshold(
                PrefsHelper.getFeatureExtraValue(
                    context, packageName, featureKey, "threshold_dp", DEFAULT_THRESHOLD_DP
                ).toFloat()
            )
        )
    }
    var iconSizeDp by remember {
        mutableStateOf(
            PrefsHelper.getFeatureExtraValue(
                context, packageName, featureKey, "icon_size_dp", DEFAULT_ICON_SIZE_DP
            ).coerceIn(ICON_SIZE_MIN, ICON_SIZE_MAX).toFloat()
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
            onValueChange = { thresholdDp = snapThreshold(it) },
            onValueChangeFinished = {
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, "threshold_dp", thresholdDp.toInt()
                )
            },
            valueRange = THRESHOLD_UI_MIN.toFloat()..THRESHOLD_UI_MAX.toFloat(),
            valueDisplay = "${effectiveThresholdDp(thresholdDp.toInt())} dp"
        )
        AppSlider(
            label = "图标大小",
            value = iconSizeDp,
            onValueChange = { iconSizeDp = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureExtraValue(
                    context, packageName, featureKey, "icon_size_dp", iconSizeDp.toInt()
                )
            },
            valueRange = ICON_SIZE_MIN.toFloat()..ICON_SIZE_MAX.toFloat(),
            valueDisplay = "${iconSizeDp.toInt()} dp"
        )
    }
}

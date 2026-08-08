package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionOption
import com.karen.flymetool.ui.component.SingleSelectionRow

private const val ACTION_FLASHLIGHT = "flashlight"
private const val ACTION_SCREENSHOT = "screenshot"
private const val ACTION_SLEEP = "sleep"
private const val ACTION_MUTE = "mute"
private const val DEFAULT_ACTION = ACTION_FLASHLIGHT

@Composable
fun MBackDoubleClickConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var action by remember {
        mutableStateOf(
            PrefsHelper.getFeatureString(context, packageName, featureKey, DEFAULT_ACTION)
                .ifBlank { DEFAULT_ACTION }
        )
    }

    val options = listOf(
        SelectionOption(value = ACTION_FLASHLIGHT, label = "手电筒"),
        SelectionOption(value = ACTION_SCREENSHOT, label = "截图"),
        SelectionOption(value = ACTION_SLEEP, label = "息屏"),
        SelectionOption(value = ACTION_MUTE, label = "静音"),
    )

    SelectionGroup(title = "双击动作") {
        SingleSelectionRow(
            options = options,
            selectedValue = action,
            onSelected = { value ->
                action = value
                PrefsHelper.setFeatureString(context, packageName, featureKey, value)
            }
        )
    }
}

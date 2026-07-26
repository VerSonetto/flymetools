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

@Composable
fun CircleBatteryStatusBarIconConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var textMode by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 0))
    }

    SelectionGroup(
        title = "电量值显示",
        description = "控制状态栏、锁屏状态栏和控制中心状态栏的环形电量文字显示方式"
    ) {
        SingleSelectionRow(
            options = listOf(
                SelectionOption(0, "不显示"),
                SelectionOption(1, "环内"),
                SelectionOption(2, "环侧")
            ),
            selectedValue = textMode,
            onSelected = { mode ->
                textMode = mode
                PrefsHelper.setFeatureValue(context, packageName, featureKey, mode)
            }
        )
    }
}

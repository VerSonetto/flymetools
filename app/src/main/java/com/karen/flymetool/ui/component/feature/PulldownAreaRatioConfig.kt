package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionOption
import com.karen.flymetool.ui.component.SingleSelectionRow

@Composable
fun PulldownAreaRatioConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var controlCenterRatio by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 50))
    }

    val ratios = listOf(
        SelectionOption(25, "25%"),
        SelectionOption(50, "50%"),
        SelectionOption(75, "75%")
    )

    val notificationRatios = listOf(
        SelectionOption(75, "25%"),
        SelectionOption(50, "50%"),
        SelectionOption(25, "75%")
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SelectionGroup(title = "控制中心区域比例") {
            SingleSelectionRow(
                options = ratios,
                selectedValue = controlCenterRatio,
                onSelected = { ratio ->
                    controlCenterRatio = ratio
                    PrefsHelper.setFeatureValue(context, packageName, featureKey, ratio)
                }
            )
        }

        SelectionGroup(title = "通知面板区域比例") {
            SingleSelectionRow(
                options = notificationRatios,
                selectedValue = 100 - controlCenterRatio,
                onSelected = { ratio ->
                    val newControlCenterRatio = 100 - ratio
                    controlCenterRatio = newControlCenterRatio
                    PrefsHelper.setFeatureValue(context, packageName, featureKey, newControlCenterRatio)
                }
            )
        }
    }
}

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
fun NotificationIconLimitConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var maxIcons by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 4))
    }

    val iconCounts = listOf(
        SelectionOption(1, "1"),
        SelectionOption(2, "2"),
        SelectionOption(3, "3"),
        SelectionOption(4, "4")
    )

    SelectionGroup(title = "最大显示数量") {
        SingleSelectionRow(
            options = iconCounts,
            selectedValue = maxIcons,
            onSelected = { count ->
                maxIcons = count
                PrefsHelper.setFeatureValue(context, packageName, featureKey, count)
            }
        )
    }
}

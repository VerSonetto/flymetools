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
fun WeekdayFormatConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var selectedFormat by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 0))
    }

    val formats = listOf(
        SelectionOption(0, "周一"),
        SelectionOption(1, "Mon"),
        SelectionOption(2, "Monday")
    )

    SelectionGroup(title = "显示格式") {
        SingleSelectionRow(
            options = formats,
            selectedValue = selectedFormat,
            onSelected = { format ->
                selectedFormat = format
                PrefsHelper.setFeatureValue(context, packageName, featureKey, format)
            }
        )
    }
}

package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

    var selectedPosition by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, "${featureKey}_position", 0))
    }

    val formats = listOf(
        SelectionOption(0, "周一"),
        SelectionOption(1, "Mon"),
        SelectionOption(2, "Monday")
    )

    val positions = listOf(
        SelectionOption(0, "左侧"),
        SelectionOption(1, "右侧")
    )

    Column {
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

        Spacer(modifier = Modifier.height(12.dp))

        SelectionGroup(title = "显示位置") {
            SingleSelectionRow(
                options = positions,
                selectedValue = selectedPosition,
                onSelected = { position ->
                    selectedPosition = position
                    PrefsHelper.setFeatureValue(context, packageName, "${featureKey}_position", position)
                }
            )
        }
    }
}

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
fun SlideGestureMultiArcConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var arcCount by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 1).coerceIn(1, 4))
    }

    val options = listOf(
        SelectionOption(1, "1 条"),
        SelectionOption(2, "2 条"),
        SelectionOption(3, "3 条"),
        SelectionOption(4, "4 条")
    )

    SelectionGroup(
        title = "圆弧条数",
        description = "第 1 条为系统默认圆弧，保留 6 个快捷项和更多应用入口；新增圆弧向内收拢，越往内容量越少。"
    ) {
        SingleSelectionRow(
            options = options,
            selectedValue = arcCount,
            onSelected = { count ->
                arcCount = count
                PrefsHelper.setFeatureValue(context, packageName, featureKey, count)
            }
        )
    }
}

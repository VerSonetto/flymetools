package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.hook.feature.systemui.HideStatusBarIconHook
import com.karen.flymetool.ui.component.MultiSelectionFlow
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionOption

@Composable
fun HideStatusBarIconConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var hiddenSlots by remember {
        mutableStateOf(
            PrefsHelper.getFeatureStringSet(context, packageName, featureKey, emptySet())
        )
    }

    val options = remember { 
        HideStatusBarIconHook.iconOptions.map { (slot, label) ->
            SelectionOption(slot, label)
        }
    }

    SelectionGroup(title = "选择要隐藏的图标") {
        MultiSelectionFlow(
            options = options,
            selectedValues = hiddenSlots,
            onToggle = { slot, isSelected ->
                val newSet = hiddenSlots.toMutableSet()
                if (isSelected) newSet.remove(slot) else newSet.add(slot)
                hiddenSlots = newSet
                PrefsHelper.setFeatureStringSet(context, packageName, featureKey, newSet)
            }
        )
    }
}

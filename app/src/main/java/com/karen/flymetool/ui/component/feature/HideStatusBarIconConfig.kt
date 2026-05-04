package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.hook.feature.systemui.HideStatusBarIconHook

@OptIn(ExperimentalLayoutApi::class)
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

    val options = remember { HideStatusBarIconHook.iconOptions }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "选择要隐藏的图标",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (slot, label) ->
                val isSelected = slot in hiddenSlots
                val shape = RoundedCornerShape(12.dp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                            shape = shape
                        )
                        .clickable {
                            val newSet = hiddenSlots.toMutableSet()
                            if (isSelected) newSet.remove(slot) else newSet.add(slot)
                            hiddenSlots = newSet
                            PrefsHelper.setFeatureStringSet(context, packageName, featureKey, newSet)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppInputDialog
import com.karen.flymetool.ui.component.AppSlider

@Composable
fun ConnectionRateConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var thresholdValue by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 10).toFloat())
    }
    var showDialog by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSlider(
                label = "隐藏阈值",
                value = thresholdValue,
                onValueChange = { thresholdValue = it },
                onValueChangeFinished = {
                    PrefsHelper.setFeatureValue(context, packageName, featureKey, thresholdValue.toInt())
                },
                valueRange = 1f..1024f,
                valueDisplay = "${thresholdValue.toInt()} KB/s"
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { showDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "精确设置",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showDialog) {
        var inputText by remember { mutableStateOf(thresholdValue.toInt().toString()) }
        AppInputDialog(
            title = "设置隐藏阈值",
            label = "KB/s",
            value = inputText,
            onValueChange = { inputText = it },
            onConfirm = {
                val value = inputText.toIntOrNull()?.coerceAtLeast(1) ?: 10
                thresholdValue = value.toFloat()
                PrefsHelper.setFeatureValue(context, packageName, featureKey, value)
                showDialog = false
            },
            onDismiss = { showDialog = false },
            filter = { it.isDigit() }
        )
    }
}

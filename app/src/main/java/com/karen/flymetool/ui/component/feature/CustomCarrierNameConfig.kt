package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.FeatureCard

@Composable
fun CustomCarrierNameConfig(
    packageName: String,
    featureKey: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name1 by remember {
        mutableStateOf(PrefsHelper.getFeatureString(context, packageName, featureKey, ""))
    }
    var name2 by remember {
        mutableStateOf(PrefsHelper.getFeatureString(context, packageName, "${featureKey}_sim2", ""))
    }

    FeatureCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "卡 1 名称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = name1,
                onValueChange = {
                    name1 = it
                    PrefsHelper.setFeatureString(context, packageName, featureKey, it)
                },
                placeholder = { Text("卡 1 运营商名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
            Text(
                text = "卡 2 名称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            OutlinedTextField(
                value = name2,
                onValueChange = {
                    name2 = it
                    PrefsHelper.setFeatureString(context, packageName, "${featureKey}_sim2", it)
                },
                placeholder = { Text("卡 2 运营商名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
        }
    }
}

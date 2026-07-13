package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun ForceLiveNotificationConfig(
    packageName: String,
    featureKey: String,
    onNavigate: (String) -> Unit,
) {
    Button(
        onClick = { onNavigate("app_select/$packageName/$featureKey") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
        Text("选择应用")
    }
}

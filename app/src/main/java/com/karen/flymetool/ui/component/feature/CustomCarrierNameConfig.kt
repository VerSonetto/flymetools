package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.FeatureCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                value = name1,
                onValueChange = {
                    name1 = it
                    PrefsHelper.setFeatureString(context, packageName, featureKey, it)
                },
                label = "卡 1 运营商名称",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "卡 2 名称",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                value = name2,
                onValueChange = {
                    name2 = it
                    PrefsHelper.setFeatureString(context, packageName, "${featureKey}_sim2", it)
                },
                label = "卡 2 运营商名称",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.FeatureCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PAGE_LIMIT_DEFAULT = 9
private const val PAGE_LIMIT_MIN = 1
private const val PAGE_LIMIT_MAX = 30

@Composable
fun HomeScreenLimitConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var input by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(context, packageName, featureKey, PAGE_LIMIT_DEFAULT).toString()
        )
    }

    FeatureCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "页数上限",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                value = input,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    input = digits
                    val value = digits.toIntOrNull()
                    if (value != null && value in PAGE_LIMIT_MIN..PAGE_LIMIT_MAX) {
                        PrefsHelper.setFeatureValue(context, packageName, featureKey, value)
                    }
                },
                label = "$PAGE_LIMIT_MIN-$PAGE_LIMIT_MAX",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "范围 $PAGE_LIMIT_MIN-$PAGE_LIMIT_MAX，默认 $PAGE_LIMIT_DEFAULT 页（不含负一屏与隐私空间）；" +
                    "修改后需重启桌面生效，调小不会自动删除已有页面。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
    }
}

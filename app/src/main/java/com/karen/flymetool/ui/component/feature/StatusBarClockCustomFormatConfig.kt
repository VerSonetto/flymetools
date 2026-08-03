package com.karen.flymetool.ui.component.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.FeatureCard
import com.karen.flymetool.ui.component.SelectionItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FORMAT_PRESETS = listOf(
    "HH:mm",
    "HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "MM月dd日 EEEE HH:mm",
    "HH:mm EEEE"
)

private data class FormatToken(val token: String, val meaning: String, val example: String)

private val FORMAT_TOKENS = listOf(
    FormatToken("yyyy", "年份（四位数）", "2026"),
    FormatToken("yy", "年份（两位数）", "26"),
    FormatToken("MM", "月份（补零）", "08"),
    FormatToken("M", "月份", "8"),
    FormatToken("MMM", "月份缩写", "8月"),
    FormatToken("MMMM", "月份全称", "八月"),
    FormatToken("dd", "日期（补零）", "03"),
    FormatToken("d", "日期", "3"),
    FormatToken("E", "星期（缩写）", "周一"),
    FormatToken("EEEE", "星期（全称）", "星期一"),
    FormatToken("u", "ISO 星期（1-7）", "1-7"),
    FormatToken("a", "上午/下午", "上午"),
    FormatToken("HH", "24 小时（补零）", "14"),
    FormatToken("H", "24 小时", "14"),
    FormatToken("kk", "24 小时（1-24）", "14"),
    FormatToken("hh", "12 小时（补零）", "02"),
    FormatToken("h", "12 小时", "2"),
    FormatToken("KK", "12 小时（0-11）", "02"),
    FormatToken("mm", "分钟（补零）", "05"),
    FormatToken("m", "分钟", "5"),
    FormatToken("ss", "秒（补零）", "09"),
    FormatToken("s", "秒", "9"),
    FormatToken("SSS", "毫秒", "123"),
    FormatToken("z", "时区名称", "CST"),
    FormatToken("Z", "时区偏移", "+0800"),
    FormatToken("XXX", "时区偏移", "+08:00"),
    FormatToken("''", "单引号转义", "显示 '"),
    FormatToken("其他文字", "原样显示", "年月日、空格、冒号等")
)

private data class FormatPreview(val valid: Boolean, val text: String?)

private fun renderPreview(pattern: String): FormatPreview {
    if (pattern.isBlank()) {
        return FormatPreview(valid = true, text = "（未启用）")
    }
    return try {
        FormatPreview(valid = true, text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date()))
    } catch (e: Throwable) {
        FormatPreview(valid = false, text = null)
    }
}

@Composable
fun StatusBarClockCustomFormatConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var format by remember {
        mutableStateOf(PrefsHelper.getFeatureString(context, packageName, featureKey, ""))
    }
    var showGuide by remember { mutableStateOf(false) }
    val preview = remember(format) { renderPreview(format) }

    FeatureCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "时间格式",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                value = format,
                onValueChange = { newValue ->
                    format = newValue
                    PrefsHelper.setFeatureString(context, packageName, featureKey, newValue)
                },
                label = "例如 HH:mm:ss",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (preview.valid) {
                Text(
                    text = "实时预览：${preview.text}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "格式无效，将保持系统默认时间显示",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "常用格式",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            FormatPresetChips(
                presets = FORMAT_PRESETS,
                selected = format,
                onSelect = { preset ->
                    format = preset
                    PrefsHelper.setFeatureString(context, packageName, featureKey, preset)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGuide = !showGuide }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "格式写法说明",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (showGuide) 180f else 0f)
                )
            }

            if (showGuide) {
                Spacer(modifier = Modifier.height(8.dp))
                FormatGuideTable()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示：格式中含 ss 等秒级写法时自动开启秒刷新；格式已含 a（上午/下午）时建议关闭「时间段修饰」，避免重复显示。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatPresetChips(
    presets: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            SelectionItem(
                label = preset,
                isSelected = preset == selected,
                onClick = { onSelect(preset) }
            )
        }
    }
}

@Composable
private fun FormatGuideTable() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FORMAT_TOKENS.forEach { token ->
            Text(
                text = "${token.token}：${token.meaning}（如 ${token.example}）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

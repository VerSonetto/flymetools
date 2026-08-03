package com.karen.flymetool.ui.component.feature

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppInputDialog
import com.karen.flymetool.ui.component.FeatureCard
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionOption
import com.karen.flymetool.ui.component.SingleSelectionRow
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 自定义时段：名称 + 起始小时（含）~ 结束小时（含）。 */
private data class PeriodSegmentUi(val name: String, val startHour: Int, val endHour: Int)

/** 完全自定义时段无配置时的默认值，与预设六时段一致。 */
private val DEFAULT_SEGMENTS = listOf(
    PeriodSegmentUi("凌晨", 0, 4),
    PeriodSegmentUi("上午", 5, 10),
    PeriodSegmentUi("中午", 11, 12),
    PeriodSegmentUi("下午", 13, 16),
    PeriodSegmentUi("傍晚", 17, 18),
    PeriodSegmentUi("晚上", 19, 23)
)

private fun loadSegments(context: Context, packageName: String, featureKey: String): List<PeriodSegmentUi> {
    val raw = PrefsHelper.getFeatureString(context, packageName, "${featureKey}_custom", "")
    if (raw.isBlank()) return DEFAULT_SEGMENTS
    return try {
        val array = JSONArray(raw)
        val segments = mutableListOf<PeriodSegmentUi>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val start = obj.optInt("start", 0).coerceIn(0, 23)
            val end = obj.optInt("end", 0).coerceIn(0, 23)
            segments.add(PeriodSegmentUi(name, minOf(start, end), maxOf(start, end)))
        }
        if (segments.isEmpty()) DEFAULT_SEGMENTS else segments
    } catch (e: Throwable) {
        DEFAULT_SEGMENTS
    }
}

private fun saveSegments(
    context: Context,
    packageName: String,
    featureKey: String,
    segments: List<PeriodSegmentUi>
) {
    val array = JSONArray()
    segments.forEach { segment ->
        array.put(
            JSONObject()
                .put("name", segment.name)
                .put("start", segment.startHour)
                .put("end", segment.endHour)
        )
    }
    PrefsHelper.setFeatureString(context, packageName, "${featureKey}_custom", array.toString())
}

@Composable
fun StatusBarClockPeriodConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current

    var selectedScheme by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, featureKey, 1))
    }

    var selectedPosition by remember {
        mutableStateOf(PrefsHelper.getFeatureValue(context, packageName, "${featureKey}_position", 0))
    }

    var segments by remember {
        mutableStateOf(loadSegments(context, packageName, featureKey))
    }

    val schemeOptions = listOf(
        SelectionOption(1, "六时段"),
        SelectionOption(2, "两时段"),
        SelectionOption(3, "自定义时段")
    )

    val positions = listOf(
        SelectionOption(0, "左侧"),
        SelectionOption(1, "右侧")
    )

    Column {
        SelectionGroup(
            title = "时间段方案",
            description = "六时段：凌晨/上午/中午/下午/傍晚/晚上；两时段：上午/下午；自定义时段可自由添加名称与时间段，按顺序匹配第一个命中的时段"
        ) {
            SingleSelectionRow(
                options = schemeOptions,
                selectedValue = selectedScheme,
                onSelected = { scheme ->
                    selectedScheme = scheme
                    PrefsHelper.setFeatureValue(context, packageName, featureKey, scheme)
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

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

        if (selectedScheme == 3) {
            Spacer(modifier = Modifier.height(4.dp))
            FeatureCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "自定义时段",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (segments.isEmpty()) {
                        Text(
                            text = "暂无时段，点击下方「添加时段」创建",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        segments.forEachIndexed { index, segment ->
                            PeriodSegmentEditor(
                                index = index,
                                segment = segment,
                                onNameChange = { newName ->
                                    segments = segments.mapIndexed { i, s ->
                                        if (i == index) s.copy(name = newName) else s
                                    }
                                    saveSegments(context, packageName, featureKey, segments)
                                },
                                onStartChange = { newStart ->
                                    val clamped = newStart.coerceIn(0, 23)
                                    val next = if (clamped > segment.endHour) {
                                        segment.copy(startHour = clamped, endHour = clamped)
                                    } else {
                                        segment.copy(startHour = clamped)
                                    }
                                    segments = segments.mapIndexed { i, s -> if (i == index) next else s }
                                    saveSegments(context, packageName, featureKey, segments)
                                },
                                onEndChange = { newEnd ->
                                    val clamped = newEnd.coerceIn(0, 23)
                                    val next = if (clamped < segment.startHour) {
                                        segment.copy(startHour = clamped, endHour = clamped)
                                    } else {
                                        segment.copy(endHour = clamped)
                                    }
                                    segments = segments.mapIndexed { i, s -> if (i == index) next else s }
                                    saveSegments(context, packageName, featureKey, segments)
                                },
                                onDelete = {
                                    segments = segments.filterIndexed { i, _ -> i != index }
                                    saveSegments(context, packageName, featureKey, segments)
                                }
                            )
                            if (index != segments.lastIndex) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "添加时段",
                            onClick = {
                                segments = segments + PeriodSegmentUi("", 0, 23)
                                saveSegments(context, packageName, featureKey, segments)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "提示：按从上到下的顺序匹配，第一个命中的时段生效；名称为空的时段不会生效；起止范围为 0-23 点。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodSegmentEditor(
    index: Int,
    segment: PeriodSegmentUi,
    onNameChange: (String) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    onDelete: () -> Unit
) {
    // 当前正在编辑的小时字段：null 不弹窗，否则为 "start"/"end"
    var editingHourKind by remember { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = "时段 ${index + 1}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = segment.name,
            onValueChange = onNameChange,
            label = "时段名称",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HourBadge(
                label = "起始",
                value = segment.startHour,
                onClick = { editingHourKind = "start" }
            )
            HourBadge(
                label = "结束",
                value = segment.endHour,
                onClick = { editingHourKind = "end" }
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                text = "删除",
                onClick = onDelete
            )
        }
    }

    editingHourKind?.let { kind ->
        var inputText by remember { mutableStateOf(
            if (kind == "start") segment.startHour.toString() else segment.endHour.toString()
        ) }
        AppInputDialog(
            title = if (kind == "start") "设置起始小时" else "设置结束小时",
            label = "0-23",
            value = inputText,
            onValueChange = { inputText = it },
            onConfirm = {
                val value = inputText.toIntOrNull()?.coerceIn(0, 23)
                    ?: if (kind == "start") segment.startHour else segment.endHour
                if (kind == "start") onStartChange(value) else onEndChange(value)
                editingHourKind = null
            },
            onDismiss = { editingHourKind = null },
            filter = { it.isDigit() }
        )
    }
}

@Composable
private fun HourBadge(
    label: String,
    value: Int,
    onClick: () -> Unit
) {
    Column {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "$value 点",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
    }
}

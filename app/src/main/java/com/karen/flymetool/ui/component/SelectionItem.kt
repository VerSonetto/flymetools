package com.karen.flymetool.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SelectionOption<T>(
    val value: T,
    val label: String,
    val subLabel: String? = null,
    val iconContent: @Composable (() -> Unit)? = null
)

@Composable
fun SelectionGroup(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(bottom = if (description != null) 4.dp else 8.dp)
        )

        description?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SingleSelectionRow(
    options: List<SelectionOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) where T : Any {
    // 流式标签：格子宽度随内容自适应，宽度不足自动换行，任何字号下文字都不会被截断
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            SelectionItem(
                label = option.label,
                isSelected = option.value == selectedValue,
                onClick = { onSelected(option.value) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> MultiSelectionFlow(
    options: List<SelectionOption<T>>,
    selectedValues: Set<T>,
    onToggle: (T, Boolean) -> Unit,
    modifier: Modifier = Modifier
) where T : Any {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option.value in selectedValues
            SelectionItem(
                label = option.label,
                isSelected = isSelected,
                onClick = { onToggle(option.value, isSelected) }
            )
        }
    }
}

@Composable
fun <T> SelectionList(
    options: List<SelectionOption<T>>,
    selectedValue: T?,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null
) where T : Any {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (options.isEmpty() && emptyText != null) {
            Text(
                text = emptyText,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            options.forEach { option ->
                SelectionItemWithIcon(
                    label = option.label,
                    subLabel = option.subLabel,
                    isSelected = option.value == selectedValue,
                    iconContent = option.iconContent,
                    onClick = { onSelected(option.value) }
                )
            }
        }
    }
}

@Composable
fun SelectionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fixedWidth: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MiuixTheme.colorScheme.surfaceVariant,
        animationSpec = snap(),
        label = "backgroundColor"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MiuixTheme.colorScheme.primary
                else Color.Transparent,
                shape = shape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .run { if (fixedWidth) padding(vertical = 12.dp) else padding(horizontal = 16.dp, vertical = 8.dp) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = if (isSelected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onBackgroundVariant,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun SelectionItemWithIcon(
    label: String,
    subLabel: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconContent: @Composable (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MiuixTheme.colorScheme.surfaceVariant,
        animationSpec = snap(),
        label = "backgroundColor"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MiuixTheme.colorScheme.primary
                else Color.Transparent,
                shape = shape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconContent != null) {
            iconContent()
        } else {
            Spacer(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = if (isSelected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackgroundVariant,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            subLabel?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

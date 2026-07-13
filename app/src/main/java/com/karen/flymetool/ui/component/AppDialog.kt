package com.karen.flymetool.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AppInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    filter: ((Char) -> Boolean)? = null
) {
    var inputValue by remember { mutableStateOf(value) }

    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        TextField(
            value = inputValue,
            onValueChange = {
                val filtered = if (filter != null) it.filter(filter) else it
                inputValue = filtered
                onValueChange(filtered)
            },
            label = label,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = "取消",
                onClick = { dismiss?.invoke() },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "确定",
                onClick = {
                    onConfirm()
                    dismiss?.invoke()
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun AppConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    WindowDialog(
        show = true,
        title = title,
        summary = message,
        onDismissRequest = onDismiss
    ) {
        val dismiss = LocalDismissState.current

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = "取消",
                onClick = { dismiss?.invoke() },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "确定",
                onClick = {
                    onConfirm()
                    dismiss?.invoke()
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

package com.karen.flymetool.ui.screen

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import com.karen.flymetool.data.PrefsHelper
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AppFilter { ALL, USER, SYSTEM }

private fun AppFilter.label() = when (this) {
    AppFilter.ALL -> "全部"
    AppFilter.USER -> "用户"
    AppFilter.SYSTEM -> "系统"
}

@Composable
fun AppSelectScreen(
    packageName: String,
    featureKey: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedPackages by remember {
        mutableStateOf(PrefsHelper.getFeatureStringSet(context, packageName, featureKey, emptySet()))
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    val allApps = remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        allApps.value = withContext(Dispatchers.IO) { loadApps(context) }
        loaded = true
    }
    val filtered = remember(allApps.value, query, filter) {
        allApps.value.filter { app ->
            when (filter) {
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
                AppFilter.ALL -> true
            } && (query.isEmpty() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "选择应用",
                subtitle = "已选 ${selectedPackages.size} 个应用",
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // 搜索
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索应用",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.padding(horizontal = 12.dp)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* no-op */ })
            )

            // 筛选
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppFilter.values().forEach { f ->
                    val isSelected = filter == f
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceVariant
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { filter = f }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = f.label(),
                            style = MiuixTheme.textStyles.body2,
                            color = if (isSelected) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onBackgroundVariant
                        )
                    }
                }
            }

            // 列表
            if (!loaded) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    InfiniteProgressIndicator(
                        color = MiuixTheme.colorScheme.primary,
                        size = 32.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "加载中…",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPackages
                        AppItem(
                            app = app,
                            checked = checked,
                            onClick = {
                                val s = selectedPackages.toMutableSet()
                                if (checked) s.remove(app.packageName) else s.add(app.packageName)
                                selectedPackages = s
                                PrefsHelper.setFeatureStringSet(context, packageName, featureKey, s)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppItem(app: AppInfo, checked: Boolean, onClick: () -> Unit) {
    val icon = remember(app.packageName) { app.icon }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
            Text(app.packageName, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
        if (checked) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}

private data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: Bitmap?
)

private val pinyinFormat = HanyuPinyinOutputFormat().apply { setToneType(HanyuPinyinToneType.WITHOUT_TONE) }

private fun sortKey(label: String): String {
    val sb = StringBuilder()
    for (c in label) {
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> sb.append(c.lowercaseChar())
            else -> {
                try {
                    val p = PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat)
                    if (p != null && p.isNotEmpty()) sb.append(p[0]) else sb.append(c)
                } catch (_: Exception) { sb.append(c) }
            }
        }
    }
    return sb.toString()
}

private fun loadApps(context: android.content.Context): List<AppInfo> {
    val pm = context.packageManager
    val iconSize = 64
    return pm.getInstalledApplications(0)
        .filter { it.enabled && pm.getLaunchIntentForPackage(it.packageName) != null }
        .mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val icon = try {
                info.loadIcon(pm)?.toBitmap(iconSize, iconSize)
            } catch (_: Throwable) { null }
            AppInfo(
                packageName = info.packageName,
                label = label,
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                icon = icon
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { sortKey(it.label) }
}

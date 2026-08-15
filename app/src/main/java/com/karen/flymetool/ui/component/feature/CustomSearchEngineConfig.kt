package com.karen.flymetool.ui.component.feature

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.CustomSearchEngine
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.data.SearchEngines
import com.karen.flymetool.ui.component.FeatureCard
import com.karen.flymetool.ui.component.SelectionGroup
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CustomSearchEngineConfig(
    packageName: String,
    featureKey: String
) {
    val context = LocalContext.current
    var userEngines by remember {
        mutableStateOf(loadUserEngines(context, packageName, featureKey))
    }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val allEngines = SearchEngines.BUILT_IN + userEngines

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "配置后请重启 Flyme 搜索进程，或在 Flyme 搜索“设置 → 搜索引擎”中查看。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )

        // 内置引擎
        SelectionGroup(
            title = "内置搜索引擎",
            description = "必应和谷歌会直接出现在 Flyme 搜索的“搜索引擎”设置中"
        ) {
            allEngines.filter { it.builtIn }.forEach { engine ->
                EngineRow(engine = engine, onDelete = null)
            }
        }

        // 用户自定义
        SelectionGroup(
            title = "自定义搜索引擎",
            description = "URL 模板中使用 ${CustomSearchEngine.PLACEHOLDER} 代替搜索词，例如 https://example.com/s?q={0}"
        ) {
            if (userEngines.isEmpty()) {
                Text(
                    text = "暂无自定义搜索引擎，可在下方添加",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                userEngines.forEach { engine ->
                    EngineRow(
                        engine = engine,
                        onDelete = {
                            userEngines = userEngines.filterNot { it.id == engine.id }
                            saveUserEngines(context, packageName, featureKey, userEngines)
                        }
                    )
                }
            }
        }

        // 添加表单
        FeatureCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "添加搜索引擎",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "URL 模板（含 ${CustomSearchEngine.PLACEHOLDER}）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "清空",
                        onClick = {
                            name = ""
                            url = ""
                            error = null
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val engineName = name.trim()
                            val engineUrl = url.trim()
                            when {
                                engineName.isEmpty() -> error = "请填写搜索引擎名称"
                                engineUrl.isEmpty() -> error = "请填写 URL 模板"
                                !engineUrl.startsWith("http://") && !engineUrl.startsWith("https://") ->
                                    error = "URL 必须以 http:// 或 https:// 开头"
                                !engineUrl.contains(CustomSearchEngine.PLACEHOLDER) ->
                                    error = "URL 模板必须包含 ${CustomSearchEngine.PLACEHOLDER}"
                                else -> {
                                    val newEngine = CustomSearchEngine(
                                        id = SearchEngines.nextUserEngineId(userEngines),
                                        name = engineName,
                                        urlTemplate = engineUrl
                                    )
                                    userEngines = userEngines + newEngine
                                    saveUserEngines(context, packageName, featureKey, userEngines)
                                    name = ""
                                    url = ""
                                    error = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("添加")
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineRow(
    engine: CustomSearchEngine,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = engine.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = engine.urlTemplate,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onDelete != null) {
            TextButton(
                text = "删除",
                onClick = onDelete
            )
        } else {
            Text(
                text = "内置",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
    }
}

private fun loadUserEngines(
    context: Context,
    packageName: String,
    featureKey: String
): List<CustomSearchEngine> {
    val json = PrefsHelper.getFeatureString(context, packageName, featureKey, "")
    return CustomSearchEngine.decode(json)
}

private fun saveUserEngines(
    context: Context,
    packageName: String,
    featureKey: String,
    engines: List<CustomSearchEngine>
) {
    PrefsHelper.setFeatureString(context, packageName, featureKey, CustomSearchEngine.encode(engines))
}

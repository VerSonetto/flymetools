package com.karen.flymetool.ui.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.BuildConfig
import com.karen.flymetool.ui.component.AppIcon
import com.karen.flymetool.util.GithubAvatarLoader
import com.karen.flymetool.util.FlymeVersionUtils
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val RUYUE_GITHUB_URL = GithubAvatarLoader.profileUrl

private data class OpenSourceProject(
    val name: String,
    val license: String,
    val url: String
)

private val openSourceProjects = listOf(
    OpenSourceProject(
        name = "Miuix",
        license = "Apache-2.0",
        url = "https://github.com/compose-miuix-ui/miuix"
    ),
    OpenSourceProject(
        name = "AndroidX & Jetpack Compose",
        license = "Apache-2.0",
        url = "https://github.com/androidx/androidx"
    ),
    OpenSourceProject(
        name = "pinyin4j",
        license = "BSD License",
        url = "https://github.com/belerweb/pinyin4j"
    )
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.exclude(WindowInsets.navigationBars),
        topBar = {
            SmallTopAppBar(
                title = "关于",
                color = MiuixTheme.colorScheme.background,
                titleColor = MiuixTheme.colorScheme.onBackground,
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(key = "hero", contentType = "hero") {
                AboutHero()
            }

            item(key = "device_title", contentType = "section_title") {
                SectionTitle(
                    title = "设备信息",
                    icon = {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            item(key = "device_info", contentType = "card") {
                DeviceInfoSection()
            }

            item(key = "thanks_title", contentType = "section_title") {
                SectionTitle(
                    title = "特别感谢",
                    icon = {
                        Icon(
                            imageVector = MiuixIcons.Favorites,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            item(key = "ruyue", contentType = "card") {
                RuyueCard(onClick = { openUrl(context, RUYUE_GITHUB_URL) })
            }

            item(key = "opensource_title", contentType = "section_title") {
                SectionTitle(
                    title = "主要开源项目",
                    icon = {
                        Icon(
                            imageVector = MiuixIcons.Community,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            item(key = "opensource", contentType = "card") {
                OpenSourceSection(
                    projects = openSourceProjects,
                    onProjectClick = { project -> openUrl(context, project.url) }
                )
            }

            item(key = "notice_title", contentType = "section_title") {
                SectionTitle(
                    title = "开源声明",
                    icon = {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            item(key = "notice", contentType = "card") {
                LegalNotice()
            }
        }
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                packageName = BuildConfig.APPLICATION_ID,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FlymeTool",
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                VersionBadge(version = BuildConfig.VERSION_NAME)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.dividerLine)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "优化flyme系统部分功能体验",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBadge(text = "Flyme 10–12")
            InfoBadge(text = "Xposed")
            InfoBadge(text = "Miuix")
        }
    }
}

@Composable
private fun VersionBadge(version: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "版本 $version",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceInfoSection() {
    val rows = remember {
        listOf(
            "机型" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            "Android 版本" to "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "系统版本" to FlymeVersionUtils.getFullVersion().ifBlank { Build.DISPLAY }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surface)
    ) {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
            if (index != rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.dividerLine)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RuyueCard(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MiuixTheme.colorScheme.primary,
                            MiuixTheme.colorScheme.primaryContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            RuyueAvatar()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ruyue",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "感谢其的堆叠后台方案",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "github.com/Ruyue-Kinsenka",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = "打开 Ruyue 的 GitHub 主页",
            tint = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun OpenSourceSection(
    projects: List<OpenSourceProject>,
    onProjectClick: (OpenSourceProject) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surface)
    ) {
        projects.forEachIndexed { index, project ->
            OpenSourceRow(
                project = project,
                onClick = { onProjectClick(project) }
            )
            if (index != projects.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.dividerLine)
                )
            }
        }
    }
}

@Composable
private fun OpenSourceRow(
    project: OpenSourceProject,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(7.dp))
            LicenseBadge(license = project.license)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = "打开 ${project.name} 项目主页",
            tint = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun LicenseBadge(license: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = license,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LegalNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Text(
            text = "感谢每一个让 FlymeTool 得以实现的开源项目。相关版权归原作者及贡献者所有。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RuyueAvatar() {
    val avatar by produceState<Bitmap?>(
        initialValue = null,
        key1 = Unit
    ) {
        value = GithubAvatarLoader.load()
    }

    if (avatar != null) {
        Image(
            bitmap = avatar!!.asImageBitmap(),
            contentDescription = "Ruyue 头像",
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        )
    } else {
        Text(
            text = "R",
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}

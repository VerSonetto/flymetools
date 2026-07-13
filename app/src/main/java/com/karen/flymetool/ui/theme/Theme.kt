package com.karen.flymetool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun FlymeToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = lightColorScheme(
                background = Color(0xFFF9F9F9),
                surface = Color.White,
            ),
            darkColors = darkColorScheme(
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
            )
        )
    }

    MiuixTheme(controller = controller) {
        // 桥接 MaterialTheme，让现有 Material3 组件继续工作
        val miuixColors = MiuixTheme.colorScheme
        val bridgeScheme = if (darkTheme) {
            m3DarkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                tertiary = miuixColors.tertiaryContainer,
                onTertiary = miuixColors.onTertiaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onBackgroundVariant,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.dividerLine,
                error = miuixColors.error,
                onError = miuixColors.onError
            )
        } else {
            m3LightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                tertiary = miuixColors.tertiaryContainer,
                onTertiary = miuixColors.onTertiaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onBackgroundVariant,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.dividerLine,
                error = miuixColors.error,
                onError = miuixColors.onError
            )
        }

        MaterialTheme(
            colorScheme = bridgeScheme,
            typography = AppTypography,
            content = content
        )
    }
}

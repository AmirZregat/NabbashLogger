package com.amirzregat.nabbash.internal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The console's own palette, so it looks the same whatever theme the host app uses. */
internal class NabbashColors(
    val isDark: Boolean,
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentBg: Color,
    val success: Color,
    val successBg: Color,
    val warning: Color,
    val warningBg: Color,
    val danger: Color,
    val dangerBg: Color,
)

private val Light = NabbashColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF6F6F3),
    surface2 = Color(0xFFEDEDE9),
    border = Color(0xFFE5E5E0),
    borderStrong = Color(0xFFCDCDC6),
    textPrimary = Color(0xFF1B1B19),
    textSecondary = Color(0xFF55554F),
    textMuted = Color(0xFF75756E),
    accent = Color(0xFF1F5FD6),
    onAccent = Color(0xFFFFFFFF),
    accentBg = Color(0xFFE5EEFC),
    success = Color(0xFF14713A),
    successBg = Color(0xFFDDF4E4),
    warning = Color(0xFF8A5A00),
    warningBg = Color(0xFFFBEFCB),
    danger = Color(0xFFB42323),
    dangerBg = Color(0xFFFCE4E4),
)

private val Dark = NabbashColors(
    isDark = true,
    bg = Color(0xFF131312),
    surface1 = Color(0xFF1D1D1B),
    surface2 = Color(0xFF282826),
    border = Color(0xFF30302D),
    borderStrong = Color(0xFF4A4A45),
    textPrimary = Color(0xFFF1F1EE),
    textSecondary = Color(0xFFBEBEB7),
    textMuted = Color(0xFF96968F),
    accent = Color(0xFF86AEFF),
    onAccent = Color(0xFF0B1D40),
    accentBg = Color(0xFF1A2A4B),
    success = Color(0xFF6BD694),
    successBg = Color(0xFF12301F),
    warning = Color(0xFFE8B95A),
    warningBg = Color(0xFF3A2C0C),
    danger = Color(0xFFFF8A8A),
    dangerBg = Color(0xFF401818),
)

internal val LocalColors = staticCompositionLocalOf { Light }

@Composable
internal fun NabbashTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (c.isDark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = c.accentBg,
            onPrimaryContainer = c.accent,
            secondaryContainer = c.accentBg,
            onSecondaryContainer = c.textPrimary,
            background = c.bg,
            onBackground = c.textPrimary,
            surface = c.bg,
            onSurface = c.textPrimary,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface1,
            surfaceContainer = c.surface1,
            surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface2,
            outline = c.borderStrong,
            outlineVariant = c.border,
            error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = c.accentBg,
            onPrimaryContainer = c.accent,
            secondaryContainer = c.accentBg,
            onSecondaryContainer = c.textPrimary,
            background = c.bg,
            onBackground = c.textPrimary,
            surface = c.bg,
            onSurface = c.textPrimary,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface1,
            surfaceContainer = c.surface1,
            surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface2,
            outline = c.borderStrong,
            outlineVariant = c.border,
            error = c.danger,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalColors provides c, LocalContentColor provides c.textPrimary, content = content)
    }
}

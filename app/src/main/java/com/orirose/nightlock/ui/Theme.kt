package com.orirose.nightlock.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// Always dark: this app is used at bedtime.
private val NightColors = darkColorScheme(
    primary = Color(0xFFB9B4FF),
    onPrimary = Color(0xFF221C5C),
    primaryContainer = Color(0xFF39337A),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFFC6C3DC),
    onSecondary = Color(0xFF2E2D42),
    tertiary = Color(0xFFFFB4A8),
    onTertiary = Color(0xFF561E16),
    tertiaryContainer = Color(0xFF73342A),
    onTertiaryContainer = Color(0xFFFFDAD4),
    background = Color(0xFF0D0E17),
    onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF0D0E17),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF2A2A3A),
    onSurfaceVariant = Color(0xFFC8C5D4),
    surfaceContainerLow = Color(0xFF141521),
    surfaceContainer = Color(0xFF181926),
    surfaceContainerHigh = Color(0xFF1F2030),
    surfaceContainerHighest = Color(0xFF26273A),
    outline = Color(0xFF928F9F),
    outlineVariant = Color(0xFF47464F),
)

/** Always left to right: the app is in English, even on a phone set to Hebrew (Compose ignores supportsRtl). */
@Composable
fun NightLockTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = NightColors, content = content)
    }
}

/** Edge-to-edge with light bar icons and no scrim, even when the phone itself is in light mode. */
fun ComponentActivity.enableNightEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
}

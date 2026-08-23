package com.homecam.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF7FD1B9)
private val TealDark = Color(0xFF1E6B58)
private val Ink = Color(0xFF101418)
private val Alarm = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Ink,
    secondary = TealDark,
    background = Ink,
    surface = Color(0xFF171C22),
    error = Alarm,
)

private val LightColors = lightColorScheme(
    primary = TealDark,
    onPrimary = Color.White,
    secondary = Teal,
    error = Alarm,
)

@Composable
fun HomecamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

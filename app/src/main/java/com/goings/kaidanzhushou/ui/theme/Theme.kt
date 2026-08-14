package com.goings.kaidanzhushou.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF1A5BE3)
val AppBackground = Color(0xFFF7F8FC)
val Ink = Color(0xFF121A29)
val Secondary = Color(0xFF7A8496)
val Separator = Color(0xFFDCE1EA)
val Success = Color(0xFF159E5E)
val Warning = Color(0xFFF2921A)
val ErrorRed = Color(0xFFD92E2E)

private val Colors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    background = AppBackground,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    error = ErrorRed,
)

@Composable
fun KaidanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = MaterialTheme.typography, content = content)
}

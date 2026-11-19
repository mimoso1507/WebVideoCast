package com.ondacast.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF6C9CFF),
    onPrimary = Color(0xFF002B6B),
    secondary = Color(0xFF9DBBFF),
    background = Color(0xFF10131A),
    surface = Color(0xFF10131A),
    surfaceVariant = Color(0xFF1C2230),
    onSurface = Color(0xFFE4E7EF),
    onSurfaceVariant = Color(0xFFA9B0C2),
    surfaceContainer = Color(0xFF171C27),
    surfaceContainerHigh = Color(0xFF1E2431),
    error = Color(0xFFFF8A80),
)

@Composable
fun OndaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

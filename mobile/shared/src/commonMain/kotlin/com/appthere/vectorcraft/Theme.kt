package com.appthere.vectorcraft

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// VectorCraft's own neutral palette (the desktop app's default "Medium Dark" and "Light" themes
// approximated as Material 3 schemes), with a blue accent.
private val Dark = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1F4A7A),
    onPrimaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF262626),
    surface = Color(0xFF323232),
    surfaceContainerLow = Color(0xFF2B2B2B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1E5FA8),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    background = Color(0xFFF0F0F0),
    surface = Color(0xFFFAFAFA),
    surfaceContainerLow = Color(0xFFF3F3F3),
)

@Composable
fun VectorCraftTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

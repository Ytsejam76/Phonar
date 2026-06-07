// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhonarColors = darkColorScheme(
    primary = Color(0xFF7CFF43),
    onPrimary = Color(0xFF07120D),
    secondary = Color(0xFF78D0C0),
    onSecondary = Color(0xFF061012),
    background = Color(0xFF021010),
    onBackground = Color(0xFFE8FFF0),
    surface = Color(0xFF061012),
    onSurface = Color(0xFFE8FFF0),
    surfaceVariant = Color(0xFF0B1B18),
    onSurfaceVariant = Color(0xFFBDE7DC),
)

@Composable
fun PhonarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhonarColors, content = content)
}

// vim: set ts=4 sw=4 et:

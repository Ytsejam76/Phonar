// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhonarColors = lightColorScheme(
    primary = Color(0xFF075985),
    onPrimary = Color.White,
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
)

@Composable
fun PhonarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhonarColors, content = content)
}

// vim: set ts=4 sw=4 et:

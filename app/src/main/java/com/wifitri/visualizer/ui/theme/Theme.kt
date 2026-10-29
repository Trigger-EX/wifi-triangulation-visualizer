package com.wifitri.visualizer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B0F2B)
val NavyCard = Color(0xFF161B45)
val NeonCyan = Color(0xFF00E5FF)
val NeonMagenta = Color(0xFFFF2BD6)
val NeonOrange = Color(0xFFFF9100)
val NeonLime = Color(0xFFB6FF00)
val HotRed = Color(0xFFFF3D3D)
val ColdBlue = Color(0xFF2979FF)

private val scheme = darkColorScheme(
    primary = NeonCyan, secondary = NeonMagenta, tertiary = NeonOrange,
    background = Navy, surface = NavyCard, onBackground = Color.White, onSurface = Color.White,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

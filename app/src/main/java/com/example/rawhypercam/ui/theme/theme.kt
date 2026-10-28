package com.example.rawhypercam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HyperDarkColors = darkColorScheme(
    primary = Color(0xFFFF4B2B),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF0D0D0D),
    onSurface = Color.White
)

@Composable
fun RawHyperCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HyperDarkColors, content = content)
}
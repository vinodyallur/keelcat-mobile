package com.keelcat.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFF0B31C) // iQOO accent
private val Ink = Color(0xFF0B0B0F)

private val LightColors = lightColorScheme(primary = Ink, secondary = Amber)
private val DarkColors = darkColorScheme(primary = Amber, secondary = Amber)

@Composable
fun KeelCatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

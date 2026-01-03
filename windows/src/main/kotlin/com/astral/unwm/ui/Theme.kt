package com.astral.unwm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

val DarkColorScheme = darkColorScheme(
    // Customize as needed
)

val LightColorScheme = lightColorScheme(
    // Customize as needed
)

@Composable
fun AstralUnwmTheme(
    darkTheme: Boolean = true, // Default to dark for desktop usually, or system check
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

package com.astral.unwm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.astral.unwm.ui.theme.PurplePrimary
import com.astral.unwm.ui.theme.SurfaceLight
import com.astral.unwm.ui.theme.TextOnSurface

private val LightColors = lightColorScheme(
    primary = PurplePrimary,
    onPrimary = TextOnSurface,
    background = SurfaceLight,
    onBackground = TextOnSurface,
    surface = SurfaceLight,
    onSurface = TextOnSurface
)

@Composable
fun AstralUnwmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}

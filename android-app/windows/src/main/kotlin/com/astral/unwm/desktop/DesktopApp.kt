package com.astral.unwm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    configureRenderingFallback()

    application {
        Window(onCloseRequest = ::exitApplication, title = "AstralUNWM") {
            MaterialTheme {
                Surface(modifier = Modifier.padding(24.dp)) {
                    DesktopApp()
                }
            }
        }
    }
}

private fun configureRenderingFallback() {
    // Compose for Desktop can render a black window on some Windows GPUs when the hardware
    // renderer fails to initialize. Forcing software rendering restores a visible UI while
    // still allowing users to override the renderer via the standard skiko flag.
    val renderApiSet = System.getProperty("skiko.renderApi")?.isNotBlank() == true
    if (!renderApiSet) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }
}

@Composable
private fun DesktopApp() {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "AstralUNWM (Desktop Preview)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "The Windows build now starts with a simple launcher so you can verify the installer works.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Future updates will bring the full unwatermarking workflow to desktop.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { uriHandler.openUri("https://github.com/AstralDawn/AstralUNWM") }) {
                Text("View project on GitHub")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = { uriHandler.openUri("https://github.com/AstralDawn/AstralUNWM/issues") }) {
                Text("Report an issue")
            }
        }

        Text(
            text = "If the app window does not appear, please ensure Windows has permission to run downloaded executables.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

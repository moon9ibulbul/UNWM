import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.astral.unwm.ui.App
import nu.pattern.OpenCV

fun main() = application {
    // Initialize OpenCV
    // Using openpnp/opencv, we usually load it like this:
    OpenCV.loadLocally()

    Window(onCloseRequest = ::exitApplication, title = "AstralUNWM") {
        App()
    }
}

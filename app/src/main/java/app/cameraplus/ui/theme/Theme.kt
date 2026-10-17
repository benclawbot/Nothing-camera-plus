package app.cameraplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Monochrome palette. The app is dark-only on purpose: a camera viewfinder is
// the content, and quiet black-and-white chrome keeps it that way.
val Black = Color(0xFF000000)
val OffBlack = Color(0xFF121212)
val White = Color(0xFFFFFFFF)
// Interface copy is intentionally monochrome with no mid-grey text: every
// label remains legible over a moving camera preview.
val DimWhite = White
val FaintWhite = Color(0x33FFFFFF)

private val MonochromeColors = darkColorScheme(
    primary = White,
    onPrimary = Black,
    background = Black,
    onBackground = White,
    surface = OffBlack,
    onSurface = White,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = DimWhite,
    outline = FaintWhite,
)

@Composable
fun CameraPlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonochromeColors,
        content = content,
    )
}

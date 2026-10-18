package app.cameraplus

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.cameraplus.ui.CameraKeyEvents
import app.cameraplus.ui.CameraPlusApp
import app.cameraplus.ui.ModeShortcut
import app.cameraplus.ui.theme.CameraPlusTheme

class MainActivity : ComponentActivity() {

    /**
     * Set by [onCreate]/[onNewIntent] when the launch carries shortcut
     * extras; the Compose tree applies it once and consumes it.
     */
    private var pendingShortcut by mutableStateOf<ModeShortcut.Selection?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Camera-style fullscreen (the original camera theme does the same):
        // the status bar previously sat on top of the app's own top bar.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pendingShortcut = ModeShortcut.selectionFor(intent)
        setContent {
            CameraPlusTheme {
                CameraPlusApp(
                    shortcut = pendingShortcut,
                    onShortcutConsumed = { pendingShortcut = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShortcut = ModeShortcut.selectionFor(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutterOnVolume = getSharedPreferences("capture_preferences", MODE_PRIVATE)
            .getBoolean("volume_shutter", false)
        if (shutterOnVolume && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            CameraKeyEvents.emitVolumeShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

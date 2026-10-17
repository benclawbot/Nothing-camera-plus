package app.cameraplus

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.cameraplus.ui.CameraPlusApp
import app.cameraplus.ui.CameraKeyEvents
import app.cameraplus.ui.theme.CameraPlusTheme

class MainActivity : ComponentActivity() {
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
        setContent {
            CameraPlusTheme {
                CameraPlusApp()
            }
        }
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
